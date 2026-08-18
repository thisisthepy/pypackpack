package org.thisisthepy.python.multiplatform.packpack.bundle.fat

import org.junit.jupiter.api.io.TempDir
import org.thisisthepy.python.multiplatform.packpack.bundle.BundleRequest
import org.thisisthepy.python.multiplatform.packpack.bundle.BundleType
import org.thisisthepy.python.multiplatform.packpack.bundle.BundlerInterface
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Specification tests for bundle type `fat`.
 *
 * Spec source: `docs/SPEC.md` -> "Bundle Type" -> "`fat`: Produces a wheel-like archive that
 * includes the current package together with its dependencies", plus the output-path convention
 * `<package>/build/packpack/<bundle type>/<build type>/<build level>` from the same document.
 *
 * The SPEC does not say *where* "dependencies" come from. This codebase has no mechanism to vendor
 * third-party (PyPI) dependencies into a directory: `docs/SPEC.md`'s own "Not yet implemented
 * (target)" list under "Per-package target dependency management" names "Creating and maintaining a
 * dedicated venv per target for CrossEnv dependencies" as unbuilt, and nothing in
 * `dependency/backend` ever calls `uv pip install --target` or unpacks a downloaded wheel anywhere
 * (verified by grepping `packpack/src/main` for `site-packages`/`crossenv`/`.venv` -- the only hits
 * are: `SingleWheelBundler`'s own destdir lookup, `ResourceBundler`'s bytecode-compiler lookup under
 * `<project>/.venv`, and `DevEnv`'s `.venv` path for the *dev* environment, which never receives a
 * per-target install either). So `FatWheelBundler` scopes "dependencies" to what is actually
 * resolvable without inventing that missing subsystem: **workspace-local** Python dependencies --
 * other `pypackpack` packages in the same workspace, declared as plain entries in
 * `project.dependencies` and matched by name against `tool.uv.workspace.members` (both already-real
 * mechanisms; see `Workspace.kt` and `CrossEnv.resolvePackageSpec`). A dependency name that does not
 * match a workspace member cannot be vendored today and fails loudly naming the missing subsystem,
 * rather than silently shipping an incomplete "fat" wheel.
 */
class FatWheelBundlerTest {
    @TempDir
    lateinit var tempDir: File

    private fun workspacePyproject(memberPaths: List<String>) {
        File(tempDir, "pyproject.toml").writeText(
            """
            [project]
            name = "workspace-root"
            version = "0.0.0"

            [tool.uv.workspace]
            members = [${memberPaths.joinToString(", ") { "\"$it\"" }}]
            """.trimIndent(),
        )
    }

