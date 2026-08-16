package org.thisisthepy.python.multiplatform.packpack.dependency.backend

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class UVBackendTest {
    @Test
    fun createVirtualEnvironment_buildsVenvCommandWithoutPythonFlag() {
        val backend = RecordingUVBackend()

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
        val backend = RecordingUVBackend()

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
        val backend = RecordingUVBackend()

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

    @Test
    fun installDependenciesToTarget_buildsPipInstallCommandFromPyprojectToml() {
        val backend = RecordingUVBackend()

        val result =
            runBlocking {
                backend.installDependenciesToTarget(
                    targetDir = "build/crossenv/x86_64-pc-windows-msvc",
                    pythonPlatform = "x86_64-pc-windows-msvc",
                    workingDir = File("/tmp/project/core"),
                )
            }

        assertTrue(result.isSuccess)
        assertEquals(
            listOf(
                "pip",
                "install",
                "-r",
                "pyproject.toml",
                "--target",
                "build/crossenv/x86_64-pc-windows-msvc",
                "--python-platform",
                "x86_64-pc-windows-msvc",
            ),
            backend.lastCommand,
        )
        assertEquals(File("/tmp/project/core").absolutePath, backend.lastWorkingDir?.absolutePath)
    }

    @Test
    fun installDependenciesToTarget_passesExtraArgsAsUvOptions() {
        val backend = RecordingUVBackend()

        val result =
            runBlocking {
                backend.installDependenciesToTarget(
                    targetDir = "build/crossenv/win",
                    pythonPlatform = "windows",
                    extraArgs = mapOf("python-version" to "3.13"),
                )
            }

        assertTrue(result.isSuccess)
        assertEquals(
            listOf(
                "pip",
                "install",
                "-r",
                "pyproject.toml",
                "--target",
                "build/crossenv/win",
                "--python-platform",
                "windows",
                "--python-version",
                "3.13",
            ),
            backend.lastCommand,
        )
    }

    private class RecordingUVBackend : UVBackend() {
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
