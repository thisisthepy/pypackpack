package org.thisisthepy.python.multiplatform.packpack.dependency.backend

import org.thisisthepy.python.multiplatform.packpack.utils.DownloadSpec
import org.thisisthepy.python.multiplatform.packpack.utils.Downloader
import org.thisisthepy.python.multiplatform.packpack.utils.Platforms
import org.thisisthepy.python.multiplatform.packpack.utils.extractArchive
import org.thisisthepy.python.multiplatform.packpack.utils.findProjectRoot
import org.thisisthepy.python.multiplatform.packpack.utils.verifySha256
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

abstract class DefaultBackend : BackendInterface {
    protected open fun pythonInstallRoot(): File = File(System.getProperty("user.home"), ".pypackpack/python")

    /**
     * `installPython` may place the actual interpreter outside [pythonInstallRoot] (e.g. under
     * `<project>/.venv` for the host target, or `<project>/<target-dir-name>` for a cross target),
     * because that is where the rest of the toolchain (uv, crossenv) expects to find it.
     *
     * `list`/`find`/`uninstall` only take a version (no project/target), so they cannot recompute
     * that project-relative path on their own. This registry is the bridge: a flat
     * `version=absolutePath` file kept inside [pythonInstallRoot] that `installPython` writes to on
     * success, and that `find`/`list`/`uninstall` consult before falling back to their original
     * behavior of looking for `pythonInstallRoot()/<version>` directly. The fallback is kept so a
     * directory placed straight under `pythonInstallRoot()` (as the existing tests, and any manual
     * setup, do) is still discovered without ever touching the registry.
     */
    private fun registryFile(): File = File(pythonInstallRoot(), "registry.properties")