    private fun packageDir(
        relativePath: String,
        name: String,
        version: String = "0.1.0",
        dependencies: List<String> = emptyList(),
    ): File {
        val dir = File(tempDir, relativePath)
        dir.mkdirs()
        val depsLine =
            if (dependencies.isEmpty()) {
                ""
            } else {
                "dependencies = [${dependencies.joinToString(", ") { "\"$it\"" }}]"
            }
        File(dir, "pyproject.toml").writeText(
            """
            [project]
            name = "$name"
            version = "$version"
            $depsLine
            """.trimIndent(),
        )
        return dir
    }

    /** Writes a file under `<pkg>/dist/<prefix>/site-packages/<relativePath>`, mirroring a Meson destdir install. */
    private fun writeDestdir(
        pkg: File,
        relativePath: String,
        content: ByteArray = "x".toByteArray(),
        prefix: String = "usr/local/lib/python3.13",
    ) {
        val file = File(File(pkg, "dist/$prefix/site-packages"), relativePath)
        file.parentFile.mkdirs()
        file.writeBytes(content)
    }

    /**
     * Writes a `<pkg>/build/crossenv/<target>/<distInfoName>.dist-info/RECORD` plus the files it
     * lists, mirroring what `CrossEnv.syncDependencies` (via `uv pip install -r pyproject.toml
     * --target ... --python-platform ...`) leaves behind for a target: a plain directory holding
     * one `.dist-info` per installed distribution (declared or transitive) next to that
     * distribution's own files, with no venv/executable involved.
     */
    private fun writeCrossenvDistInfo(
        pkg: File,
        target: String,
        distInfoName: String,
        packageFiles: Map<String, String>,
    ) {
        val crossenvDir = File(pkg, "build/crossenv/$target")
        val distInfoDir = File(crossenvDir, "$distInfoName.dist-info")
        distInfoDir.mkdirs()
        val recordLines = mutableListOf<String>()
        packageFiles.forEach { (relPath, content) ->
            val file = File(crossenvDir, relPath)
            file.parentFile.mkdirs()
            file.writeText(content)
            recordLines += "$relPath,sha256=deadbeef,${content.toByteArray().size}"
        }
        recordLines += "$distInfoName.dist-info/METADATA,sha256=deadbeef,10"
        recordLines += "$distInfoName.dist-info/RECORD,,"
        File(distInfoDir, "RECORD").writeText(recordLines.joinToString("\n"))
        File(distInfoDir, "METADATA").writeText("Metadata-Version: 2.1\n")
    }

    private fun bundler() = BundlerInterface.create(BundleType.FAT)

    private fun zipEntryNames(whl: File): Set<String> = ZipFile(whl).use { zf -> zf.entries().asSequence().map { it.name }.toSet() }

    private fun zipEntryText(whl: File, entryName: String): String = ZipFile(whl).use { zf ->
        val entry = zf.getEntry(entryName) ?: error("entry not found: $entryName")
        zf.getInputStream(entry).bufferedReader().readText()
    }

    @Test
    fun create_returnsFatWheelBundlerForFatType() {
        assertTrue(BundlerInterface.create(BundleType.FAT) is FatWheelBundler)
    }

    @Test
    fun bundle_withNoDependenciesProducesSameContentAsSingle() {
        val pkg = packageDir("core", "core")
        writeDestdir(pkg, "core/__init__.py")

        val result =
            bundler()
                .bundle(BundleRequest(packageDir = pkg, target = "macos"))
                .also { assertTrue(it.isSuccess, it.exceptionOrNull()?.stackTraceToString()) }
                .getOrThrow()

        assertEquals(
            File(pkg, "build/packpack/fat/debug/instant").canonicalFile,
            result.outputDir.canonicalFile,
        )
        assertNotNull(result.artifactFile)
        assertEquals(BundleType.FAT, result.bundleType)
        assertTrue(zipEntryNames(result.artifactFile!!).contains("core/__init__.py"))
    }

    @Test
    fun bundle_vendorsWorkspaceLocalDependencyFiles() {
        workspacePyproject(listOf("core", "libs/helper"))
        val helper = packageDir("libs/helper", "helper")
        writeDestdir(helper, "helper/__init__.py", "HELPER = 1\n".toByteArray())

        val core = packageDir("core", "core", dependencies = listOf("helper"))
        writeDestdir(core, "core/__init__.py")

        val result = bundler().bundle(BundleRequest(packageDir = core, target = "macos")).getOrThrow()
        val entries = zipEntryNames(result.artifactFile!!)

        assertTrue(entries.contains("core/__init__.py"), entries.toString())
        assertTrue(entries.contains("helper/__init__.py"), entries.toString())
    }

    @Test
    fun bundle_matchesWorkspaceDependencyNameCaseInsensitivelyAndIgnoresVersionSpecifiers() {
        workspacePyproject(listOf("core", "libs/My-Helper"))
        val helper = packageDir("libs/My-Helper", "My-Helper")
        writeDestdir(helper, "my_helper/__init__.py")

        val core = packageDir("core", "core", dependencies = listOf("my-helper>=1.0"))
        writeDestdir(core, "core/__init__.py")

        val result = bundler().bundle(BundleRequest(packageDir = core, target = "macos")).getOrThrow()
        val entries = zipEntryNames(result.artifactFile!!)

        assertTrue(entries.contains("my_helper/__init__.py"), entries.toString())
    }

    @Test
    fun bundle_failsNamingDependenciesThatAreNotWorkspaceMembers() {
        workspacePyproject(listOf("core"))
        val core = packageDir("core", "core", dependencies = listOf("requests"))
        writeDestdir(core, "core/__init__.py")

        val result = bundler().bundle(BundleRequest(packageDir = core, target = "macos"))

        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(message.contains("requests"), message)
    }

    @Test
    fun bundle_failsWhenWorkspaceDependencyHasNoCompiledOutput() {
        workspacePyproject(listOf("core", "libs/helper"))
        packageDir("libs/helper", "helper")
        // No dist/ written for helper -- it was never built.

        val core = packageDir("core", "core", dependencies = listOf("helper"))
        writeDestdir(core, "core/__init__.py")

        val result = bundler().bundle(BundleRequest(packageDir = core, target = "macos"))

        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(message.contains("helper"), message)
    }

    @Test
    fun bundle_vendorsThirdPartyDependencyInstalledUnderCrossenvTargetDirectory() {
        val core = packageDir("core", "core", dependencies = listOf("requests"))
        writeDestdir(core, "core/__init__.py")
        writeCrossenvDistInfo(
            core,
            target = "aarch64-apple-darwin",
            distInfoName = "requests-2.34.2",
            packageFiles = mapOf("requests/__init__.py" to "REQUESTS = 1\n"),
        )

        val result = bundler().bundle(BundleRequest(packageDir = core, target = "macos")).getOrThrow()
        val entries = zipEntryNames(result.artifactFile!!)

        assertTrue(entries.contains("core/__init__.py"), entries.toString())
        assertTrue(entries.contains("requests/__init__.py"), entries.toString())
        assertFalse(entries.any { it.startsWith("requests-2.34.2.dist-info/") }, entries.toString())
    }

    @Test
    fun bundle_vendorsTransitiveThirdPartyDependenciesFoundInCrossenvDirectory() {
        // "requests" is the only declared dependency, but the crossenv directory also holds
        // urllib3 (a transitive dependency `uv pip install` resolved and installed alongside it).
        // A "fat" bundle needs the whole closure, not just the directly-declared names.
        val core = packageDir("core", "core", dependencies = listOf("requests"))
        writeDestdir(core, "core/__init__.py")
        writeCrossenvDistInfo(core, "aarch64-apple-darwin", "requests-2.34.2", mapOf("requests/__init__.py" to "x"))
        writeCrossenvDistInfo(core, "aarch64-apple-darwin", "urllib3-2.7.0", mapOf("urllib3/__init__.py" to "y"))

        val result = bundler().bundle(BundleRequest(packageDir = core, target = "macos")).getOrThrow()
        val entries = zipEntryNames(result.artifactFile!!)

        assertTrue(entries.contains("urllib3/__init__.py"), entries.toString())
    }

    @Test
    fun bundle_failsNamingAThirdPartyDependencyMissingFromAnExistingCrossenvDirectory() {
        // The crossenv directory exists for this target (sync ran) but never installed "requests"
        // into it -- e.g. sync ran for a different target, or the dependency was added after the
        // last sync. This must still fail loudly rather than silently omit it.
        val core = packageDir("core", "core", dependencies = listOf("requests"))
        writeDestdir(core, "core/__init__.py")
        File(core, "build/crossenv/aarch64-apple-darwin").mkdirs()

        val result = bundler().bundle(BundleRequest(packageDir = core, target = "macos"))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("requests"))
    }

    @Test
    fun bundle_rejectsBuildLevelsThatAreNotImplementedYet() {
        val pkg = packageDir("core", "core")
        writeDestdir(pkg, "core/__init__.py")

        listOf("bytecode", "native", "mixed").forEach { level ->
            val result = bundler().bundle(BundleRequest(packageDir = pkg, target = "macos", buildLevel = level))
            assertTrue(result.isFailure, "build level '$level' should not silently succeed")
        }
    }

    @Test
    fun bundle_isDeterministicAcrossRuns() {
        workspacePyproject(listOf("core", "libs/helper"))
        val helper = packageDir("libs/helper", "helper")
        writeDestdir(helper, "helper/__init__.py")
        val core = packageDir("core", "core", dependencies = listOf("helper"))
        writeDestdir(core, "core/__init__.py")

        val first = bundler().bundle(BundleRequest(packageDir = core, target = "macos", overwrite = true)).getOrThrow()
        val firstBytes = first.artifactFile!!.readBytes()
        val second = bundler().bundle(BundleRequest(packageDir = core, target = "macos", overwrite = true)).getOrThrow()
        val secondBytes = second.artifactFile!!.readBytes()

        assertTrue(firstBytes.contentEquals(secondBytes), "fat wheel bytes must be reproducible across runs")
    }

    @Test
    fun bundle_wheelContainsMetadataWheelAndRecordForMergedPayload() {
        workspacePyproject(listOf("core", "libs/helper"))
        val helper = packageDir("libs/helper", "helper")
        writeDestdir(helper, "helper/__init__.py")
        val core = packageDir("core", "core", version = "0.1.0", dependencies = listOf("helper"))
        writeDestdir(core, "core/__init__.py")

        val result = bundler().bundle(BundleRequest(packageDir = core, target = "macos")).getOrThrow()
        val entries = zipEntryNames(result.artifactFile!!)

        assertTrue(entries.contains("core-0.1.0.dist-info/METADATA"), entries.toString())
        assertTrue(entries.contains("core-0.1.0.dist-info/WHEEL"), entries.toString())
        assertTrue(entries.contains("core-0.1.0.dist-info/RECORD"), entries.toString())
        // Only one dist-info: dependencies are vendored as plain site-packages content, not as
        // independently-installed wheels, so they carry no dist-info of their own.
        assertFalse(entries.any { it.endsWith(".dist-info/METADATA") && !it.startsWith("core-0.1.0") }, entries.toString())
    }

    @Test
    fun bundle_manifestIsWrittenToFilesystemAndMatchesArchiveContent() {
        workspacePyproject(listOf("core", "libs/helper"))
        val helper = packageDir("libs/helper", "helper")
        writeDestdir(helper, "helper/__init__.py")
        val core = packageDir("core", "core", version = "0.1.0", dependencies = listOf("helper"))
        writeDestdir(core, "core/__init__.py")

        val result = bundler().bundle(BundleRequest(packageDir = core, target = "macos")).getOrThrow()
        val manifestFile = result.manifestFile

        assertTrue(manifestFile.isFile, "manifest not written: $manifestFile")

        val entries = zipEntryNames(result.artifactFile!!)
        val recordEntry = entries.firstOrNull { it.endsWith(".dist-info/RECORD") }
        assertNotNull(recordEntry, "RECORD not found in archive")

        val archiveContent = zipEntryText(result.artifactFile!!, recordEntry!!)
        assertEquals(archiveContent, manifestFile.readText())
    }

    @Test
    fun bundle_rejectsUnknownTarget() {
        val pkg = packageDir("core", "core")
        writeDestdir(pkg, "core/__init__.py")

        val result = bundler().bundle(BundleRequest(packageDir = pkg, target = "definitely-not-a-target"))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("definitely-not-a-target"))
    }
}
