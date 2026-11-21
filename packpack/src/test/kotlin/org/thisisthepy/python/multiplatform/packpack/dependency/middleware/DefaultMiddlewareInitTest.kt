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

    /** `init --target` records the canonical targets in `[tool.ppp.dependencies].platforms` (#159). */
    @Test
    fun initProjectRecordsItsTargetsAsPlatforms() {
        val tempRoot = Files.createTempDirectory("ppp-init-targets-").toFile()
        try {
            DefaultMiddleware().withBackend(FakeBackend())
                .initProject(path = tempRoot.absolutePath, targets = listOf("aarch64-apple-ios", "aarch64-linux-android"), extraArgs = null)
                .getOrThrow()

            val toml = File(tempRoot, "pyproject.toml").readText()
            assertTrue(Regex("""\[tool\.ppp\.dependencies]""").containsMatchIn(toml), toml)
            val platforms = Regex("""platforms\s*=\s*\[([^\]]*)]""").find(toml)?.groupValues?.get(1).orEmpty()
            assertTrue("\"aarch64-linux-android\"" in platforms && "\"aarch64-apple-ios\"" in platforms, toml)
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    /** An existing `pyproject.toml` is refused before uv runs, naming whose project it is (#159). */
    @Test
    fun initProjectRefusesAnExistingProjectAndNamesItsTool() {
        val cases = mapOf(
            "[project]\nname = \"a\"\n\n[tool.ppp.dependencies]\nplatforms = []\n" to "already a pypackpack project",
            "[tool.poetry]\nname = \"a\"\n" to "configured for poetry",
            "[project]\nname = \"a\"\n" to "a Python project already exists",
        )
        for ((toml, expected) in cases) {
            val tempRoot = Files.createTempDirectory("ppp-init-existing-").toFile()
            try {
                File(tempRoot, "pyproject.toml").writeText(toml)
                val backend = FakeBackend()
                val result = DefaultMiddleware().withBackend(backend)
                    .initProject(path = tempRoot.absolutePath, targets = null, extraArgs = null)

                assertTrue(result.isFailure, toml)
                assertTrue(expected in result.exceptionOrNull()?.message.orEmpty(), result.exceptionOrNull()?.message)
                assertEquals(null, backend.initArgs, "uv init must not run")
                assertEquals(toml, File(tempRoot, "pyproject.toml").readText(), "the existing file is untouched")
            } finally {
                tempRoot.deleteRecursively()
            }
        }
    }

    /** Another tool's files do not stop init, but the result says pypackpack does not read them. */
    @Test
    fun initProjectWarnsAboutAnotherPackagingToolsFiles() {
        val tempRoot = Files.createTempDirectory("ppp-init-othertool-").toFile()
        try {
            File(tempRoot, "setup.py").writeText("from setuptools import setup\nsetup()\n")
            File(tempRoot, "Pipfile").writeText("[packages]\n")

            val output = DefaultMiddleware().withBackend(FakeBackend())
                .initProject(path = tempRoot.absolutePath, targets = null, extraArgs = null)
                .getOrThrow()

            assertTrue("setup.py, Pipfile" in output && "another packaging tool" in output, output)
            assertTrue(File(tempRoot, "pyproject.toml").isFile)
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    /** An unknown target is refused before uv runs, so nothing is created. */
    @Test
    fun initProjectRefusesAnUnknownTargetBeforeUv() {
        val tempRoot = Files.createTempDirectory("ppp-init-badtarget-").toFile()
        try {
            val backend = FakeBackend()
            val result = DefaultMiddleware().withBackend(backend)
                .initProject(path = tempRoot.absolutePath, targets = listOf("not-a-target"), extraArgs = null)

            assertTrue(result.isFailure)
            assertTrue("not-a-target" in result.exceptionOrNull()?.message.orEmpty(), result.exceptionOrNull()?.message)
            assertEquals(null, backend.initArgs, "uv init must not run")
            assertFalse(File(tempRoot, "pyproject.toml").exists())
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

    /**
     * `pypackpack python use <version>` (docs/SPEC.md *Changing the project's Python version*): the
     * project root's `.venv` is deleted, recreated with the requested version in the project root, and
     * synchronized there. Here because it shares this file's recording backend.
     */
    @Test
    fun changePythonVersionRecreatesTheRootVenvWithTheNewVersionAndSyncs() {
        val tempRoot = Files.createTempDirectory("ppp-python-use-").toFile().canonicalFile
        val oldUserDir = System.getProperty("user.dir")
        try {
            File(tempRoot, "pyproject.toml").writeText("[project]\nname = \"demo\"\n")
            val stale = File(tempRoot, ".venv/bin/python3").apply { parentFile.mkdirs(); writeText("old") }
            val nested = File(tempRoot, "src/core").apply { mkdirs() }
            System.setProperty("user.dir", nested.absolutePath)
            val backend = FakeBackend()

            assertTrue(DefaultMiddleware().withBackend(backend).changePythonVersion("3.15t"))

            assertFalse(stale.exists(), "the old .venv must be deleted before it is recreated")
            val venv = File(tempRoot, ".venv").absolutePath
            assertEquals<List<Triple<String, String?, String?>>>(listOf(Triple(venv, "3.15t", tempRoot.absolutePath)), backend.venvCalls)
            assertEquals<List<Pair<String, String?>>>(listOf(venv to tempRoot.absolutePath), backend.syncCalls)
        } finally {
            System.setProperty("user.dir", oldUserDir)
            tempRoot.deleteRecursively()
        }
    }

    /**
     * docs/SPEC.md *Cleaning up subpackage build directories on `python use`* (#159): every workspace
     * member's `build/packpack/` (the Meson trees configured against the old interpreter) is deleted;
     * the rest of `build/`, `dist/` and the root's `build/crossenv/` stay.
     */
    @Test
    fun changePythonVersionDeletesEachPackagesCompileTreesAndKeepsTheRest() {
        val tempRoot = Files.createTempDirectory("ppp-python-use-clean-").toFile().canonicalFile
        val oldUserDir = System.getProperty("user.dir")
        try {
            File(tempRoot, "pyproject.toml").writeText(
                "[project]\nname = \"ws\"\n\n[tool.uv.workspace]\nmembers = [\"src/core\", \"src/ui\", \"src/gone\"]\n",
            )
            fun touch(path: String) = File(tempRoot, path).apply { parentFile.mkdirs(); writeText("x") }
            val coreTree = touch("src/core/build/packpack/single/debug/instant/default/build.ninja")
            val uiTree = touch("src/ui/build/packpack/single/release/native/default/build.ninja")
            val coreOther = touch("src/core/build/notes.txt")
            val coreDist = touch("src/core/dist/default/debug/instant/core.so")
            val crossenv = touch("build/crossenv/aarch64-linux-android/core/pkg.py")
            System.setProperty("user.dir", tempRoot.absolutePath)

            assertTrue(DefaultMiddleware().withBackend(FakeBackend()).changePythonVersion("3.15t"))

            assertFalse(File(tempRoot, "src/core/build/packpack").exists(), "core's compile tree must be deleted")
            assertFalse(File(tempRoot, "src/ui/build/packpack").exists(), "ui's compile tree must be deleted")
            assertFalse(coreTree.exists() || uiTree.exists())
            assertTrue(coreOther.isFile, "the rest of build/ stays")
            assertTrue(coreDist.isFile, "dist/ stays")
            assertTrue(crossenv.isFile, "the root's build/crossenv stays")
        } finally {
            System.setProperty("user.dir", oldUserDir)
            tempRoot.deleteRecursively()
        }
    }

    /** Without workspace members the project root is the one package: its `build/packpack/` goes. */
    @Test
    fun changePythonVersionInASinglePackageRepositoryDeletesTheRootCompileTree() {
        val tempRoot = Files.createTempDirectory("ppp-python-use-single-").toFile().canonicalFile
        val oldUserDir = System.getProperty("user.dir")
        try {
            File(tempRoot, "pyproject.toml").writeText("[project]\nname = \"solo\"\n")
            val tree = File(tempRoot, "build/packpack/single/debug/instant/default/build.ninja").apply { parentFile.mkdirs(); writeText("x") }
            val crossenv = File(tempRoot, "build/crossenv/x86_64-unknown-linux-gnu/m.py").apply { parentFile.mkdirs(); writeText("x") }
            System.setProperty("user.dir", tempRoot.absolutePath)

            assertTrue(DefaultMiddleware().withBackend(FakeBackend()).changePythonVersion("3.15t"))

            assertFalse(tree.exists(), "the root's compile tree must be deleted")
            assertFalse(File(tempRoot, "build/packpack").exists())
            assertTrue(crossenv.isFile, "build/crossenv stays")
        } finally {
            System.setProperty("user.dir", oldUserDir)
            tempRoot.deleteRecursively()
        }
    }

    private class FakeBackend : BackendInterface {
        var initArgs: Map<String, String>? = null
        val venvCalls = mutableListOf<Triple<String, String?, String?>>()
        val syncCalls = mutableListOf<Pair<String, String?>>()

        override fun initialize() = Unit

        override suspend fun getVersion(): Result<String> = Result.success("uv 0.0.0")

        override suspend fun isToolInstalled(): Boolean = true

        override suspend fun installTool(): Result<String> = Result.success("ok")

        override suspend fun createVirtualEnvironment(
            path: String,
            pythonVersion: String?,
            extraArgs: Map<String, String>?,
            workingDir: File?,
        ): Result<String> {
            venvCalls += Triple(path, pythonVersion, workingDir?.absolutePath)
            return Result.success("ok")
        }

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
        ): Result<String> {
            syncCalls += venvPath to workingDir?.absolutePath
            return Result.success("ok")
        }

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