    private fun readRegistry(): Map<String, String> {
        val file = registryFile()
        if (!file.exists()) return emptyMap()
        return file
            .readLines()
            .mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx <= 0) null else line.substring(0, idx) to line.substring(idx + 1)
            }.toMap()
    }

    private fun writeRegistry(entries: Map<String, String>) {
        registryFile().writeText(entries.entries.joinToString(System.lineSeparator()) { "${it.key}=${it.value}" })
    }

    protected fun registerInstalledVersion(
        pythonVersion: String,
        installDir: File,
    ) {
        pythonInstallRoot().mkdirs()
        val entries = readRegistry().toMutableMap()
        entries[pythonVersion] = installDir.absolutePath
        writeRegistry(entries)
    }

    private fun unregisterInstalledVersion(pythonVersion: String) {
        val entries = readRegistry().toMutableMap()
        if (entries.remove(pythonVersion) != null) {
            writeRegistry(entries)
        }
    }

    private fun requirePythonVersionName(pythonVersion: String) {
        require(pythonVersion.isNotBlank()) { "Python version cannot be blank" }
        require(!pythonVersion.contains('/') && !pythonVersion.contains('\\')) {
            "Python version cannot contain path separators"
        }
    }

    /** The project `installPython` places the interpreter in. A seam for tests; see rule 13 in AGENTS.md. */
    // No pyproject.toml above the working directory: the working directory itself, which is what
    // the previous `File(findProjectRoot(), …)` resolved to when the root was null.
    protected open fun projectRoot(): File = findProjectRoot() ?: File(System.getProperty("user.dir"))

    /** The host's canonical target triple. A seam for tests. */
    protected open fun hostTarget(): String = Platforms.detectHostTarget()

    /** The pinned archive for a (version, canonical target) pair. A seam for tests. */
    protected open fun resolveDistribution(
        pythonVersion: String,
        canonicalTarget: String,
    ): Result<PythonDistribution> = PythonDistributions.resolve(pythonVersion, canonicalTarget)

    /** Downloads [url] to `destDir/fileName`. A seam so tests never touch the network. */
    protected open suspend fun downloadArchive(
        url: String,
        fileName: String,
        destDir: File,
    ): Result<File> {
        val result = Downloader().use { downloader -> downloader.download(DownloadSpec(url, fileName, destDir)) }
        return if (result.success) {
            Result.success(File(result.filePath))
        } else {
            Result.failure(IllegalStateException("Download of $url failed: ${result.error}"))
        }
    }

    /**
     * Installs the pinned CPython for ([pythonVersion], [targetPlatform]) (`docs/SPEC.md`,
     * "Installing a ppp Python distribution"):
     *
     * 1. resolve the pair in [PythonDistributions] -- an unknown or unpinned pair is refused with the
     *    supported list;
     * 2. download into a staging directory beside the install directory;
     * 3. verify the archive's SHA-256 against the pin -- a mismatch fails naming the artifact, the
     *    expected and the actual digest;
     * 4. extract into the staging directory, then move the tree into place.
     *
     * The install directory is [installDir] when given: the tree replaces whatever is there, and
     * neither [projectRoot] nor the registry is touched. Otherwise it is `<project>/.venv` (host) or
     * `<project>/<target-dir-name>`, and the location is registered for `find`/`list`/`uninstall`.
     *
     * Any failure deletes the staging directory, so a refused or broken download leaves neither the
     * archive nor a partial tree behind, and the install directory untouched.
     */
    override suspend fun installPython(
        pythonVersion: String,
        targetPlatform: String?,
        installDir: File?,
    ): Result<String> {
        val canonicalTarget =
            Platforms.normalizeTarget(targetPlatform ?: hostTarget())
                ?: return Result.failure(IllegalArgumentException("Unsupported target platform: $targetPlatform"))

        val distribution = resolveDistribution(pythonVersion, canonicalTarget).getOrElse { return Result.failure(it) }
        val expectedSha256 =
            distribution.sha256
                ?: return Result.failure(IllegalStateException("No pinned SHA-256 for ${distribution.fileName}"))

        val destination =
            if (installDir != null) {
                installDir.absoluteFile
            } else {
                val dirName =
                    if (canonicalTarget == hostTarget()) {
                        ".venv"
                    } else {
                        installDirName(canonicalTarget)
                            ?: return Result.failure(IllegalArgumentException("Unsupported target platform: $canonicalTarget"))
                    }
                File(projectRoot(), dirName)
            }
        val parent =
            destination.parentFile
                ?: return Result.failure(IllegalArgumentException("Install directory has no parent: ${destination.path}"))

        val staging = File(parent, ".${destination.name}.pypackpack-staging")
        return try {
            staging.deleteRecursively()
            val downloadDir = File(staging, "download")
            val extractDir = File(staging, "extract")

            val archive =
                downloadArchive(distribution.url, distribution.fileName, downloadDir).getOrElse { return Result.failure(it) }
            verifySha256(archive, expectedSha256, distribution.fileName).getOrElse { return Result.failure(it) }

            extractArchive(archive, extractDir, distribution.stripComponents, distribution.prefixFilter)
            if (extractDir.listFiles().isNullOrEmpty()) {
                return Result.failure(
                    IllegalStateException(
                        "${distribution.fileName} matched its SHA-256 but extracted nothing under " +
                            "'${distribution.prefixFilter ?: ""}'; its layout is not the one expected",
                    ),
                )
            }
            if (installDir != null) {
                // An explicit directory belongs to the caller (e.g. toolchain's
                // build/pythonRuntime/<triple>/<version>/): replace it whole and register nothing.
                replaceWithExtractedTree(extractDir, destination, File(staging, "previous"))
            } else {
                placeExtractedTree(extractDir, destination)

                // Bridge the project-relative install location back to the version registry so that
                // `find`/`list`/`uninstall` (which only take a version, not a project/target) can locate it.
                // See the KDoc on registerInstalledVersion/registryFile for why this indirection exists.
                registerInstalledVersion(pythonVersion, destination)
            }

            Result.success(
                "Installed Python ${distribution.version} for $canonicalTarget into ${destination.absolutePath} " +
                    "(${distribution.fileName}, sha256 $expectedSha256)",
            )
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * Replaces [installDir] with [extractDir] by renames within one parent directory: a previous
     * [installDir] is first moved aside to [previous] (inside the staging directory, which the caller
     * deletes), then [extractDir] is renamed into place. If that rename fails, the previous tree is
     * moved back, so [installDir] is either the old tree or the new one.
     */
    private fun replaceWithExtractedTree(
        extractDir: File,
        installDir: File,
        previous: File,
    ) {
        val hadPrevious = Files.exists(installDir.toPath(), LinkOption.NOFOLLOW_LINKS)
        if (hadPrevious) rename(installDir, previous)
        try {
            rename(extractDir, installDir)
        } catch (e: Exception) {
            if (hadPrevious) rename(previous, installDir)
            throw e
        }
        // A previous symbolic link is removed as a link, so deleting the staging directory never
        // reaches through it.
        if (Files.isSymbolicLink(previous.toPath())) Files.delete(previous.toPath())
    }

    private fun rename(
        from: File,
        to: File,
    ) {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(from.toPath(), to.toPath())
        }
    }

    /**
     * Moves a fully extracted tree to [installDir]. A missing [installDir] is replaced by a rename;
     * an existing one (a host `.venv` uv already made) has the tree copied over it, which is what
     * extracting straight into it used to do.
     */
    private fun placeExtractedTree(
        extractDir: File,
        installDir: File,
    ) {
        if (!installDir.exists()) {
            installDir.parentFile?.mkdirs()
            try {
                Files.move(extractDir.toPath(), installDir.toPath(), StandardCopyOption.ATOMIC_MOVE)
                return
            } catch (e: IOException) {
                // Different file store, or no atomic rename; fall through to the copy.
            }
        }
        Files.walk(extractDir.toPath()).use { paths ->
            paths.forEach { source ->
                val target = installDir.toPath().resolve(extractDir.toPath().relativize(source).toString())
                when {
                    Files.isSymbolicLink(source) -> {
                        Files.deleteIfExists(target)
                        Files.createDirectories(target.parent)
                        Files.copy(source, target, LinkOption.NOFOLLOW_LINKS)
                    }

                    Files.isDirectory(source) -> {
                        Files.createDirectories(target)
                    }

                    else -> {
                        Files.createDirectories(target.parent)
                        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
                    }
                }
            }
        }
    }

    private fun installDirName(canonicalTarget: String): String? =
        when (canonicalTarget) {
            "x86_64-pc-windows-msvc" -> "windows_amd64"
            "aarch64-pc-windows-msvc" -> "windows_arm64"
            "x86_64-unknown-linux-gnu" -> "linux_x86_64"
            "aarch64-unknown-linux-gnu" -> "linux_arm64"
            "aarch64-apple-darwin" -> "macos_arm64"
            "x86_64-apple-darwin" -> "macos_x86_64"
            "aarch64-linux-android" -> "android_arm64"
            "x86_64-linux-android" -> "android_x86_64"
            "arm64-apple-ios" -> "ios_arm64"
            "arm64-apple-ios-simulator" -> "ios_simulator_arm64"
            "x86_64-apple-ios-simulator" -> "ios_simulator_x86_64"
            else -> null
        }

    override fun uninstallPython(pythonVersion: String): Result<String> =
        runCatching {
            requirePythonVersionName(pythonVersion)

            val registeredPath = readRegistry()[pythonVersion]?.let { File(it) }
            val installDir =
                registeredPath?.takeIf { it.exists() } ?: File(pythonInstallRoot(), pythonVersion)
            require(installDir.exists()) { "Python $pythonVersion is not installed" }
            require(installDir.isDirectory) { "Python $pythonVersion install path is not a directory: ${installDir.absolutePath}" }

            if (!installDir.deleteRecursively()) {
                throw IllegalStateException("Failed to remove ${installDir.absolutePath}")
            }
            unregisterInstalledVersion(pythonVersion)

            "Removed Python $pythonVersion from ${installDir.absolutePath}"
        }

    override fun findPython(pythonVersion: String): Result<String> =
        runCatching {
            requirePythonVersionName(pythonVersion)

            val registeredPath = readRegistry()[pythonVersion]?.let { File(it) }
            if (registeredPath != null && registeredPath.exists() && registeredPath.isDirectory) {
                return@runCatching registeredPath.absolutePath
            }

            val installDir = File(pythonInstallRoot(), pythonVersion)
            require(installDir.exists() && installDir.isDirectory) { "Python $pythonVersion is not installed" }

            installDir.absolutePath
        }

    override fun listPython(): Result<String> =
        runCatching {
            val installRoot = pythonInstallRoot()
            val registeredVersions = readRegistry().keys
            val dirVersions =
                if (installRoot.exists()) {
                    require(installRoot.isDirectory) { "Python install root is not a directory: ${installRoot.absolutePath}" }
                    installRoot
                        .listFiles()
                        .orEmpty()
                        .filter { it.isDirectory }
                        .map { it.name }
                } else {
                    emptyList()
                }
            val versions = (registeredVersions + dirVersions).toSortedSet()
            if (versions.isEmpty()) {
                "No Python versions installed"
            } else {
                versions.joinToString(System.lineSeparator())
            }
        }
}
