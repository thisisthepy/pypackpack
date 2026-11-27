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
 * the single wheel bundler packs it as `cp315-cp315t-pyemscripten_2026_0_wasm32`.
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
                static PyObject *answer(PyObject *self, PyObject *args) { return PyLong_FromLong(42); }
                static PyMethodDef methods[] = {{"answer", answer, METH_NOARGS, NULL}, {NULL, NULL, 0, NULL}};
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
        File(site, "wasmdemo/__init__.py").writeText("")

        val wheel = BundlerInterface.create(BundleType.SINGLE)
            .bundle(BundleRequest(packageDir = pkg, target = "wasm32-emscripten", buildType = "release", buildLevel = "native"))
            .getOrThrow().artifactFile!!
        assertEquals("wasmdemo-0.1.0-cp315-cp315t-pyemscripten_2026_0_wasm32.whl", wheel.name)
        val entries = ZipFile(wheel).use { zip -> zip.entries().asSequence().map { it.name }.toSet() }
        assertTrue("wasmdemo/wmod.cpython-315t-wasm32-emscripten.so" in entries, "$entries")
    }
}
