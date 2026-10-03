package org.thisisthepy.python.multiplatform.packpack.dependency.middleware.environment

import kotlinx.coroutines.runBlocking
import org.thisisthepy.python.multiplatform.packpack.dependency.backend.BackendInterface
import org.thisisthepy.python.multiplatform.packpack.utils.toml.TomlEditor
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
    fun removeDependenciesMatchesUvNormalizedMarkerText() {
        // `MarkerPolicy.markerForTarget("windows")` writes `platform_system == 'Windows' and
        // platform_machine == 'x86_64'` via `uv add --marker <text>`. Real uv (0.12.3, verified
        // by hand) does not persist that text verbatim: it rewrites the `platform_system` clause
        // into an equivalent `sys_platform` clause and reorders the clauses alphabetically, so
        // pyproject.toml ends up holding `platform_machine == 'x86_64' and sys_platform ==
        // 'win32'` -- exactly what this fixture pre-seeds below. `removeDependencies` must still
        // find this entry when asked to remove the same target it was added for (see
        // docs/SPEC.md's "remove --target" limitation and docs/issues/KNOWN_ISSUES.md).
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
                dependencies = [
                    "requests>=2.34.2 ; platform_machine == 'x86_64' and sys_platform == 'win32'",
                ]
                """.trimIndent(),
            )

            val crossEnv = CrossEnv()
            crossEnv.initialize(FakeBackend())

            crossEnv.removeDependencies("core", listOf("requests"), listOf("windows"), null).getOrThrow()

            val remaining =
                TomlEditor(File(workspaceRoot, "src/core/pyproject.toml").readText())
                    .getArray("project", "dependencies")
            assertTrue(remaining.isEmpty())
        }
    }

    @Test
    fun removeDependenciesDoesNotMatchAWrongTargetsNormalizedMarker() {
        // Companion to the test above: a marker that normalizes to a *different* target (macOS)
        // must still be left alone when removing for "windows", so the fix can't just accept any
        // sys_platform-shaped marker -- it has to compare the actual (family, machine) pair.
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
                dependencies = [
                    "requests>=2.34.2 ; platform_machine == 'arm64' and sys_platform == 'darwin'",
                ]
                """.trimIndent(),
            )

            val crossEnv = CrossEnv()
            crossEnv.initialize(FakeBackend())

            val result = crossEnv.removeDependencies("core", listOf("requests"), listOf("windows"), null)
            assertTrue(result.isFailure)

            val remaining =
                TomlEditor(File(workspaceRoot, "src/core/pyproject.toml").readText())
                    .getArray("project", "dependencies")
            assertEquals(1, remaining.size)
        }
    }

    @Test
    fun syncDependenciesInstallsPerTargetCrossenvDirectories() {
        // docs/SPEC.md's "Not yet implemented (target)" list names two related gaps: no dedicated
        // venv/install-directory per CrossEnv target, and no step during `sync` that preserves
        // per-target install results. This is the `sync`-side half: for each resolved target,
        // `syncDependencies` should also install that target's dependencies into a dedicated
        // directory under `<package>/build/crossenv/<target>`, not just verify resolvability via
        // `uv tree` as it did before.
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

            val backend = FakeBackend()
            val crossEnv = CrossEnv()
            crossEnv.initialize(backend)

            crossEnv.syncDependencies("core", listOf("windows"), null).getOrThrow()

            assertEquals(1, backend.targetInstallCalls.size)
            val call = backend.targetInstallCalls.single()
            assertEquals(
                File(workspaceRoot, "src/core/build/crossenv/x86_64-pc-windows-msvc").absolutePath,
                File(call.targetDir).absolutePath,
            )
            assertEquals("x86_64-pc-windows-msvc", call.pythonPlatform)
            assertEquals(File(workspaceRoot, "src/core").absolutePath, call.workingDir?.absolutePath)
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

    private class FakeBackend : BackendInterface {
        companion object {
            var lastInitPath: String? = null
            var lastInitExtraArgs: Map<String, String> = emptyMap()
        }

        data class TargetInstallCall(
            val targetDir: String,
            val pythonPlatform: String,
            val workingDir: File?,
        )

        val targetInstallCalls = mutableListOf<TargetInstallCall>()

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

        override suspend fun installDependenciesToTarget(
            targetDir: String,
            pythonPlatform: String,
            extraArgs: Map<String, String>?,
            workingDir: File?,
        ): Result<String> {
            targetInstallCalls += TargetInstallCall(targetDir, pythonPlatform, workingDir)
            return Result.success("ok")
        }

        override suspend fun showDependencyTree(
            packageName: String?,
            extraArgs: Map<String, String>?,
            workingDir: File?,
        ): Result<String> = Result.success("ok")

        override suspend fun lockDependencies(projectRoot: String): Result<String> = Result.success("ok")

        override fun listPython(): Result<String> = Result.success("ok")

        override fun findPython(pythonVersion: String): Result<String> = Result.success("ok")

        override suspend fun installPython(
            pythonVersion: String,
            targetPlatform: String?,
            installDir: File?,
        ): Result<String> = Result.success("ok")

        override fun uninstallPython(pythonVersion: String): Result<String> = Result.success("ok")
    }
}
