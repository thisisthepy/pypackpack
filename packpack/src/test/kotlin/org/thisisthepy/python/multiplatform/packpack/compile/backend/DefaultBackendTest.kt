package org.thisisthepy.python.multiplatform.packpack.compile.backend

import kotlinx.coroutines.runBlocking
import org.thisisthepy.python.multiplatform.packpack.compile.backend.external.Cargo
import org.thisisthepy.python.multiplatform.packpack.compile.backend.external.Meson
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultBackendTest {
    /** After Meson, `build` builds the package's Rust crates (#92); `default` means the host. */
    @Test
    fun compile_buildsTheRustCratesAfterMeson() {
        withWorkspace {
            File(this, "pyproject.toml").writeText("[tool.uv.workspace]\nmembers = [\"src/main/core\"]\n")
            val packageDir = File(this, "src/main/core")
            packageDir.mkdirs()
            File(packageDir, "pyproject.toml").writeText("[project]\nname = \"core\"\nversion = \"0.1.0\"\n")
            val calls = mutableListOf<List<Any?>>()
            val cargo = object : Cargo() {
                override fun build(packageDir: File, target: String?, buildType: String, destdir: File): Result<List<File>> {
                    calls += listOf(packageDir, target, buildType, destdir)
                    return Result.success(emptyList())
                }
            }
            val backend = DefaultBackend(RecordingMeson(), cargo, NO_CHECK)

            val previousDir = System.getProperty("user.dir")
            try {
                System.setProperty("user.dir", File(this.absolutePath).parentFile.absolutePath) // not the workspace: compile must use its workingDir (#159)
                runBlocking { backend.compile("core", mapOf("type" to "release", "target" to "macos"), this@withWorkspace) }.getOrThrow()
                runBlocking { backend.compile("core", mapOf("type" to "debug"), this@withWorkspace) }.getOrThrow()
            } finally {
                System.setProperty("user.dir", previousDir)
            }

            val pkg = packageDir.canonicalFile
            assertEquals(
                listOf(
                    listOf<Any?>(pkg, "macos", "release", File("${pkg.absolutePath}/dist/macos/release/instant")),
                    listOf<Any?>(pkg, null, "debug", File("${pkg.absolutePath}/dist/default/debug/instant")),
                ),
                calls,
            )
        }
    }

    /** `--overwrite` clears the build directory without following a link out of it (#96). */
    @Test
    fun compile_overwriteDoesNotFollowALinkOutOfTheBuildDirectory() {
        withWorkspace {
            File(this, "pyproject.toml").writeText("[tool.uv.workspace]\nmembers = [\"src/main/core\"]\n")
            val packageDir = File(this, "src/main/core")
            packageDir.mkdirs()
            File(packageDir, "pyproject.toml").writeText("[project]\nname = \"core\"\nversion = \"0.1.0\"\n")
            val outside = File(this, "outside").apply { mkdirs() }
            File(outside, "keep.txt").writeText("keep")
            val buildDir = File(packageDir, "build/packpack/single/debug/instant/default").apply { mkdirs() }
            java.nio.file.Files.createSymbolicLink(File(buildDir, "link").toPath(), outside.absoluteFile.toPath())

            val previousDir = System.getProperty("user.dir")
            try {
                System.setProperty("user.dir", File(this.absolutePath).parentFile.absolutePath) // not the workspace: compile must use its workingDir (#159)
                runBlocking { DefaultBackend(RecordingMeson(), dependencyCheck = NO_CHECK).compile("core", mapOf("overwrite" to "true"), this@withWorkspace) }.getOrThrow()
            } finally {
                System.setProperty("user.dir", previousDir)
            }

            assertEquals("keep", File(outside, "keep.txt").readText())
        }
    }

    @Test
    fun compile_passesTypeToBuildDirAndMesonOptions() {
        withWorkspace {
            File(this, "pyproject.toml").writeText(
                """
                [tool.uv.workspace]
                members = ["src/main/core"]
                """.trimIndent(),
            )
            val packageDir = File(this, "src/main/core")
            packageDir.mkdirs()
            File(packageDir, "pyproject.toml").writeText(
                """
                [project]
                name = "core"
                version = "0.1.0"
                """.trimIndent(),
            )
            File(packageDir, "__init__.py").writeText("")

            val recordingMeson = RecordingMeson()
            val backend = DefaultBackend(recordingMeson, dependencyCheck = NO_CHECK)

            val previousDir = System.getProperty("user.dir")
            try {
                System.setProperty("user.dir", File(this.absolutePath).parentFile.absolutePath) // not the workspace: compile must use its workingDir (#159)
                val result = runBlocking {
                    backend.compile("core", mapOf("type" to "release"), this@withWorkspace)
                }
                assertTrue(result.isSuccess, result.exceptionOrNull()?.message)
                assertEquals("build/packpack/single/release/instant/default", recordingMeson.lastSetupBuildDir)
                assertEquals(listOf("--buildtype=release"), recordingMeson.lastSetupOptions)
            } finally {
                System.setProperty("user.dir", previousDir)
            }
        }
    }

    /**
     * The defect this separation exists to fix, stated as the thing that used to happen: every
     * target installed into one `dist/`, so building a second target overwrote the first and the
     * only evidence was a wheel with the wrong contents. Two builds that differ only by target
     * must therefore reach two destinations, and each destination must name the three things that
     * distinguish it -- target, build type and level -- or a third combination collides again.
     *
     * Asserted on the destdir handed to `meson install`, not on the build directory, because the
     * build directory was already separated by type before this change and would pass while the
     * install path still collided.
     */
    @Test
    fun compile_installsEachTargetUnderItsOwnDestinationRatherThanOneSharedDist() {
        withWorkspace {
            File(this, "pyproject.toml").writeText(
                """
                [tool.uv.workspace]
                members = ["src/main/core"]
                """.trimIndent(),
            )
            val packageDir = File(this, "src/main/core")
            packageDir.mkdirs()
            File(packageDir, "pyproject.toml").writeText(
                """
                [project]
                name = "core"
                version = "0.1.0"
                """.trimIndent(),
            )
            File(packageDir, "__init__.py").writeText("")

            val recordingMeson = RecordingMeson()
            val backend = DefaultBackend(recordingMeson, dependencyCheck = NO_CHECK)

            val previousDir = System.getProperty("user.dir")
            try {
                System.setProperty("user.dir", File(this.absolutePath).parentFile.absolutePath) // not the workspace: compile must use its workingDir (#159)
                val first = runBlocking {
                    backend.compile("core", mapOf("target" to "aarch64-apple-darwin", "type" to "release", "level" to "full"), this@withWorkspace)
                }
                assertTrue(first.isSuccess, first.exceptionOrNull()?.message)
                val firstDestdir = recordingMeson.lastInstallDestdir

                val second = runBlocking {
                    backend.compile("core", mapOf("target" to "x86_64-linux-gnu", "type" to "release", "level" to "full"), this@withWorkspace)
                }
                assertTrue(second.isSuccess, second.exceptionOrNull()?.message)
                val secondDestdir = recordingMeson.lastInstallDestdir

                assertTrue(
                    firstDestdir != null && secondDestdir != null,
                    "install was never handed a destdir: $firstDestdir / $secondDestdir",
                )
                assertTrue(
                    firstDestdir != secondDestdir,
                    "two targets installed into one destination, which is the overwrite: $firstDestdir",
                )
                assertTrue(
                    firstDestdir!!.endsWith("/dist/aarch64-apple-darwin/release/full"),
                    "the destination must name target, type and level: $firstDestdir",
                )
                assertTrue(
                    secondDestdir!!.endsWith("/dist/x86_64-linux-gnu/release/full"),
                    "the destination must name target, type and level: $secondDestdir",
                )
            } finally {
                System.setProperty("user.dir", previousDir)
            }
        }
    }

    @Test
    fun compile_resolvesSingleProjectWithoutUvWorkspaceByProjectNameOrDot() {
        withWorkspace {
            File(this, "pyproject.toml").writeText(
                """
                [project]
                name = "torchnative"
                version = "0.1.0"
                """.trimIndent(),
            )
            File(this, "__init__.py").writeText("")

            val recordingMeson = RecordingMeson()
            val backend = DefaultBackend(recordingMeson, dependencyCheck = NO_CHECK)

            val previousDir = System.getProperty("user.dir")
            try {
                System.setProperty("user.dir", File(this.absolutePath).parentFile.absolutePath) // not the workspace: compile must use its workingDir (#159)

                val byName = runBlocking { backend.compile("torchnative", emptyMap(), this@withWorkspace) }
                assertTrue(byName.isSuccess, byName.exceptionOrNull()?.message)
                assertEquals(this.canonicalFile, recordingMeson.lastSetupWorkingDir?.canonicalFile)

                val byDot = runBlocking { backend.compile(".", emptyMap(), this@withWorkspace) }
                assertTrue(byDot.isSuccess, byDot.exceptionOrNull()?.message)
                assertEquals(this.canonicalFile, recordingMeson.lastSetupWorkingDir?.canonicalFile)

                val unknown = runBlocking { backend.compile("other-pkg", emptyMap(), this@withWorkspace) }
                assertTrue(unknown.isFailure)
                assertTrue(
                    "not a workspace member" in unknown.exceptionOrNull()?.message.orEmpty(),
                    unknown.exceptionOrNull()?.message,
                )
            } finally {
                System.setProperty("user.dir", previousDir)
            }
        }
    }

    /** `build` verifies dependencies before Meson runs, and a failed check stops it (#159). */
    @Test
    fun compile_verifiesDependenciesBeforeBuildingAndStopsOnAFailedCheck() {
        withWorkspace {
            File(this, "pyproject.toml").writeText("[tool.uv.workspace]\nmembers = [\"src/main/core\"]\n")
            val packageDir = File(this, "src/main/core").apply { mkdirs() }
            File(packageDir, "pyproject.toml").writeText("[project]\nname = \"core\"\nversion = \"0.1.0\"\n")
            val checked = mutableListOf<Pair<File, File>>()
            val meson = RecordingMeson()
            val failing = DependencyCheck { root, pkg ->
                checked += root to pkg
                Result.failure(IllegalStateException("not in sync; run `pypackpack sync`"))
            }

            val result = runBlocking { DefaultBackend(meson, dependencyCheck = failing).compile("core", null, this@withWorkspace) }

            assertTrue(result.isFailure)
            assertTrue("pypackpack sync" in result.exceptionOrNull()?.message.orEmpty())
            assertEquals(listOf(this.canonicalFile to packageDir.canonicalFile), checked)
            assertEquals(null, meson.lastSetupBuildDir, "Meson must not run after a failed check")
        }
    }

    /** `UvDependencyCheck` asks uv `sync --check`, naming the member package in a workspace. */
    @Test
    fun uvDependencyCheck_runsUvSyncCheckForTheMember() {
        withWorkspace {
            File(this, "pyproject.toml").writeText("[tool.uv.workspace]\nmembers = [\"src/main/core\"]\n")
            val packageDir = File(this, "src/main/core").apply { mkdirs() }
            File(packageDir, "pyproject.toml").writeText("[project]\nname = \"core\"\nversion = \"0.1.0\"\n")
            val commands = mutableListOf<Pair<List<String>, File?>>()
            val uv = object : org.thisisthepy.python.multiplatform.packpack.dependency.backend.UVBackend() {
                override suspend fun executeCommand(command: List<String>, workingDir: File?): Result<String> {
                    commands += command to workingDir
                    return Result.failure(Exception("Would install 1 package"))
                }
            }

            val result = runBlocking { UvDependencyCheck(uv).check(this@withWorkspace, packageDir) }

            assertEquals<List<Pair<List<String>, File?>>>(listOf(listOf("sync", "--check", "--package", "core") to this), commands)
            assertTrue(result.isFailure)
            assertTrue("pypackpack sync" in result.exceptionOrNull()?.message.orEmpty() && "Would install" in result.exceptionOrNull()?.message.orEmpty())
        }
    }

    private class RecordingMeson : Meson() {
        var lastSetupBuildDir: String? = null
        var lastSetupOptions: List<String>? = null
        var lastSetupWorkingDir: File? = null
        var lastInstallDestdir: String? = null

        override suspend fun isMesonInstalled(): Boolean = true

        override suspend fun setup(
            buildDir: String,
            options: List<String>?,
            workingDir: File?,
            overwrite: Boolean,
        ): Result<String> {
            lastSetupBuildDir = buildDir
            lastSetupOptions = options
            lastSetupWorkingDir = workingDir
            return Result.success("ok")
        }

        override suspend fun compile(
            buildDir: String,
            options: List<String>?,
            workingDir: File?,
        ): Result<String> = Result.success("ok")

        override suspend fun install(
            buildDir: String,
            options: List<String>?,
            workingDir: File?,
            destdir: String?,
        ): Result<String> {
            lastInstallDestdir = destdir
            return Result.success("ok")
        }
    }

    private fun withWorkspace(block: File.() -> Unit) {
        val testWorkDir = File("build/tmp/compile-backend-test")
        testWorkDir.mkdirs()
        val tempRoot = Files.createTempDirectory(testWorkDir.toPath(), "workspace-").toFile()
        try {
            tempRoot.block()
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    private companion object {
        val NO_CHECK = DependencyCheck { _, _ -> Result.success(Unit) }
    }
}
