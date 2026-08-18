package org.thisisthepy.python.multiplatform.packpack.dependency.backend

import org.thisisthepy.python.multiplatform.packpack.utils.DownloadSpec
import org.thisisthepy.python.multiplatform.packpack.utils.Downloader
import org.thisisthepy.python.multiplatform.packpack.utils.Platforms
import org.thisisthepy.python.multiplatform.packpack.utils.extractArchive
import org.thisisthepy.python.multiplatform.packpack.utils.findProjectRoot
import java.io.File

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

    override suspend fun installPython(
        pythonVersion: String,
        targetPlatform: String?,
    ): Result<String> {
        if (pythonVersion != "3.13") {
            return Result.failure(IllegalArgumentException("Only Python 3.13 is supported due to python-mutliplatform limitations"))
        } // TODO: Remove when python-multiplatform supports more versions

        val targetPlatform =
            Platforms.normalizeTarget(targetPlatform ?: Platforms.detectHostTarget())
                ?: return Result.failure(IllegalArgumentException("Unsupported target platform: $targetPlatform"))

        val (downloadFileName, fileName) =
            when (targetPlatform) {
                "windows", "x86_64-pc-windows-msvc" -> {
                    "cpython-3.13.0+20241008-x86_64-pc-windows-msvc-shared-pgo-full.tar.zst" to "x86_64-pc-windows-msvc"
                }

                "linux", "x86_64-unknown-linux-gnu" -> {
                    "cpython-3.13.0+20241008-x86_64_v4-unknown-linux-gnu-lto-full.tar.zst" to "x86_64-unknown-linux-gnu"
                }

                "macos", "aarch64-apple-darwin" -> {
                    "cpython-3.13.0+20241008-aarch64-apple-darwin-pgo+lto-full.tar.zst" to "aarch64-apple-darwin"
                }

                "x86_64-apple-darwin" -> {
                    "cpython-3.13.0+20241008-x86_64-apple-darwin-pgo+lto-full.tar.zst" to "x86_64-apple-darwin"
                }

                "aarch64-linux-android" -> {
                    "aarch64-linux-android.tar.xz" to "aarch64-linux-android"
                }

                "x86_64-linux-android" -> {
                    "x86_64-linux-android.tar.xz" to "x86_64-linux-android"
                }

                "arm64-apple-ios" -> {
                    "arm64-iphoneos.zip" to "arm64-apple-ios"
                }

                "arm64-apple-ios-simulator" -> {
                    "arm64-iphonesimulator.zip" to "arm64-apple-ios-simulator"
                }

                "x86_64-apple-ios-simulator" -> {
                    "x86_64-iphonesimulator.zip" to "x86_64-apple-ios-simulator"
                }

                else -> {
                    return Result.failure(IllegalArgumentException("Unsupported target platform: $targetPlatform"))
                }
            }
        val baseUrl = "https://github.com/thisisthepy/python-multiplatform/raw/release/binary"
        val url = "$baseUrl/$downloadFileName"

        val hostPlatform = Platforms.detectHostTarget()

        val installDir: File =
            if (targetPlatform == hostPlatform) {
                File(findProjectRoot(), ".venv")
            } else {
                val dirName =
                    when (targetPlatform) {
                        "windows", "x86_64-pc-windows-msvc" -> "windows_amd64"
                        "linux", "x86_64-unknown-linux-gnu" -> "linux_x86_64"
                        "macos", "aarch64-apple-darwin" -> "macos_arm64"
                        "x86_64-apple-darwin" -> "macos_x86_64"
                        "aarch64-linux-android" -> "android_arm64"
                        "x86_64-linux-android" -> "android_x86_64"
                        "arm64-apple-ios" -> "ios_arm64"
                        "arm64-apple-ios-simulator" -> "ios_simulator_arm64"
                        "x86_64-apple-ios-simulator" -> "ios_simulator_x86_64"
                        else -> return Result.failure(IllegalArgumentException("Unsupported target platform: $targetPlatform"))
                    }
                File(findProjectRoot(), dirName)
            }

        val downloadSpec = DownloadSpec(url, downloadFileName, installDir)
        val result = Downloader().use { downloader -> downloader.download(downloadSpec) }
        println("Downloaded path: ${result.filePath}")

        if (!result.success) throw RuntimeException("Download failed: ${result.error}")

        // We use prefixFilter = "python/install/" to prevent sibling directories in the full tarball
        // (like python/build/, python/licenses/, and python/PYTHON.json) from being mistakenly
        // extracted into the root of the installation directory.
        extractArchive(
            File(result.filePath),
            installDir,
            stripComponents = 2,
            prefixFilter = "python/install/"
        )

        // Bridge the project-relative install location back to the version registry so that
        // `find`/`list`/`uninstall` (which only take a version, not a project/target) can locate it.
        // See the KDoc on registerInstalledVersion/registryFile for why this indirection exists.
        registerInstalledVersion(pythonVersion, installDir)

        return Result.success("Downloaded ${result.filePath} for Python $pythonVersion")
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
