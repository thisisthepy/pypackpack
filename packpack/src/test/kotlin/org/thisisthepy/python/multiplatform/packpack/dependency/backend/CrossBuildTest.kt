package org.thisisthepy.python.multiplatform.packpack.dependency.backend

import org.junit.jupiter.api.io.TempDir
import org.thisisthepy.python.multiplatform.packpack.compile.backend.external.MesonCross
import org.thisisthepy.python.multiplatform.packpack.compile.backend.external.TargetPython
import org.thisisthepy.python.multiplatform.packpack.compile.extension.CompileProcessResult
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The cross build environment (docs/SPEC.md *Cross build environment (sdist-only dependencies)*): the target
 * identity, recipes, PyPI's sdist entry, the command sequence against a recording runner, and the hook and the
 * PEP 517 frontend run by the host `python3` (no network). The real Android build is `CrossSdistRealBuildTest`.
 */
class CrossBuildTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun eachCrossTargetReportsItsWheelPlatformAndTheHostIsRefused() {
        val android = crossTargetIdentity("aarch64-linux-android")
        assertEquals("android-24-arm64_v8a", android.hostPlatform)
        assertEquals("android", android.sysPlatform)
        assertEquals(24, android.androidApiLevel)
        assertEquals("android-24-x86_64", crossTargetIdentity("x86_64-linux-android").hostPlatform)
        assertEquals("ios-13.0-arm64-iphoneos", crossTargetIdentity("arm64-apple-ios").hostPlatform)
        assertEquals("ios-13.0-arm64-iphonesimulator", crossTargetIdentity("arm64-apple-ios-simulator").hostPlatform)
        assertEquals("ios-13.0-x86_64-iphonesimulator", crossTargetIdentity("x86_64-apple-ios-simulator").hostPlatform)
        val linux = crossTargetIdentity("aarch64-unknown-linux-gnu")
        assertEquals("manylinux_2_17_aarch64", linux.hostPlatform)
        assertEquals("linux", linux.sysPlatform)
        assertEquals("manylinux_2_28_x86_64", crossTargetIdentity("x86_64-manylinux_2_28").hostPlatform)
        assertFailsWith<IllegalArgumentException> { crossTargetIdentity("aarch64-apple-darwin") }
        assertFailsWith<IllegalArgumentException> { crossTargetIdentity("x86_64-pc-windows-msvc") }
    }

    @Test
    fun aRecipeIsFoundUnderAnySpellingOfItsNameAndAMissingPatchFails() {
        val pkg = File(tempDir, "pkg").apply { mkdirs() }
        File(pkg, "recipes").mkdirs()
        File(pkg, "recipes/fix.patch").writeText("--- a/x\n+++ b/x\n")
        File(pkg, "pyproject.toml").writeText(
            "[project]\nname = \"core\"\n\n[tool.ppp.recipes.foo_bar]\npatches = [\"recipes/fix.patch\"]\nenv = { FOO_NO_SIMD = \"1\" }\n",
        )
        val recipe = readCrossRecipe(pkg, "Foo.Bar")!!
        assertEquals(listOf(File(pkg, "recipes/fix.patch")), recipe.patches)
        assertEquals(mapOf("FOO_NO_SIMD" to "1"), recipe.env)
        assertEquals(recipe, readCrossRecipe(pkg, "foo-bar"))
        assertNull(readCrossRecipe(pkg, "other"))

        File(pkg, "pyproject.toml").writeText("[project]\nname = \"core\"\n\n[tool.ppp.recipes.foo-bar]\npatches = [\"recipes/gone.patch\"]\n")
        val missing = assertFailsWith<IllegalArgumentException> { readCrossRecipe(pkg, "foo_bar") }
        assertTrue("gone.patch" in missing.message.orEmpty(), missing.message)
    }

    /**
     * Recipe directories (#168, the format the leader approved on 2026-11-25): `<dir>/<name>.toml` with the keys of
     * `[tool.ppp.recipes.<name>]`, patches relative to the file. The first directory that has one wins, and a table
     * in the package's own `pyproject.toml` wins over every file.
     */
    @Test
    fun aRecipeDirectoryHoldsOneTomlPerPackageAndThePackagesOwnTableWins() {
        val pkg = File(tempDir, "pkg").apply { mkdirs() }
        val first = File(tempDir, "recipes-a").apply { mkdirs() }
        val second = File(tempDir, "recipes-b").apply { mkdirs() }
        File(first, "patches").mkdirs()
        File(first, "patches/fix.patch").writeText("--- a/x\n+++ b/x\n")
        File(first, "foo_bar.toml").writeText("patches = [\"patches/fix.patch\"]\nenv = { FOO_NO_SIMD = \"1\" }\n")
        File(second, "foo-bar.toml").writeText("env = { FROM = \"second\" }\n")
        File(second, "only-here.toml").writeText("env = { FROM = \"second\" }\n")

        val recipe = readCrossRecipe(pkg, "Foo.Bar", listOf(first, second))!!
        assertEquals(listOf(File(first, "patches/fix.patch")), recipe.patches, "a patch path is relative to its recipe file")
        assertEquals(mapOf("FOO_NO_SIMD" to "1"), recipe.env)
        assertEquals(CrossRecipe(emptyList(), mapOf("FROM" to "second")), readCrossRecipe(pkg, "only_here", listOf(first, second)))
        assertNull(readCrossRecipe(pkg, "other", listOf(first, second)))
        assertNull(readCrossRecipe(pkg, "foo-bar"), "no directories, no pyproject.toml: no recipe")

        File(pkg, "pyproject.toml").writeText("[project]\nname = \"core\"\n\n[tool.ppp.recipes.foo-bar]\nenv = { FROM = \"pyproject\" }\n")
        assertEquals(mapOf("FROM" to "pyproject"), readCrossRecipe(pkg, "foo_bar", listOf(first, second))!!.env)

        File(first, "gone.toml").writeText("patches = [\"patches/gone.patch\"]\n")
        val missing = assertFailsWith<IllegalArgumentException> { readCrossRecipe(pkg, "gone", listOf(first)) }
        assertTrue("gone.patch" in missing.message.orEmpty() && "gone.toml" in missing.message.orEmpty(), missing.message)
        File(first, "typo.toml").writeText("patch = [\"patches/fix.patch\"]\n")
        val typo = assertFailsWith<IllegalArgumentException> { readCrossRecipe(pkg, "typo", listOf(first)) }
        assertTrue("'patch'" in typo.message.orEmpty() && "patches, env" in typo.message.orEmpty(), typo.message)
    }

    @Test
    fun theSdistEntryIsTakenFromPyPIsJsonAndItsAbsenceIsNamed() {
        val json = """{"info": {"name": "markupsafe"}, "urls": [
            {"filename": "m-3.0-cp313-cp313-manylinux.whl", "packagetype": "bdist_wheel", "url": "https://x/w.whl", "digests": {"sha256": "aa"}},
            {"filename": "markupsafe-3.0.tar.gz", "packagetype": "sdist", "url": "https://x/m.tar.gz", "digests": {"md5": "b", "sha256": "cc"}, "yanked_reason": null, "size": 20}
        ]}"""
        assertEquals(SdistSource("markupsafe-3.0.tar.gz", "https://x/m.tar.gz", "cc"), sdistFromPyPIJson(json, "markupsafe==3.0"))
        val none = assertFailsWith<IllegalStateException> { sdistFromPyPIJson("""{"urls": []}""", "x==1") }
        assertTrue("no sdist for x==1" in none.message.orEmpty(), none.message)
        assertEquals(listOf("a\"b", "c\\d", "é"), MiniJson.parseStringList(MiniJson.write(listOf("a\"b", "c\\d", "é"))))
    }

    /** A fake Android CPython (the `installPython` layout) and the toolchain the Meson cross build would pick. */
    private fun fakeCross(): MesonCross {
        val prefix = File(tempDir, "android_arm64/prefix")
        val include = File(prefix, "include/python3.15t").apply { mkdirs(); File(this, "Python.h").writeText("") }
        val lib = File(prefix, "lib").apply { File(this, "python3.15t").mkdirs() }
        File(lib, "python3.15t/_sysconfigdata_t_android_aarch64-linux-android.py").writeText("build_time_vars = {}\n")
        return MesonCross(
            TargetPython("aarch64-linux-android", include, ".cpython-315t-aarch64-linux-android.so", listOf("-L${lib.path}", "-lpython3.15t"), lib, "libpython3.15t.so"),
            listOf("/ndk/bin/aarch64-linux-android24-clang"),
            listOf("/ndk/bin/aarch64-linux-android24-clang++"),
            listOf("/ndk/bin/llvm-ar"),
            "android",
            null,
            "aarch64",
        )
    }

    private data class Call(val command: List<String>, val dir: File, val env: Map<String, String>)

    /** A runner that plays uv and the frontend: `uv venv` makes the venv, the frontend writes its answers and a wheel. */
    private fun recordingRunner(
        calls: MutableList<Call>,
        backend: String = "setuptools.build_meta",
    ): (List<String>, File, Map<String, String>) -> CompileProcessResult = { command, dir, env ->
        calls += Call(command, dir, env)
        when {
            command.take(2) == listOf("uv", "venv") -> {
                val venv = File(command.last())
                File(venv, "bin").mkdirs()
                File(venv, "bin/python").writeText("")
                File(venv, "lib/python3.15t/site-packages").mkdirs()
            }
            command.getOrNull(2) == "static" -> File(command[4]).writeText("[\"setuptools>=61\"]")
            command.getOrNull(2) == "dynamic" -> File(command[4]).writeText("[]")
            command.getOrNull(2) == "backend" -> File(command[4]).writeText("[\"$backend\"]")
            command.getOrNull(2) == "build" -> File(command[4], "demo-1.0-cp315-cp315t-android_24_arm64_v8a.whl").writeText("wheel")
        }
        val out = if (command.getOrNull(2) == "build") "running build_ext\nPPP_WHEEL=demo-1.0-cp315-cp315t-android_24_arm64_v8a.whl\n" else ""
        CompileProcessResult(0, out)
    }

    @Test
    fun buildSourceCreatesTheEnvironmentInstallsRequirementsOnTheHostAndBuildsInCrossMode() {
        val pkg = File(tempDir, "pkg").apply { mkdirs() }
        val src = File(tempDir, "src/demo-1.0").apply { mkdirs() }
        val calls = mutableListOf<Call>()
        val installs = mutableListOf<Pair<String, String>>()
        val builder = CrossSdistBuilder(
            run = recordingRunner(calls),
            fetch = { error("no network") },
            installHostPython = { version, target, dir ->
                installs += version to target
                File(dir, "bin").mkdirs()
                File(dir, "bin/python3.15t").writeText("")
                Result.success(Unit)
            },
            crossFor = { _, _ -> fakeCross() },
            hostTarget = { "x86_64-unknown-linux-gnu" },
        )

        val wheel = builder.buildSource(pkg, "aarch64-linux-android", src).getOrThrow()

        assertEquals(File(pkg, "build/packpack/cross/aarch64-linux-android/wheels/demo-1.0-cp315-cp315t-android_24_arm64_v8a.whl"), wheel)
        assertEquals(listOf("3.15t" to "x86_64-unknown-linux-gnu"), installs)
        val envDir = File(pkg, "build/packpack/cross/aarch64-linux-android/env")
        val python = File(envDir, "bin/python").absolutePath
        assertEquals(listOf("uv", "venv", "--python", File(pkg, "build/packpack/cross/host-python/bin/python3.15t").absolutePath, envDir.absolutePath), calls[0].command)
        assertEquals("static", calls[1].command[2])
        assertEquals(listOf("uv", "pip", "install", "--python", python, "setuptools>=61"), calls[2].command)
        assertEquals("backend", calls[3].command[2])
        assertEquals("dynamic", calls[4].command[2])
        assertEquals(5, calls[4].command.size, "setuptools gets no settings: ${calls[4].command}")
        assertEquals(6, calls.size, "an empty dynamic list installs nothing")
        listOf(1, 3, 4).forEach { assertTrue(calls[it].env.isEmpty(), "host mode: ${calls[it].env}") }
        val build = calls[5]
        assertEquals("build", build.command[2])
        assertEquals(5, build.command.size, "setuptools needs no config settings: ${build.command}")
        assertEquals(File(envDir, "ppp-cross/config.json").absolutePath, build.env["PPP_CROSS_CONFIG"])
        assertTrue(
            build.env["PATH"].orEmpty().startsWith(File(envDir, "bin").absolutePath + File.pathSeparator),
            "the build requirements' programs come from the environment, not the machine (#168): ${build.env["PATH"]}",
        )
        assertEquals("/ndk/bin/aarch64-linux-android24-clang", build.env["CC"])
        assertEquals("/ndk/bin/aarch64-linux-android24-clang -shared", build.env["LDSHARED"])
        assertTrue("-lpython3.15t" in build.env["LDFLAGS"].orEmpty(), build.env.toString())

        val config = MiniJson.parse(File(envDir, "ppp-cross/config.json").readText()) as Map<*, *>
        assertEquals("android-24-arm64_v8a", config["host_platform"])
        assertEquals("_sysconfigdata_t_android_aarch64-linux-android", config["sysconfigdata_name"])
        assertEquals(24L, config["android_api_level"])
        assertTrue(File(envDir, "ppp-cross/sysconfig/_sysconfigdata_t_android_aarch64-linux-android.py").isFile)
        val prefix = File(envDir, "ppp-cross/prefix")
        assertTrue(File(prefix, "include/python3.15t/Python.h").isFile, "the synthesized prefix exposes the target headers")
        assertTrue(Files.isSymbolicLink(File(prefix, "lib").toPath()))
        val site = File(envDir, "lib/python3.15t/site-packages")
        assertEquals("import _ppp_cross\n", File(site, "_ppp_cross.pth").readText())
        assertTrue(File(site, "_ppp_cross.py").isFile && File(envDir, "ppp-cross/pep517.py").isFile)

        // A second build reuses the environment and the host CPython.
        calls.clear()
        builder.buildSource(pkg, "aarch64-linux-android", src).getOrThrow()
        assertTrue(calls.none { it.command.take(2) == listOf("uv", "venv") })
        assertEquals(1, installs.size)
    }

    /** An iOS simulator slice holds both architectures; the environment takes the target's `_sysconfigdata`. */
    @Test
    fun anIosSimulatorTargetTakesItsOwnArchitecturesSysconfigData() {
        val slice = File(tempDir, "ios_simulator_arm64/Python.xcframework/ios-arm64_x86_64-simulator")
        val headers = File(slice, "Python.framework/Headers").apply { mkdirs(); File(this, "Python.h").writeText("") }
        for (arch in listOf("x86_64", "arm64")) {
            File(slice, "lib-$arch/python3.15t").mkdirs()
            File(slice, "lib-$arch/python3.15t/_sysconfigdata_t_ios_$arch-iphonesimulator.py").writeText("build_time_vars = {}\n")
        }
        val cross = MesonCross(
            TargetPython("arm64-apple-ios-simulator", headers, ".cpython-315t-iphonesimulator.so", listOf("-F${slice.path}", "-framework", "Python"), slice),
            listOf("xcrun", "--sdk", "iphonesimulator", "clang"), listOf("xcrun", "--sdk", "iphonesimulator", "clang++"),
            listOf("xcrun", "--sdk", "iphonesimulator", "ar"), "darwin", "ios-simulator", "aarch64",
        )
        val pkg = File(tempDir, "pkg").apply { mkdirs() }
        val calls = mutableListOf<Call>()
        val builder = CrossSdistBuilder(
            run = recordingRunner(calls),
            fetch = { error("no network") },
            installHostPython = { _, _, dir -> File(dir, "bin").mkdirs(); File(dir, "bin/python3").writeText(""); Result.success(Unit) },
            crossFor = { _, _ -> cross },
            hostTarget = { "aarch64-apple-darwin" },
        )
        builder.buildSource(pkg, "arm64-apple-ios-simulator", File(tempDir, "src/x-1.0").apply { mkdirs() }).getOrThrow()

        val config = MiniJson.parse(File(pkg, "build/packpack/cross/arm64-apple-ios-simulator/env/ppp-cross/config.json").readText()) as Map<*, *>
        assertEquals("_sysconfigdata_t_ios_arm64-iphonesimulator", config["sysconfigdata_name"])
        assertEquals("ios-13.0-arm64-iphonesimulator", config["host_platform"])
        assertEquals("13.0", config["uname_release"], "platform.ios_ver() answers the deployment target")
        assertEquals("xcrun --sdk iphonesimulator clang -shared", calls.single { it.command.getOrNull(2) == "build" }.env["LDSHARED"])
    }

    /** meson-python gets the Meson cross file through `setup-args`; scikit-build-core gets a CMake toolchain file. */
    @Test
    fun mesonPythonGetsACrossFileAndScikitBuildCoreACMakeToolchain() {
        val pkg = File(tempDir, "pkg").apply { mkdirs() }
        val src = File(tempDir, "src/m-1.0").apply { mkdirs() }
        fun builder(backend: String, calls: MutableList<Call>) = CrossSdistBuilder(
            run = recordingRunner(calls, backend),
            fetch = { error("no network") },
            installHostPython = { _, _, dir -> File(dir, "bin").mkdirs(); File(dir, "bin/python3").writeText(""); Result.success(Unit) },
            crossFor = { _, _ -> fakeCross() },
            hostTarget = { "x86_64-unknown-linux-gnu" },
        )
        val crossFiles = File(pkg, "build/packpack/cross/aarch64-linux-android/env/ppp-cross")
        fun settingsOf(calls: List<Call>) = MiniJson.parse(File(calls.single { it.command.getOrNull(2) == "build" }.command[5]).readText())

        val meson = mutableListOf<Call>()
        builder("mesonpy", meson).buildSource(pkg, "aarch64-linux-android", src).getOrThrow()
        val crossFile = File(crossFiles, "meson-cross.ini")
        assertEquals(mapOf("setup-args" to listOf("--cross-file=${crossFile.absolutePath}")), settingsOf(meson))
        val text = crossFile.readText()
        val python = File(pkg, "build/packpack/cross/aarch64-linux-android/env/bin/python").absolutePath
        assertTrue(text.startsWith("[binaries]\npython = '$python'\n"), text)
        assertTrue("c = ['/ndk/bin/aarch64-linux-android24-clang']" in text && "system = 'android'" in text, text)
        assertTrue("[built-in options]" in text && "'-lpython3.15t'" in text, "libpython is linked on Android: $text")

        val cmake = mutableListOf<Call>()
        builder("scikit_build_core.build", cmake).buildSource(pkg, "aarch64-linux-android", src).getOrThrow()
        val toolchain = File(crossFiles, "cmake-toolchain.cmake")
        assertEquals(
            mapOf(
                "cmake.version" to ">=4.1",
                "cmake.define.CMAKE_POLICY_DEFAULT_CMP0190" to "NEW",
                "cmake.define.CMAKE_TOOLCHAIN_FILE" to toolchain.absolutePath,
            ),
            settingsOf(cmake),
        )
        val dynamic = cmake.single { it.command.getOrNull(2) == "dynamic" }
        assertEquals(File(crossFiles, "settings.json").absolutePath, dynamic.command[5], "the requirements see cmake.version")
        val cmakeText = toolchain.readText()
        assertTrue("set(CMAKE_SYSTEM_NAME Linux)" in cmakeText && "set(CMAKE_SYSTEM_PROCESSOR aarch64)" in cmakeText, cmakeText)
        val cc = File(crossFiles, "bin/cc")
        assertTrue("set(CMAKE_C_COMPILER \"${cc.absolutePath}\")" in cmakeText, cmakeText)
        assertEquals("#!/bin/sh\nexec /ndk/bin/aarch64-linux-android24-clang \"\$@\"\n", cc.readText())
        assertTrue(cc.canExecute())

        // FindPython runs the interpreter through the emulator (run 45003070103); a target program is refused.
        val emulator = File(crossFiles, "bin/emulator")
        assertTrue("set(CMAKE_CROSSCOMPILING_EMULATOR \"${emulator.absolutePath}\")" in cmakeText, cmakeText)
        File(python).apply { parentFile.mkdirs(); writeText("#!/bin/sh\necho ran \"\$@\"\n"); setExecutable(true) }
        fun emulate(vararg args: String): Pair<Int, String> {
            val process = ProcessBuilder(listOf(emulator.absolutePath) + args).redirectErrorStream(true).start()
            val out = process.inputStream.bufferedReader().readText()
            return process.waitFor() to out
        }
        assertEquals(0 to "ran -c x\n", emulate(python, "-c", "x"))
        val (refused, message) = emulate(File(tempDir, "a.out").absolutePath)
        assertEquals(1, refused)
        assertTrue("cannot run on the build host" in message, message)
    }

    /**
     * maturin (#168): the Rust target through `MATURIN_PEP517_ARGS`, PyO3's cross variables naming the target CPython,
     * and Cargo's linker, `CC`, `AR` and link arguments for the target, as the Cargo backend's Android path sets them.
     * maturin is cross-built for Android only; another cross target is refused naming it.
     */
    @Test
    fun maturinGetsTheRustTargetAndThePyO3CrossEnvironmentOnAndroidOnly() {
        val pkg = File(tempDir, "pkg").apply { mkdirs() }
        val src = File(tempDir, "src/r-1.0").apply { mkdirs() }
        fun builder(cross: MesonCross, calls: MutableList<Call>) = CrossSdistBuilder(
            run = recordingRunner(calls, "maturin"),
            fetch = { error("no network") },
            installHostPython = { _, _, dir -> File(dir, "bin").mkdirs(); File(dir, "bin/python3").writeText(""); Result.success(Unit) },
            crossFor = { _, _ -> cross },
            hostTarget = { "aarch64-apple-darwin" },
        )
        val calls = mutableListOf<Call>()
        val cross = fakeCross()
        builder(cross, calls).buildSource(pkg, "aarch64-linux-android", src).getOrThrow()

        val build = calls.single { it.command.getOrNull(2) == "build" }
        assertEquals(5, build.command.size, "maturin takes its target from the environment, not config settings: ${build.command}")
        val env = build.env
        assertEquals("--target aarch64-linux-android", env["MATURIN_PEP517_ARGS"])
        val envBin = File(pkg, "build/packpack/cross/aarch64-linux-android/env/bin").absolutePath
        assertTrue(env["PATH"].orEmpty().startsWith(envBin + File.pathSeparator), "maturin's hooks run the env's maturin binary: ${env["PATH"]}")
        assertEquals(cross.target.libDir!!.absolutePath, env["PYO3_CROSS_LIB_DIR"])
        assertEquals("3.15", env["PYO3_CROSS_PYTHON_VERSION"])
        assertEquals("CPython", env["PYO3_CROSS_PYTHON_IMPLEMENTATION"])
        val crossFiles = File(pkg, "build/packpack/cross/aarch64-linux-android/env/ppp-cross")
        val linker = File(crossFiles, "bin/cc")
        assertEquals(linker.absolutePath, env["CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER"])
        assertEquals(linker.absolutePath, env["CC_aarch64_linux_android"])
        assertEquals(File(crossFiles, "bin/c++").absolutePath, env["CXX_aarch64_linux_android"])
        assertEquals(File(crossFiles, "bin/ar").absolutePath, env["AR_aarch64_linux_android"])
        assertEquals("#!/bin/sh\nexec /ndk/bin/aarch64-linux-android24-clang \"\$@\"\n", linker.readText())
        val rustflags = env["CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS"].orEmpty()
        assertTrue("-C link-arg=-lpython3.15t" in rustflags && "-C link-arg=-L${cross.target.libDir!!.path}" in rustflags, rustflags)
        assertEquals("/ndk/bin/aarch64-linux-android24-clang", env["CC"], "the setuptools variables stay for build scripts")

        val ios = MesonCross(
            TargetPython("arm64-apple-ios", File(tempDir, "h").apply { mkdirs() }, ".so", emptyList(), File(tempDir, "ioslib").apply { File(this, "lib-arm64").mkdirs(); File(this, "lib-arm64/_sysconfigdata_t_ios_arm64-iphoneos.py").writeText("") }),
            listOf("xcrun", "clang"), listOf("xcrun", "clang++"), listOf("xcrun", "ar"), "darwin", "ios", "aarch64",
        )
        val refused = builder(ios, mutableListOf()).buildSource(pkg, "arm64-apple-ios", src)
        val message = refused.exceptionOrNull()?.message.orEmpty()
        assertTrue("maturin" in message && "arm64-apple-ios" in message && "Android" in message, message)
    }

    /**
     * The backend-level install toolchain calls (#168): the requirements are pinned for the target with `uv pip
     * compile`, the pins are installed with `--only-binary :all:`, and each pin uv reports as having no usable wheel
     * is built in the cross environment with the recipe directories, then the install repeats with `--find-links`.
     */
    @Test
    fun theTargetInstallPinsThenBuildsEachPinWithoutAWheelAndRetries() {
        val project = File(tempDir, "project").apply { mkdirs() }
        val targetDir = File(tempDir, "deps")
        val work = File(tempDir, "work").apply { mkdirs() }
        val recipes = listOf(File(tempDir, "recipes").apply { mkdirs() })
        val calls = mutableListOf<Call>()
        val built = mutableListOf<Pair<String, List<File>>>()
        var installs = 0
        val noWheel = { spec: String -> CompileProcessResult(1, "× No solution found\n╰─▶ Because $spec has no usable wheels and you require $spec, we can conclude\n") }
        val builder = object : CrossSdistBuilder(run = { _, _, _ -> error("unused") }) {
            override fun buildFromPyPI(packageDir: File, canonicalTarget: String, packageSpec: String, recipeDirs: List<File>): Result<File> {
                assertEquals(project, packageDir)
                built += packageSpec to recipeDirs
                return Result.success(File(wheelsDir(packageDir, canonicalTarget).apply { mkdirs() }, "x.whl").apply { writeText("") })
            }
        }
        val installer = CrossTargetInstaller(builder) { command, dir, env ->
            calls += Call(command, dir, env)
            when (command.getOrNull(2)) {
                "compile" -> {
                    File(command[command.indexOf("--output-file") + 1]).writeText("# pinned by uv\nnumpy==2.5.5\n    # via -r requirements.in\nregex==2026.11.23\n")
                    CompileProcessResult(0, "Resolved 2 packages")
                }
                else -> when (++installs) {
                    1 -> noWheel("numpy==2.5.5")
                    2 -> noWheel("regex==2026.11.23")
                    else -> CompileProcessResult(0, "Installed 2 packages")
                }
            }
        }
        val args = mapOf("python-version" to "3.15", "python" to "3.15t", "only-binary" to ":all:", "find-links" to "/repo/wheels", "default-index" to "https://idx")
        installer.install(project, targetDir, "aarch64-linux-android", listOf("numpy", "regex>=2026"), args, work, recipes).getOrThrow()

        val compile = calls[0].command
        assertEquals(listOf("uv", "pip", "compile"), compile.take(3))
        assertEquals("numpy\nregex>=2026\n", File(compile[3]).readText(), "the requirements go to compile as a file in the working directory")
        assertEquals(work, File(compile[3]).parentFile)
        assertTrue(compile.containsAll(listOf("--python-platform", "aarch64-linux-android", "--python-version", "3.15", "--default-index", "https://idx")), compile.toString())
        assertTrue("--only-binary" !in compile, "compile must not refuse sdists: $compile")
        assertEquals(listOf("numpy==2.5.5" to recipes, "regex==2026.11.23" to recipes), built)
        val wheels = builder.wheelsDir(project, "aarch64-linux-android").absolutePath
        val first = calls[1].command
        assertEquals(listOf("uv", "pip", "install", "numpy==2.5.5", "regex==2026.11.23"), first.take(5))
        assertTrue(first.containsAll(listOf("--target", targetDir.absolutePath, "--only-binary", ":all:", "--find-links", "/repo/wheels", "--no-deps")), first.toString())
        assertTrue(wheels !in first, "nothing built yet: $first")
        val last = calls.last().command
        assertTrue(last.windowed(2).any { it == listOf("--find-links", wheels) } && last.windowed(2).any { it == listOf("--find-links", "/repo/wheels") }, last.toString())
        assertEquals(4, calls.size)

        // A pin that still has no wheel after it was built ends it, naming it; another failure passes through.
        val again = CrossTargetInstaller(builder) { command, _, _ ->
            if (command.getOrNull(2) == "compile") {
                File(command[command.indexOf("--output-file") + 1]).writeText("numpy==2.5.5\n")
                CompileProcessResult(0, "")
            } else {
                noWheel("numpy==2.5.5")
            }
        }.install(project, targetDir, "aarch64-linux-android", listOf("numpy"), emptyMap(), work, recipes)
        assertTrue("numpy==2.5.5" in again.exceptionOrNull()?.message.orEmpty() && "still" in again.exceptionOrNull()?.message.orEmpty(), again.exceptionOrNull()?.message)
        val other = CrossTargetInstaller(builder) { command, _, _ ->
            if (command.getOrNull(2) == "compile") CompileProcessResult(2, "error: network down") else error("no install after a failed compile")
        }.install(project, targetDir, "aarch64-linux-android", listOf("numpy"), emptyMap(), work, recipes)
        assertTrue("network down" in other.exceptionOrNull()?.message.orEmpty(), other.exceptionOrNull()?.message)
        assertEquals("No requirements to install", CrossTargetInstaller(builder) { _, _, _ -> error("no uv") }.install(project, targetDir, "aarch64-linux-android", emptyList(), emptyMap(), work, recipes).getOrThrow())
    }

    @Test
    fun buildFromPyPIChecksTheSdistsHashAndAppliesTheRecipe() {
        val pkg = File(tempDir, "pkg").apply { mkdirs() }
        File(pkg, "recipes").mkdirs()
        File(pkg, "recipes/fix.patch").writeText("patch")
        File(pkg, "pyproject.toml").writeText("[project]\nname = \"core\"\n\n[tool.ppp.recipes.demo]\npatches = [\"recipes/fix.patch\"]\nenv = { DEMO_FLAG = \"on\" }\n")
        val archive = File(tempDir, "demo-1.0.tar.gz")
        org.thisisthepy.python.multiplatform.packpack.utils.ArchiveWriter.writeTarGz(
            File(tempDir, "sdist-root").apply { mkdirs(); File(this, "setup.py").writeText("") },
            "demo-1.0",
            archive,
        )
        val bytes = archive.readBytes()
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun builder(digest: String, calls: MutableList<Call>) = CrossSdistBuilder(
            run = recordingRunner(calls),
            fetch = { url ->
                when (url) {
                    "https://pypi.org/pypi/demo/1.0/json" ->
                        """{"urls": [{"filename": "demo-1.0.tar.gz", "packagetype": "sdist", "url": "https://files/demo-1.0.tar.gz", "digests": {"sha256": "$digest"}}]}""".toByteArray()
                    "https://files/demo-1.0.tar.gz" -> bytes
                    else -> error("unexpected $url")
                }
            },
            installHostPython = { _, _, dir -> File(dir, "bin").mkdirs(); File(dir, "bin/python3").writeText(""); Result.success(Unit) },
            crossFor = { _, _ -> fakeCross() },
            hostTarget = { "x86_64-unknown-linux-gnu" },
        )

        val bad = builder("00", mutableListOf()).buildFromPyPI(pkg, "aarch64-linux-android", "demo==1.0")
        assertTrue("SHA-256" in bad.exceptionOrNull()?.message.orEmpty(), bad.exceptionOrNull()?.message)

        val calls = mutableListOf<Call>()
        val wheel = builder(sha, calls).buildFromPyPI(pkg, "aarch64-linux-android", "demo==1.0").getOrThrow()
        assertTrue(wheel.isFile)
        val src = File(pkg, "build/packpack/cross/aarch64-linux-android/src/demo-1.0")
        assertTrue(File(src, "setup.py").isFile, "the sdist is unpacked without its top directory")
        assertEquals(listOf("patch", "-p1", "--forward", "--batch", "-i", File(pkg, "recipes/fix.patch").absolutePath), calls[0].command)
        assertEquals(src, calls[0].dir)
        assertEquals("on", calls.single { it.command.getOrNull(2) == "build" }.env["DEMO_FLAG"])
        assertFailsWith<IllegalArgumentException> { builder(sha, mutableListOf()).buildFromPyPI(pkg, "aarch64-linux-android", "demo").getOrThrow() }
    }

    private fun python3(args: List<String>, env: Map<String, String>, dir: File): Pair<Int, String> {
        val process = ProcessBuilder(listOf("python3") + args).directory(dir).redirectErrorStream(true)
            .apply { environment().putAll(env) }.start()
        val out = process.inputStream.bufferedReader().readText()
        return process.waitFor() to out
    }

    /** The hook, run by the host `python3`: inactive without the config, and with it the interpreter reports the target. */
    @Test
    fun theHookMakesTheHostInterpreterReportTheTarget() {
        val hookDir = File(tempDir, "hook").apply { mkdirs() }
        File(hookDir, "_ppp_cross.py").writeText(CrossBuildScripts.HOOK)
        val sysconfigDir = File(tempDir, "sc").apply { mkdirs() }
        File(sysconfigDir, "_sysconfigdata_t_android_aarch64-linux-android.py").writeText(
            "build_time_vars = {'EXT_SUFFIX': '.cpython-315t-aarch64-linux-android.so', 'ANDROID_API_LEVEL': 24}\n",
        )
        val prefix = File(tempDir, "prefix").apply { mkdirs() }
        val config = File(tempDir, "config.json")
        config.writeText(
            MiniJson.write(
                linkedMapOf(
                    "sysconfig_dir" to sysconfigDir.absolutePath, "sysconfigdata_name" to "_sysconfigdata_t_android_aarch64-linux-android",
                    "host_platform" to "android-24-arm64_v8a", "sys_platform" to "android", "multiarch" to "aarch64-linux-android",
                    "prefix" to prefix.absolutePath, "uname_system" to "Linux", "uname_release" to "", "uname_machine" to "aarch64",
                    "android_api_level" to 24,
                ),
            ),
        )
        // sysconfig.get_platform() is the wheel platform on every CPython (3.13 returns _PYTHON_HOST_PLATFORM as
        // it is, 3.15 parses it). CPython 3.13+ makes platform.uname()'s system Android, which the hook answers
        // without the device (runs 44980924111, 44989636124, 45003070103).
        val (versionCode, version) = python3(listOf("-c", "import sys; print(sys.version_info >= (3, 13))"), emptyMap(), tempDir)
        assertEquals(0, versionCode, version)
        val modern = version.trim() == "True"
        val platform = "android-24-arm64_v8a"
        val system = if (modern) "Android" else "Linux"
        val probe = "import _ppp_cross, sys, os, sysconfig, platform; print(sys.platform, sysconfig.get_platform(), platform.system(), " +
            "sysconfig.get_config_var('EXT_SUFFIX'), os.uname().machine, getattr(sys, 'getandroidapilevel', lambda: 0)(), sys.base_prefix)"
        val (code, out) = python3(listOf("-c", probe), mapOf("PYTHONPATH" to hookDir.absolutePath, "PPP_CROSS_CONFIG" to config.absolutePath), tempDir)
        assertEquals(0, code, out)
        assertEquals("android $platform $system .cpython-315t-aarch64-linux-android.so aarch64 24 ${prefix.absolutePath}", out.trim())

        // iOS: platform.uname() would ask UIDevice through Objective-C; the hook answers it.
        File(sysconfigDir, "_sysconfigdata_t_ios_arm64-iphonesimulator.py").writeText("build_time_vars = {'IPHONEOS_DEPLOYMENT_TARGET': '13.0'}\n")
        val iosConfig = File(tempDir, "ios.json")
        iosConfig.writeText(
            MiniJson.write(
                linkedMapOf(
                    "sysconfig_dir" to sysconfigDir.absolutePath, "sysconfigdata_name" to "_sysconfigdata_t_ios_arm64-iphonesimulator",
                    "host_platform" to "ios-13.0-arm64-iphonesimulator", "sys_platform" to "ios", "multiarch" to "arm64-iphonesimulator",
                    "prefix" to prefix.absolutePath, "uname_system" to "iOS", "uname_release" to "13.0", "uname_machine" to "arm64",
                    "android_api_level" to null,
                ),
            ),
        )
        val iosProbe = "import _ppp_cross, sysconfig, platform, subprocess, sys; print(sysconfig.get_platform(), platform.system(), " +
            "platform.machine(), platform.release(), subprocess.run([sys.executable, '-c', 'pass']).returncode)"
        val (iosCode, iosOut) = python3(listOf("-c", iosProbe), mapOf("PYTHONPATH" to hookDir.absolutePath, "PPP_CROSS_CONFIG" to iosConfig.absolutePath), tempDir)
        assertEquals(0, iosCode, iosOut)
        assertEquals("ios-13.0-arm64-iphonesimulator iOS arm64 13.0 0", iosOut.trim(), "subprocess still runs processes")

        val (idle, idleOut) = python3(listOf("-c", "import _ppp_cross, sys; print(sys.platform)"), mapOf("PYTHONPATH" to hookDir.absolutePath), tempDir)
        assertEquals(0, idle, idleOut)
        assertTrue(idleOut.trim() != "android", "without PPP_CROSS_CONFIG the hook does nothing")
    }

    /**
     * The PEP 517 frontend, run by the host `python3` against an in-tree backend. The backend rewrites `sys.argv`
     * in its hook, as setuptools does (run 44980924111).
     */
    @Test
    fun theFrontendReadsRequirementsAndBuildsThroughTheBackend() {
        val src = File(tempDir, "proj").apply { mkdirs() }
        File(src, "pyproject.toml").writeText(
            "[build-system]\nrequires = [\"flit_core>=3\"]\nbuild-backend = \"backend\"\nbackend-path = [\"_build\"]\n",
        )
        File(src, "_build").mkdirs()
        File(src, "_build/backend.py").writeText(
            "import os\n" +
                "import sys\n" +
                "def get_requires_for_build_wheel(config_settings=None):\n" +
                "    print('noise on stdout')\n    sys.argv = sys.argv[:1] + ['egg_info']\n" +
                "    return ['cython>=3'] + (config_settings or {}).get('extra', [])\n" +
                "def build_wheel(out, config_settings=None, metadata_directory=None):\n" +
                "    name = 'proj-1.0-py3-none-any.whl'\n    open(os.path.join(out, name), 'w').write(repr(config_settings))\n    return name\n",
        )
        val frontend = File(tempDir, "pep517.py").apply { writeText(CrossBuildScripts.PEP517) }
        val answer = File(tempDir, "answer.json")
        val (versionCode, version) = python3(listOf("-c", "import sys; print(sys.version_info >= (3, 11))"), emptyMap(), tempDir)
        org.junit.jupiter.api.Assumptions.assumeTrue(versionCode == 0 && version.trim() == "True", "tomllib needs Python 3.11+")

        assertEquals(0, python3(listOf(frontend.path, "static", src.path, answer.path), emptyMap(), tempDir).first)
        assertEquals(listOf("flit_core>=3"), MiniJson.parseStringList(answer.readText()))
        val (dynCode, dynOut) = python3(listOf(frontend.path, "dynamic", src.path, answer.path), emptyMap(), tempDir)
        assertEquals(0, dynCode, dynOut)
        assertEquals(listOf("cython>=3"), MiniJson.parseStringList(answer.readText()))
        val extra = File(tempDir, "extra.json").apply { writeText("{\"extra\": [\"cmake>=4.1\"]}") }
        assertEquals(0, python3(listOf(frontend.path, "dynamic", src.path, answer.path, extra.path), emptyMap(), tempDir).first)
        assertEquals(listOf("cython>=3", "cmake>=4.1"), MiniJson.parseStringList(answer.readText()), "dynamic passes the settings")
        assertEquals(0, python3(listOf(frontend.path, "backend", src.path, answer.path), emptyMap(), tempDir).first)
        assertEquals(listOf("backend"), MiniJson.parseStringList(answer.readText()))
        val out = File(tempDir, "out")
        val settings = File(tempDir, "settings.json").apply { writeText("{\"setup-args\": [\"--cross-file=x.ini\"]}") }
        val (code, built) = python3(listOf(frontend.path, "build", src.path, out.path, settings.path), emptyMap(), tempDir)
        assertEquals(0, code, built)
        assertTrue("PPP_WHEEL=proj-1.0-py3-none-any.whl" in built, built)
        assertEquals("{'setup-args': ['--cross-file=x.ini']}", File(out, "proj-1.0-py3-none-any.whl").readText(), "the settings reach build_wheel")

        File(src, "pyproject.toml").writeText("[project]\nname = \"proj\"\n")
        assertEquals(0, python3(listOf(frontend.path, "static", src.path, answer.path), emptyMap(), tempDir).first)
        assertEquals(listOf("setuptools>=40.8.0"), MiniJson.parseStringList(answer.readText()), "no [build-system]: the PEP 517 default")
    }
}
