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
        assertEquals("android-aarch64", android.hostPlatform, "sysconfig.get_platform() makes it android-24-arm64_v8a")
        assertEquals("android", android.sysPlatform)
        assertEquals(24, android.androidApiLevel)
        assertEquals("android-x86_64", crossTargetIdentity("x86_64-linux-android").hostPlatform)
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
        assertEquals("dynamic", calls[3].command[2])
        assertEquals("backend", calls[4].command[2])
        assertEquals(6, calls.size, "an empty dynamic list installs nothing")
        listOf(1, 3, 4).forEach { assertTrue(calls[it].env.isEmpty(), "host mode: ${calls[it].env}") }
        val build = calls[5]
        assertEquals("build", build.command[2])
        assertEquals(5, build.command.size, "setuptools needs no config settings: ${build.command}")
        assertEquals(File(envDir, "ppp-cross/config.json").absolutePath, build.env["PPP_CROSS_CONFIG"])
        assertEquals("/ndk/bin/aarch64-linux-android24-clang", build.env["CC"])
        assertEquals("/ndk/bin/aarch64-linux-android24-clang -shared", build.env["LDSHARED"])
        assertTrue("-lpython3.15t" in build.env["LDFLAGS"].orEmpty(), build.env.toString())

        val config = MiniJson.parse(File(envDir, "ppp-cross/config.json").readText()) as Map<*, *>
        assertEquals("android-aarch64", config["host_platform"])
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
        assertEquals(mapOf("cmake.define.CMAKE_TOOLCHAIN_FILE" to toolchain.absolutePath), settingsOf(cmake))
        val cmakeText = toolchain.readText()
        assertTrue("set(CMAKE_SYSTEM_NAME Linux)" in cmakeText && "set(CMAKE_SYSTEM_PROCESSOR aarch64)" in cmakeText, cmakeText)
        val cc = File(crossFiles, "bin/cc")
        assertTrue("set(CMAKE_C_COMPILER \"${cc.absolutePath}\")" in cmakeText, cmakeText)
        assertEquals("#!/bin/sh\nexec /ndk/bin/aarch64-linux-android24-clang \"\$@\"\n", cc.readText())
        assertTrue(cc.canExecute())
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
                    "host_platform" to "android-aarch64", "sys_platform" to "android", "multiarch" to "aarch64-linux-android",
                    "prefix" to prefix.absolutePath, "uname_system" to "Linux", "uname_release" to "", "uname_machine" to "aarch64",
                    "android_api_level" to 24,
                ),
            ),
        )
        // CPython 3.13+ (the 3.15t host) maps an Android platform to android-<API level>-<ABI>; older ones keep it.
        val (versionCode, version) = python3(listOf("-c", "import sys; print(sys.version_info >= (3, 13))"), emptyMap(), tempDir)
        assertEquals(0, versionCode, version)
        val platform = if (version.trim() == "True") "android-24-arm64_v8a" else "android-aarch64"
        val probe = "import _ppp_cross, sys, os, sysconfig; print(sys.platform, sysconfig.get_platform(), " +
            "sysconfig.get_config_var('EXT_SUFFIX'), os.uname().machine, getattr(sys, 'getandroidapilevel', lambda: 0)(), sys.base_prefix)"
        val (code, out) = python3(listOf("-c", probe), mapOf("PYTHONPATH" to hookDir.absolutePath, "PPP_CROSS_CONFIG" to config.absolutePath), tempDir)
        assertEquals(0, code, out)
        assertEquals("android $platform .cpython-315t-aarch64-linux-android.so aarch64 24 ${prefix.absolutePath}", out.trim())

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
                "    print('noise on stdout')\n    sys.argv = sys.argv[:1] + ['egg_info']\n    return ['cython>=3']\n" +
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
