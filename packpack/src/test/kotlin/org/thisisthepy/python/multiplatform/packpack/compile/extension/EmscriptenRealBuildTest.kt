package org.thisisthepy.python.multiplatform.packpack.compile.extension

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import org.thisisthepy.python.multiplatform.packpack.bundle.BundleRequest
import org.thisisthepy.python.multiplatform.packpack.bundle.BundleType
import org.thisisthepy.python.multiplatform.packpack.bundle.BundlerInterface
import org.thisisthepy.python.multiplatform.packpack.compile.backend.external.targetPythonIn
import org.thisisthepy.python.multiplatform.packpack.dependency.backend.UVBackend
import org.thisisthepy.python.multiplatform.packpack.dependency.backend.PythonDistributions
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #82, the real build: a C extension compiled by Emscripten [org.thisisthepy.python.multiplatform.packpack.utils.Platforms.EMSDK_VERSION]
 * against the installed 3.15t wasm runtime's headers is a wasm side module (`\0asm`, a `dylink.0` section), and
 * imports the runtime's `__c_longjmp` tag (its `setjmp`/`longjmp` under `-sSUPPORT_LONGJMP=wasm`), and the single wheel
 * bundler packs it as `cp315-cp315t-pyemscripten_2026_0_wasm32`. With `PPP_WASM_WHEEL_OUT` the wheel is kept there.
 *
 * It needs emsdk and python-multiplatform's wasm runtime, so it runs only with `PPP_WASM_REAL_BUILD=1`, `EMSDK`, and
 * `PPP_WASM_RUNTIME_MANIFEST` (interim (c): `.github/scripts/wasm/pack_pmp_runtime.py`). The team lead (2026-11-27)
 * keeps it off in CI until python-multiplatform publishes the runtime (design (a)): CI does not build the runtime.
 * Loading the module into the runtime is python-multiplatform's `WasmCompiledWheelTest`.
 */
class EmscriptenRealBuildTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun aCExtensionBuildsIntoAPyemscriptenSideModuleWheel() {
        assumeTrue(System.getenv("PPP_WASM_REAL_BUILD") == "1", "set PPP_WASM_REAL_BUILD=1 (with EMSDK and PPP_WASM_RUNTIME_MANIFEST) to build for real")
        assumeTrue(!System.getenv(PythonDistributions.WASM_RUNTIME_MANIFEST_ENV).isNullOrBlank(), "needs ${PythonDistributions.WASM_RUNTIME_MANIFEST_ENV}")

        val runtime = File(tempDir, "wasm32_emscripten")
        runBlocking { UVBackend().installPython("3.15t", "wasm32-emscripten", runtime) }.getOrThrow()
        val python = targetPythonIn(runtime, "wasm32-emscripten")

        val pkg = File(tempDir, "wasmdemo").apply { mkdirs() }
        File(pkg, "pyproject.toml").writeText("[project]\nname = \"wasmdemo\"\nversion = \"0.1.0\"\n")
        val source = File(tempDir, "wmod.c").apply {
            writeText(
                """
                #include <Python.h>
                #include <setjmp.h>
                static PyObject *answer(PyObject *self, PyObject *args) { return PyLong_FromLong(42); }
                /* setjmp/longjmp under -sSUPPORT_LONGJMP=wasm: the module imports the runtime's __c_longjmp tag. */
                static jmp_buf env;
                static void jump(int value) { longjmp(env, value); }
                static PyObject *unwind(PyObject *self, PyObject *args) {
                    int value = setjmp(env);
                    if (value == 0) jump(7);
                    return PyLong_FromLong(value);
                }
                static PyMethodDef methods[] = {
                    {"answer", answer, METH_NOARGS, NULL},
                    {"unwind", unwind, METH_NOARGS, NULL},
                    {NULL, NULL, 0, NULL},
                };
                static struct PyModuleDef def = {PyModuleDef_HEAD_INIT, "wmod", NULL, -1, methods};
                PyMODINIT_FUNC PyInit_wmod(void) {
                    PyObject *m = PyModule_Create(&def);
                #ifdef Py_GIL_DISABLED
                    if (m) PyUnstable_Module_SetGIL(m, Py_MOD_GIL_NOT_USED);
                #endif
                    return m;
                }
                """.trimIndent() + "\n",
            )
        }
        val site = File(pkg, "dist/wasm32-emscripten/release/native/usr/local/lib/python3.15t/site-packages")
        val result = ExtensionCompilerInterface.create().compile(
            ExtensionCompileRequest(
                modules = listOf(ExtensionModuleSource("wasmdemo.wmod", listOf(source))),
                target = "wasm32-emscripten",
                pythonAbi = PythonAbi(version = "3.15"),
                buildType = "release",
                workingDir = pkg,
                buildDir = File(pkg, "build/emcc"),
                outputDir = site,
                includeDir = python.includeDir,
                extensionSuffix = python.extensionSuffix,
            ),
        ).getOrThrow()

        val module = File(site, result.modules.single().relativePath)
        val bytes = module.readBytes()
        assertEquals("\u0000asm", String(bytes.copyOfRange(0, 4), Charsets.ISO_8859_1), "$module is not wasm")
        assertTrue(String(bytes, Charsets.ISO_8859_1).contains("dylink.0"), "$module has no dylink.0 section: not a side module")
        assertTrue(String(bytes, Charsets.ISO_8859_1).contains("__c_longjmp"), "$module does not import __c_longjmp: not on the runtime's unwinding ABI")
        File(site, "wasmdemo/__init__.py").writeText("")

