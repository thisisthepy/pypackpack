package org.thisisthepy.python.multiplatform.packpack.dependency.backend

import kotlinx.coroutines.runBlocking
import org.thisisthepy.python.multiplatform.packpack.utils.ChecksumMismatchException
import org.thisisthepy.python.multiplatform.packpack.utils.extractArchive
import org.thisisthepy.python.multiplatform.packpack.utils.sha256Hex
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class DefaultBackendTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun extractArchive_stripsTwoComponentsForPythonInstall() {
        val archive = File(tempDir, "cpython.tar.gz")
        java.util.zip.GZIPOutputStream(archive.outputStream()).use { gzip ->
            gzip.write(tarHeader("python/", 0, '5'))
            gzip.write(tarHeader("python/build/", 0, '5'))
            gzip.write(tarHeader("python/build/Modules/", 0, '5'))
            gzip.write(tarFile("python/build/Modules/x.o", "x".toByteArray()))
            gzip.write(tarHeader("python/licenses/", 0, '5'))
            gzip.write(tarFile("python/licenses/LICENSE", "LICENSE".toByteArray()))
            gzip.write(tarFile("python/PYTHON.json", "{}".toByteArray()))
            gzip.write(tarHeader("python/install/", 0, '5'))
            gzip.write(tarHeader("python/install/bin/", 0, '5'))
            gzip.write(tarFile("python/install/bin/python", "python-binary".toByteArray()))
            gzip.write(ByteArray(1024))
        }
        val installDir = File(tempDir, "python-install")

        extractArchive(archive, installDir, stripComponents = 2, prefixFilter = "python/install/")

        val pythonExecutable = File(installDir, "bin/python")
        assertTrue(pythonExecutable.exists(), "Executable should exist directly under installDir/bin/python")
        assertEquals("python-binary", pythonExecutable.readText())

        assertFalse(File(installDir, "Modules/x.o").exists(), "Build artifacts should not be installed")
        assertFalse(File(installDir, "LICENSE").exists(), "Licenses should not be installed outside install prefix")
    }

    @Test
    fun listPython_returnsInstalledVersionsInOrder() {
        File(tempDir, "3.12").mkdirs()
        File(tempDir, "3.11").mkdirs()
        File(tempDir, "README.txt").writeText("ignore")
        val backend = TestDefaultBackend(tempDir)

        val result = backend.listPython()

        assertTrue(result.isSuccess)
        assertEquals("3.11${System.lineSeparator()}3.12", result.getOrThrow())
    }

    @Test
    fun findPython_returnsInstalledVersionPath() {
        val pythonDir = File(tempDir, "3.12")
        pythonDir.mkdirs()
        val backend = TestDefaultBackend(tempDir)

        val result = backend.findPython("3.12")

        assertTrue(result.isSuccess)
        assertEquals(pythonDir.absolutePath, result.getOrThrow())
    }

    @Test
    fun findPython_failsWhenVersionIsMissing() {
        val backend = TestDefaultBackend(tempDir)

        val result = backend.findPython("3.12")

        assertTrue(result.isFailure)
        assertEquals("Python 3.12 is not installed", result.exceptionOrNull()?.message)
    }

    @Test
    fun findPython_rejectsPathLikeVersion() {
        val backend = TestDefaultBackend(tempDir)

        val result = backend.findPython("../3.12")

        assertTrue(result.isFailure)
        assertEquals("Python version cannot contain path separators", result.exceptionOrNull()?.message)
    }

    @Test
    fun installPython_refusesAnUnsupportedVersionWithTheSupportedList() =
        runBlocking {
            val backend = TestDefaultBackend(tempDir)

            val result = backend.installPython("3.12", "macos")

            assertTrue(result.isFailure)
            val message = result.exceptionOrNull()?.message.orEmpty()
            assertTrue(message.startsWith("Python 3.12 is not available for aarch64-apple-darwin."), message)
            assertTrue("3.14.7/aarch64-apple-darwin" in message, message)
            assertTrue("3.13.0/x86_64-unknown-linux-gnu" in message, message)
        }

    @Test
    fun installPython_returnsFailureForUnsupportedTargetPlatform() =
        runBlocking {
            val backend = TestDefaultBackend(tempDir)

            val result = backend.installPython("3.13", "unknown-target")

            assertTrue(result.isFailure)
            assertEquals("Unsupported target platform: unknown-target", result.exceptionOrNull()?.message)
        }

    @Test
    fun installPython_refusesAPairWithNoPinnedDigestWithoutDownloading() =
        runBlocking {
            val backend = TestDefaultBackend(tempDir, projectRoot = File(tempDir, "project"))

            val result = backend.installPython("3.13.0", "aarch64-linux-android")

            assertTrue(result.isFailure)
            val message = result.exceptionOrNull()?.message.orEmpty()
            assertTrue("no pinned SHA-256" in message, message)
            assertTrue("aarch64-linux-android.tar.xz" in message, message)
            assertEquals(0, backend.downloads, "an unpinned archive must not even be downloaded")
            assertFalse(File(tempDir, "project").exists())
        }

    @Test
    fun installPython_matchingDigestExtractsIntoTheCrossTargetDirectory() =
        runBlocking {
            val archive = fakePbsArchive(File(tempDir, "fake-python.tar.gz"))
            val project = File(tempDir, "project")
            val backend =
                TestDefaultBackend(
                    File(tempDir, "registry"),
                    projectRoot = project,
                    fakeArchive = archive,
                    pinnedSha256 = sha256Hex(archive),
                )

            val result = backend.installPython("3.14.7", "x86_64-linux-android")

            assertTrue(result.isSuccess, result.exceptionOrNull()?.toString())
            val installDir = File(project, "android_x86_64")
            assertEquals("python-binary", File(installDir, "bin/python3.14").readText())
            assertFalse(File(installDir, "PYTHON.json").exists(), "only python/ is installed")
            assertEquals(listOf("android_x86_64"), project.list()!!.toList(), "no staging or archive is left behind")
            assertEquals(installDir.absolutePath, backend.findPython("3.14.7").getOrThrow())
            assertEquals("https://example.invalid/3.14.7/x86_64-linux-android/fake-python.tar.gz", backend.lastUrl)
        }

    @Test
    fun installPython_hostTargetGoesToTheProjectVenv() =
        runBlocking {
            val archive = fakePbsArchive(File(tempDir, "fake-python.tar.gz"))
            val project = File(tempDir, "project")
            val backend =
                TestDefaultBackend(
                    File(tempDir, "registry"),
                    projectRoot = project,
                    fakeArchive = archive,
                    pinnedSha256 = sha256Hex(archive).uppercase(),
                )

            val result = backend.installPython("3.14.7", null)

            assertTrue(result.isSuccess, result.exceptionOrNull()?.toString())
            assertEquals("python-binary", File(project, ".venv/bin/python3.14").readText())
            assertEquals(listOf(".venv"), project.list()!!.toList())
        }

    @Test
    fun installPython_mismatchedDigestRefusesAndLeavesNothingBehind() =
        runBlocking {
            val archive = fakePbsArchive(File(tempDir, "fake-python.tar.gz"))
            val project = File(tempDir, "project")
            val wrong = "0".repeat(64)
            val backend =
                TestDefaultBackend(
                    File(tempDir, "registry"),
                    projectRoot = project,
                    fakeArchive = archive,
                    pinnedSha256 = wrong,
                )

            val result = backend.installPython("3.14.7", "aarch64-linux-android")

            assertTrue(result.isFailure)
            val error = assertIs<ChecksumMismatchException>(result.exceptionOrNull())
            assertEquals("fake-python.tar.gz", error.artifact)
            assertEquals(wrong, error.expected)
            assertEquals(sha256Hex(archive), error.actual)
            val message = error.message.orEmpty()
            assertTrue("fake-python.tar.gz" in message && wrong in message && sha256Hex(archive) in message, message)
            assertEquals(1, backend.downloads)
            assertTrue(project.list().isNullOrEmpty(), "neither the archive nor a partial tree may remain: ${project.list()?.toList()}")
            assertTrue(backend.findPython("3.14.7").isFailure, "a refused install must not be registered")
        }

    @Test
    fun installPython_mismatchedDigestLeavesAnExistingInstallUntouched() =
        runBlocking {
            val archive = fakePbsArchive(File(tempDir, "fake-python.tar.gz"))
            val project = File(tempDir, "project")
            val existing = File(project, ".venv/pyvenv.cfg")
            existing.parentFile.mkdirs()
            existing.writeText("home = /old")
            val backend =
                TestDefaultBackend(
                    File(tempDir, "registry"),
                    projectRoot = project,
                    fakeArchive = archive,
                    pinnedSha256 = "f".repeat(64),
                )

            val result = backend.installPython("3.14.7", null)

            assertTrue(result.isFailure)
            assertEquals(listOf("pyvenv.cfg"), File(project, ".venv").list()!!.toList())
            assertEquals("home = /old", existing.readText())
            assertEquals(listOf(".venv"), project.list()!!.toList())
        }

    @Test
    fun installPython_archiveWithAnUnexpectedLayoutIsRefused() =
        runBlocking {
            val archive = File(tempDir, "other.tar.gz")
            java.util.zip.GZIPOutputStream(archive.outputStream()).use { gzip ->
                gzip.write(tarFile("elsewhere/bin/python", "x".toByteArray()))
                gzip.write(ByteArray(1024))
            }
            val project = File(tempDir, "project")
            val backend =
                TestDefaultBackend(
                    File(tempDir, "registry"),
                    projectRoot = project,
                    fakeArchive = archive,
                    pinnedSha256 = sha256Hex(archive),
                )

            val result = backend.installPython("3.14.7", "aarch64-linux-android")

            assertTrue(result.isFailure)
            assertTrue("extracted nothing" in result.exceptionOrNull()?.message.orEmpty())
            assertTrue(project.list().isNullOrEmpty())
        }

    // An explicit installDir (issue #37): toolchain installs into build/pythonRuntime/<triple>/<version>/
    // from a Gradle daemon, so neither projectRoot() (user.dir) nor the global registry may be used.

    @Test
    fun installPython_explicitInstallDirReceivesTheTreeAndNothingElse() =
        runBlocking {
            val archive = fakePbsArchive(File(tempDir, "fake-python.tar.gz"))
            val registry = File(tempDir, "registry")
            val project = File(tempDir, "project")
            val runtime = File(tempDir, "build/pythonRuntime/aarch64-linux-android")
            val installDir = File(runtime, "3.14.7")
            val backend =
                TestDefaultBackend(registry, projectRoot = project, fakeArchive = archive, pinnedSha256 = sha256Hex(archive))

            val result = backend.installPython("3.14.7", "aarch64-linux-android", installDir)

            assertTrue(result.isSuccess, result.exceptionOrNull()?.toString())
            assertEquals("python-binary", File(installDir, "bin/python3.14").readText())
            assertFalse(File(installDir, "PYTHON.json").exists(), "only python/ is installed")
            assertEquals(listOf("3.14.7"), runtime.list()!!.toList(), "no staging or archive is left behind")
            assertEquals(0, backend.projectRootCalls, "an explicit installDir must not locate the project")
            assertFalse(project.exists(), "nothing may be created under projectRoot()")
            assertFalse(registry.exists(), "the registry must not be touched")
            assertTrue(backend.findPython("3.14.7").isFailure, "an explicit install is not registered")
            assertEquals(1, backend.downloads)
        }

    @Test
    fun installPython_explicitInstallDirReplacesAPreviousInstallForTheHostToo() =
        runBlocking {
            val archive = fakePbsArchive(File(tempDir, "fake-python.tar.gz"))
            val project = File(tempDir, "project")
            val installDir = File(tempDir, "runtime/host")
            File(installDir, "stale.txt").apply { parentFile.mkdirs() }.writeText("old")
            val backend =
                TestDefaultBackend(
                    File(tempDir, "registry"),
                    projectRoot = project,
                    fakeArchive = archive,
                    pinnedSha256 = sha256Hex(archive),
                )

            val result = backend.installPython("3.14.7", null, installDir)

            assertTrue(result.isSuccess, result.exceptionOrNull()?.toString())
            assertEquals(listOf("bin"), installDir.list()!!.toList(), "the previous tree is replaced, not merged")
            assertEquals("python-binary", File(installDir, "bin/python3.14").readText())
            assertEquals(listOf("host"), File(tempDir, "runtime").list()!!.toList())
            assertFalse(project.exists(), "the host target must not fall back to <project>/.venv")
            assertEquals(0, backend.projectRootCalls)
        }

    @Test
    fun installPython_explicitInstallDirStillRefusesAnUnpinnedPairBeforeDownloading() =
        runBlocking {
            val runtime = File(tempDir, "runtime")
            val installDir = File(runtime, "3.13.0")
            val backend = TestDefaultBackend(File(tempDir, "registry"), projectRoot = File(tempDir, "project"))

            val result = backend.installPython("3.13.0", "aarch64-linux-android", installDir)

            assertTrue(result.isFailure)
            assertTrue("no pinned SHA-256" in result.exceptionOrNull()?.message.orEmpty())
            assertEquals(0, backend.downloads, "an unpinned archive must not even be downloaded")
            assertFalse(runtime.exists(), "a refused pair must not create anything")
            assertFalse(File(tempDir, "project").exists())
        }

    @Test
    fun installPython_explicitInstallDirIsLeftAsItWasOnADigestMismatch() =
        runBlocking {
            val archive = fakePbsArchive(File(tempDir, "fake-python.tar.gz"))
            val runtime = File(tempDir, "runtime")
            val installDir = File(runtime, "3.14.7")
            val existing = File(installDir, "bin/python3.14")
            existing.parentFile.mkdirs()
            existing.writeText("old-binary")
            val registry = File(tempDir, "registry")
            val backend =
                TestDefaultBackend(registry, projectRoot = File(tempDir, "project"), fakeArchive = archive, pinnedSha256 = "0".repeat(64))

            val result = backend.installPython("3.14.7", "aarch64-linux-android", installDir)

            assertIs<ChecksumMismatchException>(result.exceptionOrNull())
            assertEquals(1, backend.downloads)
            assertEquals(listOf("bin"), installDir.list()!!.toList())
            assertEquals(listOf("python3.14"), File(installDir, "bin").list()!!.toList())
            assertEquals("old-binary", existing.readText())
            assertEquals(listOf("3.14.7"), runtime.list()!!.toList(), "the staging directory is removed")
            assertFalse(registry.exists())
            assertFalse(File(tempDir, "project").exists())
        }

    private fun fakePbsArchive(archive: File): File {
        java.util.zip.GZIPOutputStream(archive.outputStream()).use { gzip ->
            gzip.write(tarHeader("python/", 0, '5'))
            gzip.write(tarHeader("python/bin/", 0, '5'))
            gzip.write(tarFile("python/bin/python3.14", "python-binary".toByteArray()))
            gzip.write(tarFile("PYTHON.json", "{}".toByteArray()))
            gzip.write(ByteArray(1024))
        }
        return archive
    }

    @Test
    fun uninstallPython_removesInstalledVersion() {
        val pythonDir = File(tempDir, "3.12")
        File(pythonDir, "bin").mkdirs()
        File(pythonDir, "bin/python").writeText("")
        val backend = TestDefaultBackend(tempDir)

        val result = backend.uninstallPython("3.12")

        assertTrue(result.isSuccess)
        assertFalse(pythonDir.exists())
    }

    // The following tests cover the install<->list/find/uninstall destination-mismatch fix:
    // installPython may place the interpreter outside pythonInstallRoot() (e.g. a project's
    // .venv), so it registers that location in a small registry file that find/list/uninstall
    // now consult before falling back to scanning pythonInstallRoot() directly.

    @Test
    fun findPython_findsVersionRegisteredOutsideInstallRoot() {
        val projectVenv = File(tempDir, "project/.venv")
        projectVenv.mkdirs()
        val registryRoot = File(tempDir, "registry")
        val backend = TestDefaultBackend(registryRoot)
        backend.register("3.13", projectVenv)

        val result = backend.findPython("3.13")

        assertTrue(result.isSuccess)
        assertEquals(projectVenv.absolutePath, result.getOrThrow())
    }

    @Test
    fun listPython_includesVersionsRegisteredOutsideInstallRoot() {
        val projectVenv = File(tempDir, "project/.venv")
        projectVenv.mkdirs()
        val registryRoot = File(tempDir, "registry")
        File(registryRoot, "3.11").mkdirs()
        val backend = TestDefaultBackend(registryRoot)
        backend.register("3.13", projectVenv)

        val result = backend.listPython()

        assertTrue(result.isSuccess)
        assertEquals("3.11${System.lineSeparator()}3.13", result.getOrThrow())
    }

    @Test
    fun uninstallPython_removesVersionRegisteredOutsideInstallRoot() {
        val projectVenv = File(tempDir, "project/.venv")
        projectVenv.mkdirs()
        val registryRoot = File(tempDir, "registry")
        val backend = TestDefaultBackend(registryRoot)
        backend.register("3.13", projectVenv)

        val result = backend.uninstallPython("3.13")

        assertTrue(result.isSuccess)
        assertFalse(projectVenv.exists())
        // A subsequent find must fail cleanly instead of resurrecting a stale registry entry.
        assertTrue(backend.findPython("3.13").isFailure)
    }

    @Test
    fun findPython_fallsBackToInstallRootWhenNotRegistered() {
        // No registry entry exists for this version; a directory placed straight under
        // pythonInstallRoot() (as e.g. manual setup would do) must still be found.
        val pythonDir = File(tempDir, "3.12")
        pythonDir.mkdirs()
        val backend = TestDefaultBackend(tempDir)

        val result = backend.findPython("3.12")

        assertTrue(result.isSuccess)
        assertEquals(pythonDir.absolutePath, result.getOrThrow())
    }

    /**
     * [DefaultBackend] with its seams replaced: no network, a fixed host, and -- when [fakeArchive]
     * is set -- a distribution whose pin is [pinnedSha256] and whose download copies [fakeArchive].
     */
    private class TestDefaultBackend(
        private val root: File,
        private val projectRoot: File = File(root, "project"),
        private val fakeArchive: File? = null,
        private val pinnedSha256: String? = null,
    ) : DefaultBackend() {
        var downloads = 0
        var lastUrl: String? = null
        var projectRootCalls = 0

        override fun pythonInstallRoot(): File = root

        override fun projectRoot(): File {
            projectRootCalls++
            return projectRoot
        }

        override fun hostTarget(): String = "aarch64-apple-darwin"

        override fun resolveDistribution(
            pythonVersion: String,
            canonicalTarget: String,
        ): Result<PythonDistribution> {
            val archive = fakeArchive ?: return super.resolveDistribution(pythonVersion, canonicalTarget)
            return Result.success(
                PythonDistribution(
                    version = pythonVersion,
                    target = canonicalTarget,
                    url = "https://example.invalid/$pythonVersion/$canonicalTarget/${archive.name}",
                    sha256 = pinnedSha256,
                    provenance = "test",
                    stripComponents = 1,
                    prefixFilter = "python/",
                ),
            )
        }

        override suspend fun downloadArchive(
            url: String,
            fileName: String,
            destDir: File,
        ): Result<File> {
            downloads++
            lastUrl = url
            val archive = fakeArchive ?: return Result.failure(IllegalStateException("tests never download"))
            destDir.mkdirs()
            return Result.success(archive.copyTo(File(destDir, fileName), overwrite = true))
        }

        fun register(
            pythonVersion: String,
            installDir: File,
        ) = registerInstalledVersion(pythonVersion, installDir)

        override fun initialize() = Unit

        override suspend fun getVersion(): Result<String> = Result.success("ok")

        override suspend fun isToolInstalled(): Boolean = true

        override suspend fun installTool(): Result<String> = Result.success("ok")

        override suspend fun createVirtualEnvironment(
            path: String,
            pythonVersion: String?,
            extraArgs: Map<String, String>?,
            workingDir: File?,
        ): Result<String> = Result.success("ok")

        override suspend fun initProject(
            path: String?,
            extraArgs: Map<String, String>?,
            workingDir: File?,
        ): Result<String> = Result.success("ok")

        override suspend fun addDependencies(
            packageName: String?,
            dependencies: List<String>,
            extraArgs: Map<String, String>?,
            workingDir: File?,
        ): Result<String> = Result.success("ok")

        override suspend fun removeDependencies(
            packageName: String?,
            dependencies: List<String>,
            extraArgs: Map<String, String>?,
            workingDir: File?,
        ): Result<String> = Result.success("ok")

        override suspend fun syncDependencies(
            venvPath: String,
            extraArgs: Map<String, String>?,
            workingDir: File?,
        ): Result<String> = Result.success("ok")

        override suspend fun installDependenciesToTarget(
            targetDir: String,
            pythonPlatform: String,
            extraArgs: Map<String, String>?,
            workingDir: File?,
            requirements: List<String>?,
        ): Result<String> = Result.success("ok")

        override suspend fun showDependencyTree(
            packageName: String?,
            extraArgs: Map<String, String>?,
            workingDir: File?,
        ): Result<String> = Result.success("ok")

        override suspend fun lockDependencies(projectRoot: String): Result<String> = Result.success("ok")
    }

    private fun tarHeader(
        name: String,
        size: Int,
        typeFlag: Char,
    ): ByteArray {
        val header = ByteArray(512)
        name.toByteArray().copyInto(header, 0)
        "0000777\u0000".toByteArray().copyInto(header, 100)
        "0000000\u0000".toByteArray().copyInto(header, 108)
        "0000000\u0000".toByteArray().copyInto(header, 116)
        size.toString(8).padStart(11, '0').plus('\u0000').toByteArray().copyInto(header, 124)
        "00000000000\u0000".toByteArray().copyInto(header, 136)
        "        ".toByteArray().copyInto(header, 148)
        header[156] = typeFlag.code.toByte()
        "ustar\u000000".toByteArray().copyInto(header, 257)

        val checksum = header.sumOf { it.toUByte().toInt() }
        checksum.toString(8).padStart(6, '0').plus("\u0000 ").toByteArray().copyInto(header, 148)
        return header
    }

    private fun tarFile(
        name: String,
        content: ByteArray,
    ): ByteArray {
        val header = tarHeader(name, content.size, '0')
        val paddingSize = (512 - content.size % 512) % 512
        return header + content + ByteArray(paddingSize)
    }
}
