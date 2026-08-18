package org.thisisthepy.python.multiplatform.packpack.dependency.backend

import kotlinx.coroutines.runBlocking
import org.thisisthepy.python.multiplatform.packpack.utils.extractArchive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun installPython_returnsFailureForUnsupportedPythonVersion() =
        runBlocking {
            val backend = TestDefaultBackend(tempDir)

            val result = backend.installPython("3.12", "macos")

            assertTrue(result.isFailure)
            assertEquals(
                "Only Python 3.13 is supported due to python-mutliplatform limitations",
                result.exceptionOrNull()?.message,
            )
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

    private class TestDefaultBackend(
        private val root: File,
    ) : DefaultBackend() {
        override fun pythonInstallRoot(): File = root

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
