package org.thisisthepy.python.multiplatform.packpack.dependency.middleware

import org.thisisthepy.python.multiplatform.packpack.dependency.backend.BackendInterface
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DefaultMiddlewareInitTest {
    @Test
    fun initProjectCreatesDefaultReadmeAndLicense() {
        val tempRoot = Files.createTempDirectory("ppp-init-").toFile()
        try {
            val middleware = DefaultMiddleware().withBackend(FakeBackend())

            middleware
                .initProject(
                    path = tempRoot.absolutePath,
                    targets = null,
                    extraArgs = mapOf("name" to "demo_pkg"),
                ).getOrThrow()

            val readme = File(tempRoot, "README.md").readText()
            assertTrue(readme.startsWith("# demo_pkg\n\n"))
            assertTrue(readme.contains("A Python project managed by pypackpack."))
            assertTrue(readme.contains("Use ppp to manage dependencies, targets, and builds."))

            val license = File(tempRoot, "LICENSE").readText()
            assertTrue(license.contains("All rights reserved."))
            assertTrue(license.contains("demo_pkg"))
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    /**
     * `ppp init` must not join an unrelated ancestor project: uv's `init` otherwise adds the new
     * project as a workspace member of the nearest ancestor `pyproject.toml` and writes
     * `[tool.uv.workspace]` into that file (docs/SPEC.md, *Project creation*). The middleware asks
     * uv for a standalone project with `--no-workspace`.
     */
    @Test
    fun initProjectAsksUvForAStandaloneProject() {
        val tempRoot = Files.createTempDirectory("ppp-init-").toFile()
        try {
            val backend = FakeBackend()
            DefaultMiddleware().withBackend(backend)
                .initProject(path = tempRoot.absolutePath, targets = null, extraArgs = null)
                .getOrThrow()

            val args = backend.initArgs ?: error("initProject never reached the backend")
            assertTrue(args.containsKey("no-workspace"), args.toString())
            assertEquals("", args["no-workspace"])
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    /**
     * The same with the real uv: an ancestor `pyproject.toml` stays byte for byte as it was, and the
     * new project carries no workspace table. Requires `uv` (from PATH or the copy pypackpack
     * downloads); `uv init --bare` needs no network.
     */
    @Test
    fun initProjectWithRealUvLeavesAnAncestorProjectUntouched() {
        val tempRoot = Files.createTempDirectory("ppp-init-ancestor-").toFile()
        try {
            val ancestorToml = File(tempRoot, "pyproject.toml")
            val ancestorText = "[project]\nname = \"unrelated\"\nversion = \"1.0.0\"\nrequires-python = \">=3.9\"\n"
            ancestorToml.writeText(ancestorText)
            val projectDir = File(tempRoot, "nested/myapp")
            projectDir.mkdirs()

            val middleware = DefaultMiddleware().apply { initialize() }
            middleware
                .initProject(path = projectDir.absolutePath, targets = null, extraArgs = null)
                .getOrThrow()

            assertEquals(ancestorText, ancestorToml.readText(), "ppp init edited the ancestor pyproject.toml")
            val created = File(projectDir, "pyproject.toml")
            assertTrue(created.isFile, "no pyproject.toml at ${created.absolutePath}")
            assertFalse(created.readText().contains("tool.uv.workspace"), created.readText())
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    private fun DefaultMiddleware.withBackend(backend: BackendInterface): DefaultMiddleware {
        DefaultMiddleware::class.java
            .getDeclaredField("backend")
            .apply { isAccessible = true }
            .set(this, backend)
        return this
    }

    private class FakeBackend : BackendInterface {
        var initArgs: Map<String, String>? = null

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
            initArgs = extraArgs
            val projectDir = path?.let { File(it) } ?: workingDir ?: return Result.failure(IllegalArgumentException("path required"))
            projectDir.mkdirs()
            File(projectDir, "pyproject.toml").writeText("[project]\nname = \"${projectDir.name}\"\n")
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
            requirements: List<String>?,
        ): Result<String> = Result.success("ok")

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
