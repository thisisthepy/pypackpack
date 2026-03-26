package org.thisisthepy.python.multiplatform.packpack.dependency.middleware.environment

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.thisisthepy.python.multiplatform.packpack.dependency.backend.BaseInterface
import org.thisisthepy.python.multiplatform.packpack.utils.toml.TomlEditor

class CrossEnvTest {
    @Test
    fun addPackage_createsDirectoryAndRegistersWorkspaceMember() {
        withWorkspace(
            """
            [project]
            name = "root"

            [tool.uv.workspace]
            members = []
            """.trimIndent(),
        ) { workspaceRoot ->
            val crossEnv = CrossEnv()
            crossEnv.initialize(FakeBackend())

            val result = crossEnv.addPackage("demo_pkg")

            assertTrue(result.isSuccess)
            assertTrue(File(workspaceRoot, "demo_pkg").exists())

            val rootEditor = TomlEditor(File(workspaceRoot, "pyproject.toml").readText())
            val members = rootEditor.getArray("tool.uv.workspace", "members")
            assertTrue("demo_pkg" in members)
        }
    }

    @Test
    fun removePackage_deletesDirectoryAndRemovesWorkspaceMember() {
        withWorkspace(
            """
            [project]
            name = "root"

            [tool.uv.workspace]
            members = ["demo_pkg"]
            """.trimIndent(),
        ) { workspaceRoot ->
            val packageDir = File(workspaceRoot, "demo_pkg")
            packageDir.mkdirs()
            File(packageDir, "pyproject.toml").writeText("[project]\nname = \"demo_pkg\"\n")

            val crossEnv = CrossEnv()
            crossEnv.initialize(FakeBackend())

            val result = crossEnv.removePackage("demo_pkg")

            assertTrue(result.isSuccess)
            assertFalse(packageDir.exists())

            val rootEditor = TomlEditor(File(workspaceRoot, "pyproject.toml").readText())
            val members = rootEditor.getArray("tool.uv.workspace", "members")
            assertFalse("demo_pkg" in members)
        }
    }

    @Test
    fun addTarget_normalizesAndPersistsCanonicalTargets() {
        withWorkspace(
            """
            [project]
            name = "root"

            [tool.uv.workspace]
            members = ["demo_pkg"]
            """.trimIndent(),
        ) { workspaceRoot ->
            val packageDir = File(workspaceRoot, "demo_pkg")
            packageDir.mkdirs()
            File(packageDir, "pyproject.toml").writeText("[project]\nname = \"demo_pkg\"\n")

            val crossEnv = CrossEnv()
            crossEnv.initialize(FakeBackend())

            val result = crossEnv.addTarget("demo_pkg", listOf("linux", "windows"))

            assertTrue(result.isSuccess)
            val packageEditor = TomlEditor(File(packageDir, "pyproject.toml").readText())
            val platforms = packageEditor.getArray("tool.ppp.dependencies", "platforms")
            assertEquals(
                listOf("x86_64-pc-windows-msvc", "x86_64-unknown-linux-gnu"),
                platforms,
            )
        }
    }

    @Test
    fun removeTarget_removesNormalizedTargetEntry() {
        withWorkspace(
            """
            [project]
            name = "root"

            [tool.uv.workspace]
            members = ["demo_pkg"]
            """.trimIndent(),
        ) { workspaceRoot ->
            val packageDir = File(workspaceRoot, "demo_pkg")
            packageDir.mkdirs()
            File(packageDir, "pyproject.toml").writeText(
                """
                [project]
                name = "demo_pkg"

                [tool.ppp.dependencies]
                platforms = ["x86_64-pc-windows-msvc", "x86_64-unknown-linux-gnu"]
                """.trimIndent(),
            )

            val crossEnv = CrossEnv()
            crossEnv.initialize(FakeBackend())

            val result = crossEnv.removeTarget("demo_pkg", listOf("linux"))

            assertTrue(result.isSuccess)
            val packageEditor = TomlEditor(File(packageDir, "pyproject.toml").readText())
            val platforms = packageEditor.getArray("tool.ppp.dependencies", "platforms")
            assertEquals(listOf("x86_64-pc-windows-msvc"), platforms)
        }
    }

    @Test
    fun removePackage_failsWhenPackageMissing() {
        withWorkspace(
            """
            [project]
            name = "root"

            [tool.uv.workspace]
            members = []
            """.trimIndent(),
        ) {
            val crossEnv = CrossEnv()
            crossEnv.initialize(FakeBackend())

            val result = crossEnv.removePackage("missing_pkg")

            assertFalse(result.isSuccess)
        }
    }

    private fun withWorkspace(
        pyprojectContent: String,
        block: (File) -> Unit,
    ) {
        val tempRoot = Files.createTempDirectory("crossenv-test").toFile()
        val oldUserDir = System.getProperty("user.dir")
        try {
            File(tempRoot, "pyproject.toml").writeText(pyprojectContent)
            System.setProperty("user.dir", tempRoot.absolutePath)
            block(tempRoot)
        } finally {
            System.setProperty("user.dir", oldUserDir)
            tempRoot.deleteRecursively()
        }
    }

    private class FakeBackend : BaseInterface {
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
            val workingDir = extraArgs?.get("__working_dir")
            val packageDir =
                when {
                    !path.isNullOrBlank() && !workingDir.isNullOrBlank() -> File(workingDir, path)
                    !path.isNullOrBlank() -> File(path)
                    !workingDir.isNullOrBlank() -> File(workingDir)
                    else -> return Result.failure(IllegalArgumentException("path or working dir required"))
                }
            packageDir.mkdirs()
            File(packageDir, "pyproject.toml").writeText("[project]\nname = \"${packageDir.name}\"\n")

            val workspaceRoot = packageDir.parentFile ?: return Result.success("ok")
            val rootPyproject = File(workspaceRoot, "pyproject.toml")
            val rootEditor = TomlEditor(rootPyproject.readText())
            val tablePath = "tool.uv.workspace"
            if (!rootEditor.hasTable(tablePath)) {
                rootEditor.createTable(tablePath)
            }
            val relativePath = workspaceRoot.toPath().relativize(packageDir.toPath()).toString().replace('\\', '/')
            rootEditor.addToArray(tablePath = tablePath, key = "members", relativePath)
            rootPyproject.writeText(rootEditor.toTomlString())

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
