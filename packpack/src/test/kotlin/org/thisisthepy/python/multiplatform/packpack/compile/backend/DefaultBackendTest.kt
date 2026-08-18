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
                assertEquals("build/packpack/single/release", recordingMeson.lastSetupBuildDir)
                assertEquals(listOf("--buildtype=release"), recordingMeson.lastSetupOptions)
            } finally {
                System.setProperty("user.dir", previousDir)
            }
        }
    }

    private class RecordingMeson : Meson() {
        var lastSetupBuildDir: String? = null
        var lastSetupOptions: List<String>? = null

        override fun isMesonInstalled(): Boolean = true

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
        ): Result<String> = Result.success("ok")
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