        val wheel = BundlerInterface.create(BundleType.SINGLE)
            .bundle(BundleRequest(packageDir = pkg, target = "wasm32-emscripten", buildType = "release", buildLevel = "native"))
            .getOrThrow().artifactFile!!
        assertEquals("wasmdemo-0.1.0-cp315-cp315t-pyemscripten_2026_0_wasm32.whl", wheel.name)
        val entries = ZipFile(wheel).use { zip -> zip.entries().asSequence().map { it.name }.toSet() }
        assertTrue("wasmdemo/wmod.cpython-315t-wasm32-emscripten.so" in entries, "$entries")
        // The handoff to python-multiplatform's WasmCompiledWheelTest: keep the wheel when asked to.
        System.getenv("PPP_WASM_WHEEL_OUT")?.takeIf { it.isNotBlank() }?.let { out ->
            wheel.copyTo(File(out, wheel.name).apply { parentFile.mkdirs() }, overwrite = true)
        }
    }

    /**
     * The other tag python.wasm exports for the unwinding ABI (team lead 2026-11-27: cover both): a C++ module that
     * throws and catches a `std::runtime_error` under `-fwasm-exceptions` imports `__cpp_exception` (and libc++abi's
     * `__cxa_throw`, which python.wasm provides when the module loads). The same adapter compiles `.cpp` sources.
     * Checked by hand in the runtime with its build tree's python.sh: `wcpp.catch_it()` returned 'boom 3'.
     */
    @Test
    fun aCppExtensionUsesTheRuntimesCppExceptionTag() {
        assumeTrue(System.getenv("PPP_WASM_REAL_BUILD") == "1", "set PPP_WASM_REAL_BUILD=1 (with EMSDK and PPP_WASM_RUNTIME_MANIFEST) to build for real")
        assumeTrue(!System.getenv(PythonDistributions.WASM_RUNTIME_MANIFEST_ENV).isNullOrBlank(), "needs ${PythonDistributions.WASM_RUNTIME_MANIFEST_ENV}")

        val runtime = File(tempDir, "wasm32_emscripten")
        runBlocking { UVBackend().installPython("3.15t", "wasm32-emscripten", runtime) }.getOrThrow()
        val python = targetPythonIn(runtime, "wasm32-emscripten")
        val pkg = File(tempDir, "wasmcpp").apply { mkdirs() }
        File(pkg, "pyproject.toml").writeText("[project]\nname = \"wasmcpp\"\nversion = \"0.1.0\"\n")
        val source = File(tempDir, "wcpp.cpp").apply {
            writeText(
                """
                #include <Python.h>
                #include <stdexcept>
                #include <string>
                static std::string thrower(int value) {
                    if (value) throw std::runtime_error("boom " + std::to_string(value));
                    return "no throw";
                }
                static PyObject *catch_it(PyObject *, PyObject *) {
                    try {
                        thrower(3);
                    } catch (const std::runtime_error &error) {
                        return PyUnicode_FromString(error.what());
                    }
                    return PyUnicode_FromString("not caught");
                }
                static PyMethodDef methods[] = {{"catch_it", catch_it, METH_NOARGS, nullptr}, {nullptr, nullptr, 0, nullptr}};
                static struct PyModuleDef def = {PyModuleDef_HEAD_INIT, "wcpp", nullptr, -1, methods};
                PyMODINIT_FUNC PyInit_wcpp(void) {
                    PyObject *m = PyModule_Create(&def);
                #ifdef Py_GIL_DISABLED
                    if (m) PyUnstable_Module_SetGIL(m, Py_MOD_GIL_NOT_USED);
                #endif
                    return m;
                }
                """.trimIndent() + "\n",
            )
        }
        val site = File(pkg, "dist/wasm32-emscripten/release/native/usr/local/lib/python3.15t/site-packages")
        val result = ExtensionCompilerInterface.create().compile(
            ExtensionCompileRequest(
                modules = listOf(ExtensionModuleSource("wasmcpp.wcpp", listOf(source))),
                target = "wasm32-emscripten",
                pythonAbi = PythonAbi(version = "3.15"),
                buildType = "release",
                workingDir = pkg,
                buildDir = File(pkg, "build/emcc"),
                outputDir = site,
                includeDir = python.includeDir,
                extensionSuffix = python.extensionSuffix,
            ),
        ).getOrThrow()
        val module = File(site, result.modules.single().relativePath)
        val text = String(module.readBytes(), Charsets.ISO_8859_1)
        assertTrue(text.startsWith("\u0000asm") && "dylink.0" in text, "$module is not a wasm side module")
        assertTrue("__cpp_exception" in text, "$module does not import __cpp_exception: not on the runtime's unwinding ABI")
        File(site, "wasmcpp/__init__.py").writeText("")

        val wheel = BundlerInterface.create(BundleType.SINGLE)
            .bundle(BundleRequest(packageDir = pkg, target = "wasm32-emscripten", buildType = "release", buildLevel = "native"))
            .getOrThrow().artifactFile!!
        assertEquals("wasmcpp-0.1.0-cp315-cp315t-pyemscripten_2026_0_wasm32.whl", wheel.name)
        System.getenv("PPP_WASM_WHEEL_OUT")?.takeIf { it.isNotBlank() }?.let { out ->
            wheel.copyTo(File(out, wheel.name).apply { parentFile.mkdirs() }, overwrite = true)
        }
    }
}
