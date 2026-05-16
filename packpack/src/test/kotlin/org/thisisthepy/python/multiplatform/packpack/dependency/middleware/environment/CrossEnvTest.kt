package org.thisisthepy.python.multiplatform.packpack.dependency.middleware.environment

import kotlinx.coroutines.runBlocking
import org.thisisthepy.python.multiplatform.packpack.dependency.backend.BaseInterface
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

class CrossEnvTest {
    @Test
    fun addTargetsSyncsWorkspaceMemberPackages() {
        withWorkspace(
            """
            [project]
            name = "root"

            [tool.uv.workspace]
            members = ["src/core", "packages/utils"]
            """.trimIndent(),
        ) { workspaceRoot ->
            File(workspaceRoot, "src/core").mkdirs()
            File(workspaceRoot, "src/core/pyproject.toml").writeText("[project]\nname = \"core\"\n")
            File(workspaceRoot, "packages/utils").mkdirs()
            File(workspaceRoot, "packages/utils/pyproject.toml").writeText(
                """
                [project]
                name = "utils"

                [tool.ppp.dependencies]
                platforms = ["aarch64-apple-darwin"]
                """.trimIndent(),
            )

            val crossEnv = CrossEnv()
            crossEnv.initialize(FakeBackend())

            crossEnv.addTargets(null, listOf("windows")).getOrThrow()

            assertEquals(
                listOf("x86_64-pc-windows-msvc"),
                readTargets(File(workspaceRoot, "src/core/pyproject.toml")),
            )
            assertFalse(File(workspaceRoot, "src/windows/__init__.py").exists())
            assertTrue(File(workspaceRoot, "src/core/src/windows/__init__.py").exists())
            assertEquals(
                listOf("x86_64-pc-windows-msvc", "aarch64-apple-darwin"),
                readTargets(File(workspaceRoot, "packages/utils/pyproject.toml")),
            )
            assertTrue(File(workspaceRoot, "packages/utils/src/windows/__init__.py").exists())
        }
    }

    @Test
    fun addPackageCreatesSourceFoldersForInheritedWorkspaceTargets() {
        withWorkspace(
            """
            [project]
            name = "root"

            [tool.ppp.dependencies]
            platforms = ["x86_64-pc-windows-msvc"]
            """.trimIndent(),
        ) { workspaceRoot ->
            val crossEnv = CrossEnv()
            crossEnv.initialize(FakeBackend())

            crossEnv.addPackage("src/core", null, null).getOrThrow()

            assertEquals(
                listOf("x86_64-pc-windows-msvc"),
                readTargets(File(workspaceRoot, "src/core/pyproject.toml")),
            )
            assertTrue(File(workspaceRoot, "src/core/src/windows/__init__.py").exists())
        }
    }

    @Test
    fun removeTargetsSyncsWorkspaceMemberPackages() {
        withWorkspace(
            """
            [project]
            name = "root"

            [tool.ppp.dependencies]
            platforms = ["x86_64-pc-windows-msvc", "aarch64-apple-darwin"]

            [tool.uv.workspace]
            members = ["src/core"]
            """.trimIndent(),
        ) { workspaceRoot ->
            File(workspaceRoot, "src/core").mkdirs()
            File(workspaceRoot, "src/core/pyproject.toml").writeText(
                """
                [project]
                name = "core"

                [tool.ppp.dependencies]
                platforms = ["x86_64-pc-windows-msvc", "aarch64-apple-darwin"]
                """.trimIndent(),
            )

            val crossEnv = CrossEnv()
            crossEnv.initialize(FakeBackend())

            crossEnv.removeTargets(null, listOf("windows")).getOrThrow()

            assertEquals(
                listOf("aarch64-apple-darwin"),
                readTargets(File(workspaceRoot, "src/core/pyproject.toml")),
            )
        }
    }

