package org.thisisthepy.python.multiplatform.packpack.dependency.middleware.environment

import kotlinx.coroutines.runBlocking
import org.thisisthepy.python.multiplatform.packpack.dependency.backend.BaseInterface
import org.thisisthepy.python.multiplatform.packpack.utils.toml.TomlEditor
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
            assertEquals("", FakeBackend.lastInitExtraArgs["bare"])
            assertNull(FakeBackend.lastInitExtraArgs["package"])
        }
    }

    @Test
    fun addPackage_createsCommonPackageScaffoldOnly() {
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

            val result = crossEnv.addPackage("demo-pkg")

            assertTrue(result.isSuccess)
            val packageDir = File(workspaceRoot, "demo-pkg")
            assertTrue(File(packageDir, "README.md").exists())
            assertEquals("# demo-pkg\n", File(packageDir, "README.md").readText())
            assertTrue(File(packageDir, "src/main/demo_pkg/__init__.py").exists())
            assertTrue(File(packageDir, "src/test/test_import.py").exists())
            assertTrue(File(packageDir, "build").isDirectory)
            assertTrue(File(packageDir, "build/crossenv").isDirectory)
            assertTrue(File(packageDir, "build/packpack").isDirectory)

            assertFalse(File(packageDir, "src/windows").exists())
            assertFalse(File(packageDir, "src/android").exists())
            assertFalse(File(packageDir, "build/packpack/binary").exists())
            assertFalse(File(packageDir, "build/packpack/fat").exists())
        }
    }

    @Test
    fun addPackage_inheritsWorkspaceTargetsIntoPackagePyproject() {
        withWorkspace(
            """
            [project]
            name = "root"

            [tool.uv.workspace]
            members = []

            [tool.ppp.dependencies]
            platforms = ["x86_64-pc-windows-msvc", "x86_64-unknown-linux-gnu"]
            """.trimIndent(),
        ) { workspaceRoot ->
            val crossEnv = CrossEnv()
            crossEnv.initialize(FakeBackend())

            val result = crossEnv.addPackage("demo_pkg")

            assertTrue(result.isSuccess)
            val packagePyproject = File(workspaceRoot, "demo_pkg/pyproject.toml")
            val packageEditor = TomlEditor(packagePyproject.readText())
            val platforms = packageEditor.getArray("tool.ppp.dependencies", "platforms")
            assertEquals(
                listOf("x86_64-pc-windows-msvc", "x86_64-unknown-linux-gnu"),
                platforms,
            )
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
        val pppGradleTestDir = File("/workspace/ppp_gradle_test")
        pppGradleTestDir.mkdirs()
        val tempRoot = Files.createTempDirectory(pppGradleTestDir.toPath(), "crossenv-test").toFile()
        val oldUserDir = System.getProperty("user.dir")
        try {
            File(tempRoot, "pyproject.toml").writeText(pyprojectContent)
            System.setProperty("user.dir", tempRoot.absolutePath)
            block(tempRoot)
        } finally {
            System.setProperty("user.dir", oldUserDir)
        }
    }

    private class FakeBackend : BaseInterface {
        companion object {
            var lastInitPath: String? = null
            var lastInitExtraArgs: Map<String, String> = emptyMap()
        }

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
            val workingDir = extraArgs?.get("directory") ?: extraArgs?.get("__working_dir")
            val packageDir =
                when {
                    !path.isNullOrBlank() && !workingDir.isNullOrBlank() -> File(workingDir, path)
                    !path.isNullOrBlank() -> File(path)
                    !workingDir.isNullOrBlank() -> File(workingDir)
                    else -> return Result.failure(IllegalArgumentException("path or working dir required"))
                }
            packageDir.mkdirs()
            File(packageDir, "pyproject.toml").writeText("[project]\nname = \"${packageDir.name}\"\n")
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
