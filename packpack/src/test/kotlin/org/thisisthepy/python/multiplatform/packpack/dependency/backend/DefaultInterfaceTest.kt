package org.thisisthepy.python.multiplatform.packpack.dependency.backend

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class DefaultInterfaceTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun listPython_returnsInstalledVersionsInOrder() {
        File(tempDir, "3.12").mkdirs()
        File(tempDir, "3.11").mkdirs()
        File(tempDir, "README.txt").writeText("ignore")
        val backend = TestDefaultInterface(tempDir)

        val result = backend.listPython()

        assertTrue(result.isSuccess)
        assertEquals("3.11${System.lineSeparator()}3.12", result.getOrThrow())
    }

    @Test
    fun findPython_returnsInstalledVersionPath() {
        val pythonDir = File(tempDir, "3.12")
        pythonDir.mkdirs()
        val backend = TestDefaultInterface(tempDir)

        val result = backend.findPython("3.12")

        assertTrue(result.isSuccess)
        assertEquals(pythonDir.absolutePath, result.getOrThrow())
    }

    @Test
    fun findPython_failsWhenVersionIsMissing() {
        val backend = TestDefaultInterface(tempDir)

        val result = backend.findPython("3.12")

        assertTrue(result.isFailure)
        assertEquals("Python 3.12 is not installed", result.exceptionOrNull()?.message)
    }

    @Test
    fun findPython_rejectsPathLikeVersion() {
        val backend = TestDefaultInterface(tempDir)

        val result = backend.findPython("../3.12")

        assertTrue(result.isFailure)
        assertEquals("Python version cannot contain path separators", result.exceptionOrNull()?.message)
    }

    @Test
    fun installPython_returnsFailureForUnsupportedPythonVersion() =
        runBlocking {
            val backend = TestDefaultInterface(tempDir)

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
            val backend = TestDefaultInterface(tempDir)

            val result = backend.installPython("3.13", "unknown-target")

            assertTrue(result.isFailure)
            assertEquals("Unsupported target platform: unknown-target", result.exceptionOrNull()?.message)
        }

    @Test
    fun uninstallPython_removesInstalledVersion() {
        val pythonDir = File(tempDir, "3.12")
        File(pythonDir, "bin").mkdirs()
        File(pythonDir, "bin/python").writeText("")
        val backend = TestDefaultInterface(tempDir)

        val result = backend.uninstallPython("3.12")

        assertTrue(result.isSuccess)
        assertFalse(pythonDir.exists())
    }

    private class TestDefaultInterface(
        private val root: File,
    ) : DefaultInterface() {
        override fun pythonInstallRoot(): File = root

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

        override suspend fun showDependencyTree(
            packageName: String?,
            extraArgs: Map<String, String>?,
            workingDir: File?,
        ): Result<String> = Result.success("ok")

        override suspend fun lockDependencies(projectRoot: String): Result<String> = Result.success("ok")
    }
}
