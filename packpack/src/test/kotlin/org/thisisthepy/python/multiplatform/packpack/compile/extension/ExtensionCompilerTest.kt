package org.thisisthepy.python.multiplatform.packpack.compile.extension

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import org.thisisthepy.python.multiplatform.packpack.compile.backend.external.Clang
import org.thisisthepy.python.multiplatform.packpack.compile.backend.external.NDK
import org.thisisthepy.python.multiplatform.packpack.compile.backend.external.ndkHostTag
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
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
        )
    }

    @Test
    fun checkSupport_acceptsMacosSlicesAndRefusesOutOfScopeTargetsAndBadHeaders() {
        val headers = fakeHeaders()
        val compiler = fakeCompiler(hostTarget = "aarch64-apple-darwin")

        assertTrue(compiler.checkSupport("aarch64-apple-darwin", abi315, headers).isSuccess)
        assertTrue(compiler.checkSupport("x86_64-apple-darwin", abi315, headers).isSuccess)

        // Windows, iOS, wasm, x86_64 Android are refused with NoCompileBackendException
        for (unsupported in listOf("x86_64-pc-windows-msvc", "arm64-apple-ios", "wasm32-pyodide2024", "x86_64-linux-android")) {
            val ex = compiler.checkSupport(unsupported, abi315, headers).exceptionOrNull() as? NoCompileBackendException
            assertNotNull(ex, "expected NoCompileBackendException for $unsupported")
            assertEquals(unsupported, ex.target)
        }

        // Linux cross-arch is refused when host does not match
        val linuxCross =
            compiler.checkSupport("x86_64-unknown-linux-gnu", abi315, headers).exceptionOrNull() as? NoCompileBackendException
        assertNotNull(linuxCross)
        assertTrue("host" in linuxCross.reason.lowercase() || "cross" in linuxCross.reason.lowercase(), linuxCross.reason)

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
