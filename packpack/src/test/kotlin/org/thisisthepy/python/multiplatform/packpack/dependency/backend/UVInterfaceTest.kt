package org.thisisthepy.python.multiplatform.packpack.dependency.backend

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
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
                    workingDir = File("/tmp/project"),
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
                    workingDir = File("/tmp/project"),
                )
            }

        assertTrue(result.isSuccess)
        assertEquals(listOf("venv", "--python", "3.12", ".venv-py"), backend.lastCommand)
    }

    @Test
    fun createVirtualEnvironment_passesExtraArgsAsUvOptions() {
        val backend = RecordingUVInterface()

        val result =
            runBlocking {
                backend.createVirtualEnvironment(
                    path = ".venv-test",
                    pythonVersion = null,
                    extraArgs = mapOf("bad-option" to "1"),
                )
            }

        assertTrue(result.isSuccess)
        assertEquals(listOf("venv", "--bad-option", "1", ".venv-test"), backend.lastCommand)
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
