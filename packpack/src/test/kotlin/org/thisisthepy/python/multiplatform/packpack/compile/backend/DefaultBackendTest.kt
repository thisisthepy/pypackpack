package org.thisisthepy.python.multiplatform.packpack.compile.backend

import kotlinx.coroutines.runBlocking
import org.thisisthepy.python.multiplatform.packpack.compile.backend.external.Meson
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultBackendTest {
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
            val backend = DefaultBackend(recordingMeson)

            val previousDir = System.getProperty("user.dir")
            try {
                System.setProperty("user.dir", this.absolutePath)
                val result = runBlocking {
                    backend.compile("core", mapOf("type" to "release"))
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
            val backend = DefaultBackend(recordingMeson)

            val previousDir = System.getProperty("user.dir")
            try {
                System.setProperty("user.dir", this.absolutePath)
                val first = runBlocking {
                    backend.compile("core", mapOf("target" to "aarch64-apple-darwin", "type" to "release", "level" to "full"))
                }
                assertTrue(first.isSuccess, first.exceptionOrNull()?.message)
                val firstDestdir = recordingMeson.lastInstallDestdir

                val second = runBlocking {
                    backend.compile("core", mapOf("target" to "x86_64-linux-gnu", "type" to "release", "level" to "full"))
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

    private class RecordingMeson : Meson() {
        var lastSetupBuildDir: String? = null
        var lastSetupOptions: List<String>? = null
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
}
