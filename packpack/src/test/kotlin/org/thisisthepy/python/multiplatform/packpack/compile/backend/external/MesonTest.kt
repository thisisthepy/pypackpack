package org.thisisthepy.python.multiplatform.packpack.compile.backend.external

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MesonTest {
    @Test
    fun makeMesonBuild_generatesPythonAndCExtensionTargets() {
        withWorkspace {
            File(this, "pyproject.toml").writeText(
                """
                [project]
                name = "core"
                version = "0.1.0"
                """.trimIndent(),
            )

            val packageDir = File(this, "src/main/core")
            packageDir.mkdirs()
            File(packageDir, "__init__.py").writeText("")
            File(packageDir, "api.py").writeText("VALUE = 1\n")
            File(packageDir, "api.pyi").writeText("VALUE: int\n")
            File(packageDir, "py.typed").writeText("")
            File(packageDir, "_hello.c").writeText("/* c extension */\n")

            val result = Meson().makeMesonBuild(absolutePath)

            assertTrue(result.isSuccess, result.exceptionOrNull()?.message)
            assertEquals(result.getOrThrow(), File(this, "meson.build").readText())

            val content = result.getOrThrow()
            assertTrue(content.contains("  'core',"), content)
            assertTrue(content.contains("  'c',"), content)
            assertTrue(content.contains("  version: '0.1.0',"), content)
            assertTrue(content.contains("python = import('python')"), content)
            assertTrue(content.contains("py.extension_module("), content)
            assertTrue(content.contains("  '_hello',"), content)
            assertTrue(content.contains("  files('src/main/core/_hello.c'),"), content)
            assertTrue(content.contains("  subdir: 'core',"), content)
            assertTrue(content.contains("py.install_sources("), content)
            assertTrue(content.contains("    'src/main/core/__init__.py',"), content)
            assertTrue(content.contains("    'src/main/core/api.py',"), content)
            assertTrue(content.contains("    'src/main/core/api.pyi',"), content)
            assertTrue(content.contains("    'src/main/core/py.typed',"), content)
        }
    }

    @Test
    fun makeMesonBuild_generatesInstallOnlyProjectWithoutCSource() {
        withWorkspace {
            File(this, "pyproject.toml").writeText(
                """
                [project]
                name = "pure"
                """.trimIndent(),
            )

            val packageDir = File(this, "src/pure")
            packageDir.mkdirs()
            File(packageDir, "__init__.py").writeText("")

            val result = Meson().makeMesonBuild(absolutePath)

            assertTrue(result.isSuccess, result.exceptionOrNull()?.message)
            val content = result.getOrThrow()
            assertTrue(content.contains("  'pure',"), content)
            assertTrue(content.contains("  version: '0.0.0',"), content)
            assertFalse(content.contains("  'c',"), content)
            assertFalse(content.contains("py.extension_module("), content)
            assertTrue(content.contains("    'src/pure/__init__.py',"), content)
        }
    }

    @Test
    fun makeMesonBuild_doesNotOverwriteExistingMesonBuild() {
        withWorkspace {
            File(this, "pyproject.toml").writeText(
                """
                [project]
                name = "core"
                """.trimIndent(),
            )
            val packageDir = File(this, "src/main/core")
            packageDir.mkdirs()
            File(packageDir, "__init__.py").writeText("")
            File(this, "meson.build").writeText("existing")

            val result = Meson().makeMesonBuild(absolutePath)

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message?.contains("meson.build already exists") == true)
            assertEquals("existing", File(this, "meson.build").readText())
        }
    }

    @Test
    fun makeMesonBuild_overwritesExistingMesonBuildWhenRequested() {
        withWorkspace {
            File(this, "pyproject.toml").writeText(
                """
                [project]
                name = "core"
                """.trimIndent(),
            )
            val packageDir = File(this, "src/main/core")
            packageDir.mkdirs()
            File(packageDir, "__init__.py").writeText("")
            File(this, "meson.build").writeText("existing")

            val result = Meson().makeMesonBuild(absolutePath, overwrite = true)

            assertTrue(result.isSuccess, result.exceptionOrNull()?.message)
            assertEquals(result.getOrThrow(), File(this, "meson.build").readText())
            assertTrue(File(this, "meson.build").readText().contains("'core'"))
        }
    }

    private fun withWorkspace(block: File.() -> Unit) {
        val testWorkDir = File("build/tmp/meson-test")
        testWorkDir.mkdirs()
        val tempRoot = Files.createTempDirectory(testWorkDir.toPath(), "workspace-").toFile()
        try {
            tempRoot.block()
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
