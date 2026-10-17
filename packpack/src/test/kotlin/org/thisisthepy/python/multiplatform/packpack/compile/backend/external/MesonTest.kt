package org.thisisthepy.python.multiplatform.packpack.compile.backend.external

import kotlinx.coroutines.runBlocking
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

    /**
     * SPEC "Scope decisions": extensions target PEP 803's `abi3t` by default. Meson's `limited_api`
     * (since 1.3.0) defines `Py_LIMITED_API`, and a free-threaded 3.15 interpreter's headers turn that
     * into `abi3t` (`pyabi.h`). `-Dpython.allow_limited_api=false` is Meson's own opt-out.
     */
    @Test
    fun makeMesonBuild_targetsTheStableAbiFrom315ByDefault() {
        withWorkspace {
            File(this, "pyproject.toml").writeText("[project]\nname = \"core\"\nversion = \"0.1.0\"\n")
            val packageDir = File(this, "src/main/core")
            packageDir.mkdirs()
            File(packageDir, "__init__.py").writeText("")
            File(packageDir, "_hello.c").writeText("/* c extension */\n")
            File(packageDir, "_world.cpp").writeText("// c++ extension\n")

            val content = Meson().makeMesonBuild(absolutePath).getOrThrow()

            assertEquals(2, Regex("  limited_api: '3\\.15',").findAll(content).count(), content)
            assertTrue(content.contains("  meson_version: '>=1.3.0',"), content)
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
    fun makeMesonBuild_generatesCppExtensionTargets() {
        withWorkspace {
            File(this, "pyproject.toml").writeText(
                """
                [project]
                name = "cpp-only"
                version = "0.1.0"
                """.trimIndent(),
            )

            val packageDir = File(this, "src/main/cpp_only")
            packageDir.mkdirs()
            File(packageDir, "__init__.py").writeText("")
            File(packageDir, "_cppcalc.cpp").writeText("/* cpp extension */\n")

            val result = Meson().makeMesonBuild(absolutePath)

            assertTrue(result.isSuccess, result.exceptionOrNull()?.message)
            val content = result.getOrThrow()
            assertTrue(content.contains("  'cpp',"), content)
            assertFalse(content.contains("  'c',"), content)
            assertTrue(content.contains("py.extension_module("), content)
            assertTrue(content.contains("  '_cppcalc',"), content)
            assertTrue(content.contains("  files('src/main/cpp_only/_cppcalc.cpp'),"), content)
            assertTrue(content.contains("  subdir: 'cpp_only',"), content)
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

    // Regression coverage for: Meson.installMeson() existed but setup() never called it, so a
    // build failed outright whenever `meson`/`ninja` weren't already on PATH. setup() must now
    // install them first when isMesonInstalled() reports they are missing, and must skip that
    // step entirely when they are already present.

    @Test
    fun setup_installsMesonWhenNotAlreadyInstalled() {
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

            val meson = RecordingMeson(mesonInstalled = false)

            val projectDir = this
            val result = runBlocking { meson.setup(buildDir = "build", options = null, workingDir = projectDir) }

            assertTrue(result.isSuccess, result.exceptionOrNull()?.message)
            assertTrue(meson.installMesonCalled, "installMeson() should have been called")
            assertEquals(listOf("setup", "build"), meson.executedCommands.single())
        }
    }

    @Test
    fun setup_skipsInstallWhenMesonAlreadyInstalled() {
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

            val meson = RecordingMeson(mesonInstalled = true)

            val projectDir = this
            val result = runBlocking { meson.setup(buildDir = "build", options = null, workingDir = projectDir) }

            assertTrue(result.isSuccess, result.exceptionOrNull()?.message)
            assertFalse(meson.installMesonCalled, "installMeson() should not have been called")
        }
    }

    /**
     * Meson's `find_installation()` otherwise uses the interpreter Meson itself runs on (uv's tool
     * Python), which builds against the wrong headers and rejects `limited_api: '3.15'` below 3.15.
     * The project's `.venv` interpreter is passed through a native file's `[binaries] python`.
     */
    @Test
    fun setup_buildsAgainstTheProjectVenvInterpreter() {
        withWorkspace {
            File(this, "pyproject.toml").writeText("[project]\nname = \"core\"\nversion = \"0.1.0\"\n")
            val packageDir = File(this, "src/main/core")
            packageDir.mkdirs()
            File(packageDir, "__init__.py").writeText("")
            val interpreter = File(this, ".venv/bin/python3")
            interpreter.parentFile.mkdirs()
            interpreter.writeText("")

            val meson = RecordingMeson(mesonInstalled = true)
            val projectDir = this
            val result = runBlocking { meson.setup(buildDir = "build", options = listOf("--buildtype=debug"), workingDir = projectDir) }

            assertTrue(result.isSuccess, result.exceptionOrNull()?.message)
            val command = meson.executedCommands.single()
            val nativeFile = File(command[command.indexOf("--native-file") + 1])
            assertEquals(listOf("setup", "build", "--native-file", nativeFile.path, "--buildtype=debug"), command)
            assertTrue(nativeFile.readText().contains("python = '${interpreter.canonicalPath}'"), nativeFile.readText())
        }
    }

    private class RecordingMeson(
        private val mesonInstalled: Boolean,
    ) : Meson() {
        var installMesonCalled = false
            private set
        val executedCommands = mutableListOf<List<String>>()

        override suspend fun isMesonInstalled(): Boolean = mesonInstalled

        override suspend fun installMeson(): Result<String> {
            installMesonCalled = true
            return Result.success("installed")
        }

        override suspend fun executeCommand(
            command: List<String>,
            workingDir: File?,
        ): Result<String> {
            executedCommands.add(command)
            return Result.success("ok")
        }
    }

    @Test
    fun resolveMesonExecutable_throwsWhenNotInstalledAndNoUv() {
        val ex = runCatching {
            Meson.resolveMesonExecutable(
                binPath = null,
                isMesonInstalled = false,
                isWindows = false,
                fileExists = { false }
            )
        }.exceptionOrNull()
        assertTrue(ex is IllegalStateException)
        assertTrue(ex.message!!.contains("install meson or uv"))
    }

    @Test
    fun resolveMesonExecutable_throwsWhenNotInstalledAndUvDirLacksMeson() {
        val ex = runCatching {
            Meson.resolveMesonExecutable(
                binPath = "/some/uv/bin",
                isMesonInstalled = false,
                isWindows = false,
                fileExists = { false }
            )
        }.exceptionOrNull()
        assertTrue(ex is IllegalStateException)
        assertTrue(ex.message!!.contains("/some/uv/bin"))
    }

    @Test
    fun resolveMesonExecutable_returnsAbsoluteWhenUvDirHasMeson() {
        val result = Meson.resolveMesonExecutable(
            binPath = "/some/uv/bin",
            isMesonInstalled = false,
            isWindows = false,
            fileExists = { true }
        )
        assertTrue(result.replace("\\", "/").contains("/some/uv/bin/meson"))
    }

    @Test
    fun isMesonInstalled_checksUvToolDir() {
        withWorkspace {
            val uvToolBin = File(this, "uv_tool_bin")
            uvToolBin.mkdirs()
            
            // On Unix, touch meson and ninja
            val isWindows = System.getProperty("os.name").lowercase().contains("windows")
            val mesonExe = if (isWindows) "meson.exe" else "meson"
            val ninjaExe = if (isWindows) "ninja.exe" else "ninja"
            File(uvToolBin, mesonExe).writeText("")
            File(uvToolBin, ninjaExe).writeText("")
            
            val mockUv = object : org.thisisthepy.python.multiplatform.packpack.dependency.backend.UVBackend() {
                override suspend fun executeCommand(command: List<String>, workingDir: File?): Result<String> {
                    if (command == listOf("tool", "dir", "--bin")) {
                        return Result.success(uvToolBin.absolutePath)
                    }
                    return super.executeCommand(command, workingDir)
                }
            }
            
            val meson = Meson(uv = mockUv)
            val result = kotlinx.coroutines.runBlocking { meson.isMesonInstalled() }
            
            // Depending on system PATH, it might be true anyway, but we just verify it doesn't crash
            // and uses the uv dir fallback logic properly.
            assertTrue(result, "isMesonInstalled should return true when meson and ninja exist in uv tool dir")
        }
    }

    @Test
    fun resolveMesonExecutable_returnsMesonWhenInstalled() {
        val result = Meson.resolveMesonExecutable(
            binPath = null,
            isMesonInstalled = true,
            isWindows = false,
            fileExists = { false }
        )
        assertEquals("meson", result)
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