    @Test
    fun printResolvedPackageSpecContents() {
        withWorkspace(
            """
            [project]
            name = "root"

            [tool.uv.workspace]
            members = ["src/core", "packages/utils"]
            """.trimIndent(),
        ) {
            val crossEnv = CrossEnv()
            crossEnv.initialize(FakeBackend())

            val resolver =
                CrossEnv::class.java
                    .getDeclaredMethod("resolvePackageSpec", String::class.java, File::class.java, Boolean::class.javaPrimitiveType)
                    .apply { isAccessible = true }

            listOf("core", "src/core", "packages/utils").forEach { packageInput ->
                val packageSpec = resolver.invoke(crossEnv, packageInput, null, true)
                println("packageInput=$packageInput")
                println(packageSpec)
            }
        }
    }

    @Test
    fun addTargetsUsesPackageNameInsteadOfPackagePath() {
        withWorkspace(
            """
            [project]
            name = "root"

            [tool.uv.workspace]
            members = ["src/core"]
            """.trimIndent(),
        ) { workspaceRoot ->
            File(workspaceRoot, "src/core").mkdirs()
            File(workspaceRoot, "src/core/pyproject.toml").writeText("[project]\nname = \"core\"\n")

            val crossEnv = CrossEnv()
            crossEnv.initialize(FakeBackend())

            crossEnv.addTargets("core", listOf("windows")).getOrThrow()

            assertEquals(
                listOf("x86_64-pc-windows-msvc"),
                readTargets(File(workspaceRoot, "src/core/pyproject.toml")),
            )
            assertTrue(File(workspaceRoot, "src/core/src/windows/__init__.py").exists())
            assertFalse(File(workspaceRoot, "src/windows/__init__.py").exists())
        }
    }

    @Test
    fun removeTargetsUsesPackageNameInsteadOfPackagePath() {
        withWorkspace(
            """
            [project]
            name = "root"

            [tool.uv.workspace]
            members = ["src/core"]
            """.trimIndent(),
        ) { workspaceRoot ->
            File(workspaceRoot, "src/core").mkdirs()
            File(workspaceRoot, "src/core/pyproject.toml").writeText(
                """
                [project]
                name = "core"

                [tool.ppp.dependencies]
                platforms = ["x86_64-pc-windows-msvc", "aarch64-apple-darwin"]
                """.trimIndent(),
            )

            val crossEnv = CrossEnv()
            crossEnv.initialize(FakeBackend())

            crossEnv.removeTargets("core", listOf("windows")).getOrThrow()

            assertEquals(
                listOf("aarch64-apple-darwin"),
                readTargets(File(workspaceRoot, "src/core/pyproject.toml")),
            )
        }
    }

    private fun readTargets(pyproject: File): List<String> {
        return TomlEditor(pyproject.readText()).getArray("tool.ppp.dependencies", "platforms")
    }

    private fun withWorkspace(
        pyprojectContent: String,
        block: (File) -> Unit,
    ) {
        val testWorkDir = File("build/tmp/crossenv-test")
        testWorkDir.mkdirs()
        val tempRoot = Files.createTempDirectory(testWorkDir.toPath(), "workspace-").toFile()
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
            workingDir: File?,
        ): Result<String> = Result.success("ok")

        override suspend fun initProject(
            path: String?,
            extraArgs: Map<String, String>?,
            workingDir: File?,
        ): Result<String> {
            lastInitPath = path
            lastInitExtraArgs = extraArgs ?: emptyMap()
            val directory = extraArgs?.get("directory")
            val packageDir =
                when {
                    !path.isNullOrBlank() && workingDir != null -> File(workingDir, path)
                    !path.isNullOrBlank() -> File(path)
                    !directory.isNullOrBlank() -> File(directory)
                    workingDir != null -> workingDir
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

        override suspend fun listPython(): Result<String> = Result.success("ok")

        override suspend fun findPython(pythonVersion: String): Result<String> = Result.success("ok")

        override suspend fun installPython(pythonVersion: String): Result<String> = Result.success("ok")

        override suspend fun uninstallPython(pythonVersion: String): Result<String> = Result.success("ok")
    }
}
