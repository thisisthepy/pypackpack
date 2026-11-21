package org.thisisthepy.python.multiplatform.packpack.compile.extension

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import org.thisisthepy.python.multiplatform.packpack.compile.backend.external.Clang
import org.thisisthepy.python.multiplatform.packpack.compile.backend.external.NDK
import org.thisisthepy.python.multiplatform.packpack.compile.backend.external.ndkHostTag
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Specification tests for the native compile slot (`ExtensionCompilerInterface`, #20).
 *
 * Covers `checkSupport` refusals across targets and header checks, `Clang` and `NDK` command lines,
 * `-ffp-contract=off -fno-fast-math`, `-MMD` depfile incremental caching, `ModuleCompileException`
 * aggregation across modules, and a real host clang compilation when `xcrun clang` or `clang` is
 * present.
 */
class ExtensionCompilerTest {
    @TempDir
    lateinit var tempDir: File

    private val abi315 = PythonAbi(version = "3.15")

    private fun fakeHeaders(
        dir: File = File(tempDir, "include/python3.15t"),
        pyVersion: String = "3.15.0a2",
        gilDisabled: Boolean = true,
    ): File {
        dir.mkdirs()
        File(dir, "Python.h").writeText("#include \"patchlevel.h\"\n#include \"pyconfig.h\"\n")
        File(dir, "patchlevel.h").writeText("#define PY_VERSION \"$pyVersion\"\n")
        File(dir, "pyconfig.h").writeText(
            if (gilDisabled) "#define Py_GIL_DISABLED 1\n" else "/* GIL build */\n",
        )
        return dir
    }

    private fun fakeNdk(root: File = File(tempDir, "ndk")): File {
        val bin = File(root, "toolchains/llvm/prebuilt/${ndkHostTag()}/bin").apply { mkdirs() }
        listOf(
            "aarch64-linux-android24-clang",
            "aarch64-linux-android28-clang",
            "x86_64-linux-android24-clang",
            "llvm-strip",
        ).forEach { name ->
            File(bin, name).apply {
                writeText("#!/bin/sh\nexit 0\n")
                setExecutable(true)
            }
        }
        return root
    }

    private fun fakeAndroidLib(dir: File = File(tempDir, "android-lib")): File {
        dir.mkdirs()
        File(dir, "libpython3.15t.so").writeText("elf")
        return dir
    }

    private data class RecordedCall(
        val command: List<String>,
        val workingDir: File,
        val env: Map<String, String>,
    )

    private fun fakeCompiler(
        clangBin: List<String>? = listOf("/usr/bin/clang"),
        stripBin: List<String>? = listOf("/usr/bin/strip"),
        compilerVersion: String = "Apple clang version 17.0.0",
        ndkDir: File? = null,
        hostTarget: String = "aarch64-apple-darwin",
        windowsMachine: String? = null,
        targetCompiler: (String) -> List<String>? = { t ->
            when {
                t.endsWith("-apple-ios") -> listOf("xcrun", "--sdk", "iphoneos", "clang")
                t.endsWith("-apple-ios-simulator") -> listOf("xcrun", "--sdk", "iphonesimulator", "clang")
                t.endsWith("-pc-windows-msvc") -> listOf("clang")
                t in Clang.ZIG_LINUX_TARGETS -> listOf("zig", "cc", "-target", Clang.ZIG_LINUX_TARGETS.getValue(t))
                else -> null
            }
        },
        calls: MutableList<RecordedCall> = mutableListOf(),
        onRun: (RecordedCall) -> CompileProcessResult = { call ->
            val outIdx = call.command.indexOf("-o")
            if (outIdx >= 0 && outIdx + 1 < call.command.size) {
                val outFile = File(call.command[outIdx + 1])
                outFile.parentFile?.mkdirs()
                outFile.writeText("binary:${call.command.joinToString(" ")}")
                if ("-MMD" in call.command) {
                    val mfIdx = call.command.indexOf("-MF")
                    val depFile =
                        if (mfIdx >= 0 && mfIdx + 1 < call.command.size) {
                            File(call.command[mfIdx + 1])
                        } else {
                            File(outFile.parentFile, "${outFile.nameWithoutExtension}.d")
                        }
                    val srcFile = call.command.first { it.endsWith(".c") }
                    depFile.writeText("${outFile.path}: $srcFile\n")
                }
            }
            CompileProcessResult(exitCode = 0, output = "")
        },
    ): ExtensionCompilerInterface {
        val runner: (List<String>, File, Map<String, String>) -> CompileProcessResult = { cmd, dir, env ->
            val call = RecordedCall(cmd, dir, env)
            calls += call
            onRun(call)
        }
        return ExtensionCompilerInterface.create(
            clang = Clang(
                locateCompiler = { clangBin },
                locateTargetCompiler = targetCompiler,
                locateStrip = { stripBin },
                versionProbe = { compilerVersion },
                run = runner,
            ),
            ndk = NDK(
                locateNdk = { ndkDir },
                versionProbe = { "Android clang version 18.0.0" },
                run = runner,
            ),
            hostTarget = { hostTarget },
            windowsMachine = { windowsMachine },
        )
    }

    @Test
    fun checkSupport_acceptsMacosSlicesAndRefusesOutOfScopeTargetsAndBadHeaders() {
        val headers = fakeHeaders()
        val compiler = fakeCompiler(hostTarget = "aarch64-apple-darwin")

        assertTrue(compiler.checkSupport("aarch64-apple-darwin", abi315, headers).isSuccess)
        assertTrue(compiler.checkSupport("x86_64-apple-darwin", abi315, headers).isSuccess)

        // wasm waits for #82; Windows off its own machine, and iOS without Python.framework, are refused (#155)
        for (unsupported in listOf("wasm32-emscripten", "x86_64-pc-windows-msvc", "arm64-apple-ios")) {
            val ex = compiler.checkSupport(unsupported, abi315, headers).exceptionOrNull() as? NoCompileBackendException
            assertNotNull(ex, "expected NoCompileBackendException for $unsupported")
            assertEquals(unsupported, ex.target)
        }

        // Linux cross targets build with zig cc (#155); a missing zig is refused naming it
        assertTrue(compiler.checkSupport("x86_64-unknown-linux-gnu", abi315, headers).isSuccess)
        val noZig = fakeCompiler(targetCompiler = { null })
            .checkSupport("aarch64-unknown-linux-musl", abi315, headers).exceptionOrNull() as? NoCompileBackendException
        assertNotNull(noZig)
        assertTrue("zig" in noZig.reason, noZig.reason)

        // Missing Python.h
        val emptyInclude = File(tempDir, "empty-include").apply { mkdirs() }
        val noHeader = compiler.checkSupport("aarch64-apple-darwin", abi315, emptyInclude).exceptionOrNull() as? NoCompileBackendException
        assertNotNull(noHeader)
        assertTrue("Python.h" in noHeader.reason, noHeader.reason)

        // Wrong PY_VERSION
        val headers314 = fakeHeaders(File(tempDir, "inc314"), pyVersion = "3.14.2")
        val wrongVer = compiler.checkSupport("aarch64-apple-darwin", abi315, headers314).exceptionOrNull() as? NoCompileBackendException
        assertNotNull(wrongVer)
        assertTrue("3.14" in wrongVer.reason && "3.15" in wrongVer.reason, wrongVer.reason)

        // Missing Py_GIL_DISABLED
        val gilHeaders = fakeHeaders(File(tempDir, "incGil"), gilDisabled = false)
        val gilEx = compiler.checkSupport("aarch64-apple-darwin", abi315, gilHeaders).exceptionOrNull() as? NoCompileBackendException
        assertNotNull(gilEx)
        assertTrue("Py_GIL_DISABLED" in gilEx.reason, gilEx.reason)

        // Missing toolchain
        val noClang = fakeCompiler(clangBin = null)
        val noToolEx = noClang.checkSupport("aarch64-apple-darwin", abi315, headers).exceptionOrNull() as? NoCompileBackendException
        assertNotNull(noToolEx)
        assertEquals(NativeToolchain.CLANG, noToolEx.toolchain)

        // Android requires libDir and libraryName and the file inside libDir
        val ndk = fakeNdk()
        val androidLib = fakeAndroidLib()
        val androidCompiler = fakeCompiler(ndkDir = ndk)
        assertTrue(
            androidCompiler.checkSupport(
                "aarch64-linux-android",
                abi315,
                headers,
                libDir = androidLib,
                libraryName = "libpython3.15t.so",
            ).isSuccess,
        )
        val noLibEx =
            androidCompiler.checkSupport("aarch64-linux-android", abi315, headers).exceptionOrNull() as? NoCompileBackendException
        assertNotNull(noLibEx)
        assertTrue("libDir" in noLibEx.reason, noLibEx.reason)
    }

    /** #155: the targets beyond macOS, the Linux host and Android arm64, accepted where their toolchain can run. */
    @Test
    fun checkSupport_acceptsWindowsOnItsOwnMachineIosWithItsFrameworkAndX86Android() {
        val headers = fakeHeaders()
        val winLibs = File(tempDir, "win-libs").apply { mkdirs(); File(this, "python315t.lib").writeText("lib") }

        val onWindows = fakeCompiler(hostTarget = "x86_64-pc-windows-msvc", windowsMachine = "x86_64-pc-windows-msvc")
        assertTrue(onWindows.checkSupport("x86_64-pc-windows-msvc", abi315, headers, winLibs, "python315t.lib").isSuccess)
        val otherArch = onWindows.checkSupport("aarch64-pc-windows-msvc", abi315, headers, winLibs, "python315t.lib").exceptionOrNull() as? NoCompileBackendException
        assertNotNull(otherArch)
        assertTrue("owner decision 2026-11-19" in otherArch.reason, otherArch.reason)
        val noLib = onWindows.checkSupport("x86_64-pc-windows-msvc", abi315, headers).exceptionOrNull() as? NoCompileBackendException
        assertNotNull(noLib)
        assertTrue("python3XXt.lib" in noLib.reason, noLib.reason)

        val slice = File(tempDir, "ios-arm64").apply { File(this, "Python.framework").mkdirs() }
        val onMac = fakeCompiler(hostTarget = "aarch64-apple-darwin")
        for (target in listOf("arm64-apple-ios", "arm64-apple-ios-simulator", "x86_64-apple-ios-simulator")) {
            assertTrue(onMac.checkSupport(target, abi315, headers, slice).isSuccess, target)
        }
        val iosOnLinux = fakeCompiler(hostTarget = "x86_64-unknown-linux-gnu")
            .checkSupport("arm64-apple-ios", abi315, headers, slice).exceptionOrNull() as? NoCompileBackendException
        assertNotNull(iosOnLinux)
        assertEquals(NativeToolchain.XCODE, iosOnLinux.toolchain)

        val androidLib = fakeAndroidLib()
        assertTrue(
            fakeCompiler(ndkDir = fakeNdk()).checkSupport("x86_64-linux-android", abi315, headers, androidLib, "libpython3.15t.so").isSuccess,
        )
    }

    /** The compile and link commands each new target gets (#155). */
    @Test
    fun compile_buildsEachNewTargetWithItsCompilerFlagsAndLink() {
        val headers = fakeHeaders()
        val cFile = File(tempDir, "src/k.c").apply { parentFile.mkdirs(); writeText("#include <Python.h>\n") }
        val winLibs = File(tempDir, "win-libs").apply { mkdirs(); File(this, "python315t.lib").writeText("lib") }
        val slice = File(tempDir, "ios-slice").apply { File(this, "Python.framework").mkdirs() }

        fun build(target: String, host: String, windows: String? = null, libDir: File? = null, lib: String? = null, suffix: String, ndk: File? = null): List<RecordedCall> {
            val calls = mutableListOf<RecordedCall>()
            val pkg = File(tempDir, "pkg-$target").apply { mkdirs() }
            fakeCompiler(hostTarget = host, windowsMachine = windows, calls = calls, ndkDir = ndk).compile(
                ExtensionCompileRequest(
                    modules = listOf(ExtensionModuleSource("app.k", listOf(cFile))),
                    target = target, pythonAbi = abi315, buildType = "debug", workingDir = pkg,
                    buildDir = File(pkg, "work"), outputDir = File(pkg, "out"), includeDir = headers,
                    extensionSuffix = suffix, libDir = libDir, libraryName = lib,
                ),
            ).getOrThrow()
            return calls
        }

        val win = build("x86_64-pc-windows-msvc", "x86_64-pc-windows-msvc", windows = "x86_64-pc-windows-msvc", libDir = winLibs, lib = "python315t.lib", suffix = ".cp315t-win_amd64.pyd")
        assertEquals(listOf("clang", "-c", "--target=x86_64-pc-windows-msvc"), win[0].command.take(3))
        assertFalse("-fPIC" in win[0].command, "clang refuses -fPIC for windows-msvc")
        assertTrue(win[1].command.containsAll(listOf("-shared", "-L${winLibs.path}", "-lpython315t")), win[1].command.toString())
        assertTrue(win[1].command.last().endsWith("app/k.cp315t-win_amd64.pyd"))
        assertEquals(2, win.size, "no strip on Windows")

        val ios = build("arm64-apple-ios-simulator", "aarch64-apple-darwin", libDir = slice, suffix = ".cpython-315t-iphonesimulator.so")
        assertEquals(listOf("xcrun", "--sdk", "iphonesimulator", "clang", "-c", "-target", "arm64-apple-ios13.0-simulator"), ios[0].command.take(7))
        assertTrue(ios[1].command.containsAll(listOf("-dynamiclib", "-F${slice.path}", "-framework", "Python")), ios[1].command.toString())

        val musl = build("aarch64-unknown-linux-musl", "x86_64-unknown-linux-gnu", suffix = ".cpython-315t-aarch64-linux-musl.so")
        assertEquals(listOf("zig", "cc", "-target", "aarch64-linux-musl", "-c"), musl[0].command.take(5))
        assertTrue(musl[1].command.containsAll(listOf("-shared", "-fPIC")), musl[1].command.toString())

        val x86Android = build("x86_64-linux-android", "aarch64-apple-darwin", libDir = fakeAndroidLib(File(tempDir, "alib2")), lib = "libpython3.15t.so", suffix = ".cpython-315t-x86_64-linux-android.so", ndk = fakeNdk(File(tempDir, "ndk2")))
        assertTrue(x86Android[0].command.first().contains("x86_64-linux-android24-clang"), x86Android[0].command.first())
    }

    @Test
    fun compile_buildsMacosExtensionWithExpectedFlagsAndCachesViaDepfile() {
        val headers = fakeHeaders()
        val pkgDir = File(tempDir, "pkg").apply { mkdirs() }
        val runtimeInclude = File(tempDir, "runtime").apply { mkdirs() }
        val tpHeader = File(runtimeInclude, "tp_runtime.h").apply { writeText("/* v1 */\n") }
        val cFile = File(tempDir, "cache/nbody.c").apply {
            parentFile.mkdirs()
            writeText("#include <Python.h>\n#include \"tp_runtime.h\"\n")
        }
        val calls = mutableListOf<RecordedCall>()
        val compiler =
            fakeCompiler(
                calls = calls,
                onRun = { call ->
                    val outIdx = call.command.indexOf("-o")
                    if (outIdx >= 0) {
                        val outFile = File(call.command[outIdx + 1])
                        outFile.parentFile?.mkdirs()
                        outFile.writeText("built:${tpHeader.readText()}")
                        val mfIdx = call.command.indexOf("-MF")
                        if (mfIdx >= 0) {
                            File(call.command[mfIdx + 1]).writeText(
                                "${outFile.path}: \\\n  ${cFile.path} \\\n  ${File(headers, "Python.h").path} \\\n  ${tpHeader.path}\n",
                            )
                        }
                    }
                    CompileProcessResult(0, "")
                },
            )

        val buildDir = File(pkgDir, "build/work")
        val outDir1 = File(pkgDir, "build/out1")
        val request =
            ExtensionCompileRequest(
                modules =
                    listOf(
                        ExtensionModuleSource(
                            moduleName = "app.physics.nbody",
                            sources = listOf(cFile),
                            includeDirs = listOf(runtimeInclude),
                            defines = mapOf("MOD_DEF" to "1", "FLAG_ONLY" to null),
                        ),
                    ),
                target = "macos",
                pythonAbi = abi315,
                buildType = "release",
                workingDir = pkgDir,
                buildDir = buildDir,
                outputDir = outDir1,
                includeDir = headers,
                extensionSuffix = ".abi3t-darwin.so",
                options =
                    ExtensionCompileOptions(
                        includeDirs = listOf(runtimeInclude),
                        cFlags = listOf("-Wcustom"),
                        defines = mapOf("GLOBAL_DEF" to "yes"),
                        linkFlags = listOf("-Wl,-custom"),
                    ),
            )

        val res1 = compiler.compile(request).getOrThrow()
        assertEquals("aarch64-apple-darwin", res1.target)
        assertEquals(NativeToolchain.CLANG, res1.toolchain)
        assertEquals(listOf(CompiledExtension("app.physics.nbody", "app/physics/nbody.abi3t-darwin.so")), res1.modules)
        assertEquals("Apple clang version 17.0.0", res1.toolVersions["cc"])
        assertTrue(File(outDir1, "app/physics/nbody.abi3t-darwin.so").isFile)

        // Compile + link + strip on release
        assertEquals(3, calls.size, calls.map { it.command }.toString())
        val compileCmd = calls[0].command
        assertTrue(compileCmd.containsAll(listOf("-c", "-O2", "-DNDEBUG", "-ffp-contract=off", "-fno-fast-math", "-fPIC")), "$compileCmd")
        assertTrue(compileCmd.containsAll(listOf("-arch", "arm64")), "$compileCmd")
        assertTrue("-ffile-prefix-map=${pkgDir.canonicalFile.path}=." in compileCmd, "$compileCmd")
        assertTrue("-I${headers.canonicalFile.path}" in compileCmd, "$compileCmd")
        assertTrue("-I${runtimeInclude.canonicalFile.path}" in compileCmd, "$compileCmd")
        assertTrue("-DGLOBAL_DEF=yes" in compileCmd && "-DMOD_DEF=1" in compileCmd && "-DFLAG_ONLY" in compileCmd, "$compileCmd")
        assertEquals("-Wcustom", compileCmd.filter { !it.startsWith("-o") && !it.endsWith(".o") && !it.endsWith(".c") && !it.endsWith(".d") && it != "-MF" && it != "-MMD" }.last())

        val linkCmd = calls[1].command
        assertTrue(linkCmd.containsAll(listOf("-bundle", "-undefined", "dynamic_lookup", "-Wl,-custom")), "$linkCmd")
        assertEquals("1", calls[1].env["ZERO_AR_DATE"])

        // Second compile with identical inputs and headers hits the object cache (no -c call)
        calls.clear()
        val outDir2 = File(pkgDir, "build/out2")
        compiler.compile(request.copy(outputDir = outDir2)).getOrThrow()
        assertTrue(calls.none { "-c" in it.command }, "expected object cache hit, got: ${calls.map { it.command }}")
        assertTrue(File(outDir2, "app/physics/nbody.abi3t-darwin.so").isFile)

        // Modifying tp_runtime.h listed in the depfile invalidates the cache and recompiles
        tpHeader.writeText("/* v2 */\n")
        calls.clear()
        val outDir3 = File(pkgDir, "build/out3")
        compiler.compile(request.copy(outputDir = outDir3)).getOrThrow()
        assertTrue(calls.any { "-c" in it.command }, "expected header edit to invalidate object cache")
    }

    @Test
    fun compile_androidUsesNdkClangWithMinSdkAndLinksLibpython() {
        val headers = fakeHeaders()
        val ndk = fakeNdk()
        val androidLib = fakeAndroidLib()
        val pkgDir = File(tempDir, "pkg").apply { mkdirs() }
        val cFile = File(tempDir, "mod.c").apply { writeText("int x;\n") }
        val calls = mutableListOf<RecordedCall>()
        val compiler = fakeCompiler(ndkDir = ndk, calls = calls)

        val result =
            compiler.compile(
                ExtensionCompileRequest(
                    modules = listOf(ExtensionModuleSource("app.mod", listOf(cFile))),
                    target = "aarch64-linux-android",
                    pythonAbi = abi315,
                    buildType = "debug",
                    workingDir = pkgDir,
                    buildDir = File(pkgDir, "build/work"),
                    outputDir = File(pkgDir, "build/out"),
                    includeDir = headers,
                    extensionSuffix = ".cpython-315t-aarch64-linux-android.so",
                    libDir = androidLib,
                    libraryName = "libpython3.15t.so",
                    minSdk = 28,
                ),
            ).getOrThrow()

        assertEquals(NativeToolchain.NDK, result.toolchain)
        assertEquals(2, calls.size, "debug build runs compile + link without strip")
        assertTrue(calls[0].command.first().endsWith("aarch64-linux-android28-clang"), "${calls[0].command}")
        assertTrue(calls[0].command.containsAll(listOf("-O0", "-g", "-ffp-contract=off", "-fno-fast-math", "-fPIC")), "${calls[0].command}")
        assertTrue(calls[1].command.containsAll(listOf("-shared", "-fPIC", "-L${androidLib.canonicalFile.path}", "-lpython3.15t")), "${calls[1].command}")
    }

    @Test
    fun compile_attemptsEveryModuleAndReportsAllFailuresTogether() {
        val headers = fakeHeaders()
        val pkgDir = File(tempDir, "pkg").apply { mkdirs() }
        val c1 = File(tempDir, "a.c").apply { writeText("bad1") }
        val c2 = File(tempDir, "b.c").apply { writeText("bad2") }
        val compiler =
            fakeCompiler(
                onRun = { call ->
                    CompileProcessResult(exitCode = 1, output = "error in ${call.command.last()}")
                },
            )

        val result =
            compiler.compile(
                ExtensionCompileRequest(
                    modules =
                        listOf(
                            ExtensionModuleSource("app.a", listOf(c1)),
                            ExtensionModuleSource("app.b", listOf(c2)),
                        ),
                    target = "aarch64-apple-darwin",
                    pythonAbi = abi315,
                    buildType = "debug",
                    workingDir = pkgDir,
                    buildDir = File(pkgDir, "build/work"),
                    outputDir = File(pkgDir, "build/out"),
                    includeDir = headers,
                    extensionSuffix = ".abi3t-darwin.so",
                ),
            )

        val ex = result.exceptionOrNull() as? ModuleCompileException
        assertNotNull(ex)
        assertEquals(2, ex.failures.size)
        assertEquals(listOf("app.a", "app.b"), ex.failures.map { it.moduleName })
        assertTrue(ex.failures.all { it.stage == CompileStage.C_COMPILE && it.exitCode == 1 })
    }

    @Test
    fun compile_realHostClangBuildsLoadableSharedObjectFromSyntheticHeaders() {
        val host = org.thisisthepy.python.multiplatform.packpack.utils.Platforms.detectHostTarget()
        assumeTrue(host.contains("apple-darwin") || host.contains("linux-gnu"))
        val headers = fakeHeaders()
        File(headers, "Python.h").writeText(
            """
            #include "patchlevel.h"
            #include "pyconfig.h"
            typedef struct _object { int ob_refcnt; } PyObject;
            """.trimIndent() + "\n",
        )
        val cFile = File(tempDir, "real_mod.c").apply {
            writeText(
                """
                #include <Python.h>
                PyObject* PyInit_real_mod(void) { return (PyObject*)0; }
                """.trimIndent() + "\n",
            )
        }
        val pkgDir = File(tempDir, "real-pkg").apply { mkdirs() }
        val outDir = File(pkgDir, "out")
        val compiler = ExtensionCompilerInterface.create()
        assumeTrue(compiler.checkSupport(host, abi315, headers).isSuccess)

        val res =
            compiler.compile(
                ExtensionCompileRequest(
                    modules = listOf(ExtensionModuleSource("app.real_mod", listOf(cFile))),
                    target = host,
                    pythonAbi = abi315,
                    buildType = "release",
                    workingDir = pkgDir,
                    buildDir = File(pkgDir, "work"),
                    outputDir = outDir,
                    includeDir = headers,
                    extensionSuffix = ".abi3t-darwin.so",
                ),
            ).getOrThrow()

        val built = File(outDir, "app/real_mod.abi3t-darwin.so")
        assertTrue(built.isFile && built.length() > 0, "expected real shared object at $built")
        assertTrue(res.toolVersions["cc"].orEmpty().isNotBlank(), "${res.toolVersions}")
    }
}
