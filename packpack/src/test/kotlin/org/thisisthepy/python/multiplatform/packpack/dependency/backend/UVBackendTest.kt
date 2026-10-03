package org.thisisthepy.python.multiplatform.packpack.dependency.backend

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test
    fun installDependenciesToTarget_withRequirements_passesThemPositionallyWithoutDashR() {
        val backend = RecordingUVBackend()
        val specs = listOf("six==1.17.0", "requests[socks]>=2", "tomli>=2; python_version < '3.11'")

        val result =
            runBlocking {
                backend.installDependenciesToTarget(
                    targetDir = "build/crossenv/android",
                    pythonPlatform = "aarch64-linux-android",
                    workingDir = File("/tmp/project/core"),
                    requirements = specs,
                )
            }

        assertTrue(result.isSuccess)
        assertEquals(
            listOf("pip", "install") + specs +
                listOf("--target", "build/crossenv/android", "--python-platform", "aarch64-linux-android"),
            backend.lastCommand,
        )
        assertFalse("-r" in backend.lastCommand)
        assertFalse("pyproject.toml" in backend.lastCommand)
    }

    @Test
    fun installDependenciesToTarget_nullRequirementsKeepsDashRPyproject() {
        val backend = RecordingUVBackend()
        runBlocking { backend.installDependenciesToTarget("t", "windows", requirements = null) }
        assertEquals(
            listOf("pip", "install", "-r", "pyproject.toml", "--target", "t", "--python-platform", "windows"),
            backend.lastCommand,
        )
    }

    @Test
    fun installDependenciesToTarget_emptyRequirementsIsSuccessfulNoOp() {
        val backend = RecordingUVBackend()
        val result = runBlocking { backend.installDependenciesToTarget("t", "windows", requirements = emptyList()) }
        assertTrue(result.isSuccess)
        assertEquals(emptyList(), backend.lastCommand)
    }

    @Test
    fun installDependenciesToTarget_rejectsOptionLikeRequirement() {
        val backend = RecordingUVBackend()
        val result = runBlocking { backend.installDependenciesToTarget("t", "windows", requirements = listOf("--index-url=x")) }
        assertTrue(result.isFailure)
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
