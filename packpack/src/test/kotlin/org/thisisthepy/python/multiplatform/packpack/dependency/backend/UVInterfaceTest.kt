package org.thisisthepy.python.multiplatform.packpack.dependency.backend

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class UVInterfaceTest {
    @Test
    fun createVirtualEnvironment_buildsVenvCommandWithoutPythonFlag() {
        val backend = RecordingUVInterface()

        val result =
            runBlocking {
                backend.createVirtualEnvironment(
                    path = ".venv-test",
                    pythonVersion = null,
                    extraArgs = mapOf("__working_dir" to "/tmp/project"),
                )
            }

        assertTrue(result.isSuccess)
        assertEquals(listOf("venv", ".venv-test"), backend.lastCommand)
        assertEquals(File("/tmp/project").absolutePath, backend.lastWorkingDir?.absolutePath)
    }

    @Test
    fun createVirtualEnvironment_includesPythonFlagWhenProvided() {
        val backend = RecordingUVInterface()

        val result =
            runBlocking {
                backend.createVirtualEnvironment(
                    path = ".venv-py",
                    pythonVersion = "3.12",
                    extraArgs = mapOf("__working_dir" to "/tmp/project"),
                )
            }

        assertTrue(result.isSuccess)
        assertEquals(listOf("venv", "--python", "3.12", ".venv-py"), backend.lastCommand)
    }

    @Test
    fun createVirtualEnvironment_failsForUnsupportedOptions() {
        val backend = RecordingUVInterface()

        val result =
            runBlocking {
                backend.createVirtualEnvironment(
                    path = ".venv-test",
                    pythonVersion = null,
                    extraArgs = mapOf("bad-option" to "1"),
                )
            }

        assertFalse(result.isSuccess)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(message.contains("Unsupported uv options"))
    }

    private class RecordingUVInterface : UVInterface() {
        var lastCommand: List<String> = emptyList()
        var lastWorkingDir: File? = null

        override suspend fun executeCommand(
            command: List<String>,
            workingDir: File?,
        ): Result<String> {
            lastCommand = command
            lastWorkingDir = workingDir
            return Result.success("ok")
        }
    }
}
