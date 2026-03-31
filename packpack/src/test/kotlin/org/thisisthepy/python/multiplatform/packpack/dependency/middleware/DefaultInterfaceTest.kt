package org.thisisthepy.python.multiplatform.packpack.dependency.middleware

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.thisisthepy.python.multiplatform.packpack.dependency.backend.BaseInterface

class DefaultInterfaceTest {
    @Test
    fun initProject_createsMinimalScaffoldFiles() {
        withTempDir { projectDir ->
            val backend = RecordingBackend()
            val middleware = DefaultInterface { backend }

            val result =
                middleware.initProject(
                    path = projectDir.absolutePath,
                    targets = null,
                    extraArgs = mapOf("name" to "demo-workspace", "python" to "3.12"),
                )

            assertTrue(result.isSuccess)
            assertTrue(File(projectDir, "pyproject.toml").exists())
            assertTrue(File(projectDir, ".gitignore").exists())
            assertTrue(File(projectDir, "README.md").exists())
            assertTrue(File(projectDir, ".python-version").exists())
            assertFalse(File(projectDir, ".venv").exists())
            assertFalse(File(projectDir, ".ppp").exists())
            assertFalse(File(projectDir, "packages").exists())
            assertEquals("3.12\n", File(projectDir, ".python-version").readText())
            assertEquals("# demo-workspace\n", File(projectDir, "README.md").readText())
        }
    }

    @Test
    fun initProject_usesBareUvBootstrap() {
        withTempDir { projectDir ->
            val backend = RecordingBackend()
            val middleware = DefaultInterface { backend }

            val result =
                middleware.initProject(
                    path = projectDir.absolutePath,
                    targets = null,
                    extraArgs = mapOf("python" to "3.11"),
                )

            assertTrue(result.isSuccess)
            assertEquals(projectDir.absolutePath, backend.lastInitPath)
            assertEquals("", backend.lastInitExtraArgs["bare"])
            assertEquals("3.11", backend.lastInitExtraArgs["python"])
            assertFalse("no-workspace" in backend.lastInitExtraArgs)
        }
    }

    private fun withTempDir(block: (File) -> Unit) {
        val root = File("/workspace/ppp_gradle_test")
        root.mkdirs()
        val dir = Files.createTempDirectory(root.toPath(), "default-interface-test").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    private class RecordingBackend : BaseInterface {
        var lastInitPath: String? = null
        var lastInitExtraArgs: Map<String, String> = emptyMap()

        override fun initialize() = Unit

        override suspend fun getVersion(): Result<String> = Result.success("uv 0.0.0")

        override suspend fun isToolInstalled(): Boolean = true

        override suspend fun installTool(): Result<String> = Result.success("ok")

        override suspend fun createVirtualEnvironment(
            path: String,
            pythonVersion: String?,
            extraArgs: Map<String, String>?,
        ): Result<String> = Result.success("ok")

        override suspend fun initProject(
            path: String?,
            extraArgs: Map<String, String>?,
        ): Result<String> {
            lastInitPath = path
            lastInitExtraArgs = extraArgs ?: emptyMap()

            val targetDir = path?.let(::File) ?: File(System.getProperty("user.dir"))
            targetDir.mkdirs()
            File(targetDir, "pyproject.toml").writeText(
                """
                [project]
                name = "demo"
                version = "0.1.0"
                dependencies = []
                """.trimIndent() + "\n",
            )
            return Result.success("ok")
        }

        override suspend fun addDependencies(
            packageName: String?,
            dependencies: List<String>,
            extraArgs: Map<String, String>?,
        ): Result<String> = Result.success("ok")

        override suspend fun removeDependencies(
            packageName: String?,
            dependencies: List<String>,
            extraArgs: Map<String, String>?,
        ): Result<String> = Result.success("ok")

        override suspend fun syncDependencies(
            venvPath: String,
            extraArgs: Map<String, String>?,
        ): Result<String> = Result.success("ok")

        override suspend fun showDependencyTree(
            packageName: String?,
            extraArgs: Map<String, String>?,
        ): Result<String> = Result.success("ok")

        override suspend fun lockDependencies(projectRoot: String): Result<String> = Result.success("ok")

        override suspend fun listPython(): Result<String> = Result.success("ok")

        override suspend fun findPython(pythonVersion: String): Result<String> = Result.success("ok")

        override suspend fun installPython(pythonVersion: String): Result<String> = Result.success("ok")

        override suspend fun uninstallPython(pythonVersion: String): Result<String> = Result.success("ok")
    }
}
