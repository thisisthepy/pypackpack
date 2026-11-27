package org.thisisthepy.python.multiplatform.packpack.dependency.backend

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.thisisthepy.python.multiplatform.packpack.utils.ArchiveWriter
import org.thisisthepy.python.multiplatform.packpack.utils.Platforms
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The completion criterion of the cross build environment (docs/SPEC.md *Cross build environment*, #159): an
 * sdist-only C package builds for `aarch64-linux-android`, its extension carries the Android suffix and is an
 * aarch64 ELF. Once per build backend: setuptools (the criterion), meson-python and scikit-build-core. Then the
 * other cross families with setuptools: aarch64 Linux (`zig cc`, `manylinux_2_17`) on a Linux host and the arm64
 * iOS simulator (Xcode) on a macOS host.
 *
 * Needs the target's toolchain (the Android NDK, `uv` for zig, Xcode), `cmake` and network access (the host and
 * target CPython, the backends), so it runs only with `PPP_CROSS_SDIST_REAL_BUILD=1` (pypackpack test.yml's
 * `real-builds` and `real-builds-host` jobs); a test whose host cannot build its target is skipped. The tests of a
 * target share its CPython and cross environment. Everything is under this module's `build/`.
 */
class CrossSdistRealBuildTest {
    private val root = File("build/cross-sdist-real").absoluteFile
    private val pkg = File(root, "pkg")

    private val fastC =
        "#include <Python.h>\n" +
            "static PyModuleDef_Slot slots[] = {{Py_mod_gil, Py_MOD_GIL_NOT_USED}, {0, NULL}};\n" +
            "static struct PyModuleDef def = {PyModuleDef_HEAD_INIT, \"_fast\", NULL, 0, NULL, slots};\n" +
            "PyMODINIT_FUNC PyInit__fast(void) { return PyModuleDef_Init(&def); }\n"

    /** Skips unless opted in on a [hostFamily] host, then installs [target]'s CPython into [installDir] once. */
    private fun setUp(target: String, installDir: String, hostFamily: String) {
        assumeTrue(System.getenv("PPP_CROSS_SDIST_REAL_BUILD") == "1", "set PPP_CROSS_SDIST_REAL_BUILD=1 to build for real")
        assumeTrue(Platforms.getPlatformFamily(Platforms.detectHostTarget()) == hostFamily, "$target builds on a $hostFamily host here")
        pkg.mkdirs()
        File(pkg, "pyproject.toml").writeText("[project]\nname = \"app\"\nversion = \"0.1.0\"\n")
        if (!File(root, installDir).isDirectory) {
            runBlocking { UVBackend().installPython("3.15t", target, File(root, installDir)) }.getOrThrow()
        }
    }

    private fun setUpAndroid() = setUp("aarch64-linux-android", "android_arm64", "linux")

    private fun assertAarch64Elf(header: ByteArray) {
        assertEquals(listOf(0x7f, 'E'.code, 'L'.code, 'F'.code), header.take(4).map { it.toInt() and 0xff })
        assertEquals(183, (header[18].toInt() and 0xff) or ((header[19].toInt() and 0xff) shl 8), "e_machine must be EM_AARCH64")
    }

    private fun setuptoolsProject(name: String) = mapOf(
        "pyproject.toml" to "[build-system]\nrequires = [\"setuptools>=70\"]\nbuild-backend = \"setuptools.build_meta\"\n\n" +
            "[project]\nname = \"$name\"\nversion = \"1.0\"\n",
        "setup.py" to "from setuptools import Extension, setup\n" +
            "setup(ext_modules=[Extension(\"$name._fast\", [\"src/fast.c\"])], packages=[\"$name\"])\n",
    )

    /**
     * Writes the project [files] as `<name>-1.0.tar.gz`, builds it for [target] and checks the wheel's tag ends with
     * [tagSuffix], its extension is `_fast<extSuffix>`, and [checkBinary] accepts the extension's first 20 bytes.
     */
    private fun buildAndCheck(
        name: String,
        files: Map<String, String>,
        target: String = "aarch64-linux-android",
        tagSuffix: String = "-cp315t-android_24_arm64_v8a.whl",
        extSuffix: String = ".cpython-315t-aarch64-linux-android.so",
        checkBinary: (ByteArray) -> Unit = ::assertAarch64Elf,
    ) {
        val tree = File(root, "tree-$name").apply { deleteRecursively(); mkdirs() }
        files.forEach { (path, text) -> File(tree, path).apply { parentFile.mkdirs(); writeText(text) } }
        File(tree, "src/fast.c").apply { parentFile.mkdirs(); writeText(fastC) }
        File(tree, "$name/__init__.py").apply { parentFile.mkdirs(); writeText("") }
        val sdist = File(root, "$name-1.0.tar.gz").apply { delete() }
        ArchiveWriter.writeTarGz(tree, "$name-1.0", sdist)

        val wheel = CrossSdistBuilder().buildSdist(pkg, target, sdist, name).getOrThrow()

        assertTrue(wheel.name.startsWith("$name-1.0-") && wheel.name.endsWith(tagSuffix), wheel.name)
        val so = File(root, "unpacked-$name.so")
        ZipFile(wheel).use { zip ->
            val entry = zip.entries().asSequence().single { it.name.startsWith("$name/_fast.") }
            assertEquals("$name/_fast$extSuffix", entry.name)
            zip.getInputStream(entry).use { input -> so.outputStream().use { input.copyTo(it) } }
        }
        checkBinary(so.inputStream().use { it.readNBytes(20) })
    }

    @Test
    fun `a setuptools sdist builds for aarch64 Android with the target's suffix`() {
        setUpAndroid()
        buildAndCheck("stdemo", setuptoolsProject("stdemo"))
    }

    @Test
    fun `a setuptools sdist builds for aarch64 Linux with zig as manylinux_2_17`() {
        setUp("aarch64-unknown-linux-gnu", "linux_arm64", "linux")
        buildAndCheck(
            "lxdemo", setuptoolsProject("lxdemo"), target = "aarch64-unknown-linux-gnu",
            tagSuffix = "-cp315t-manylinux_2_17_aarch64.whl", extSuffix = ".cpython-315t-aarch64-linux-gnu.so",
        )
    }

    @Test
    fun `a setuptools sdist builds for the arm64 iOS simulator with Xcode`() {
        setUp("arm64-apple-ios-simulator", "ios_simulator_arm64", "macos")
        buildAndCheck(
            "iodemo", setuptoolsProject("iodemo"), target = "arm64-apple-ios-simulator",
            tagSuffix = "-cp315t-ios_13_0_arm64_iphonesimulator.whl", extSuffix = ".cpython-315t-iphonesimulator.so",
        ) { header ->
            assertEquals(listOf(0xcf, 0xfa, 0xed, 0xfe), header.take(4).map { it.toInt() and 0xff }, "MH_MAGIC_64")
            val cpu = (header[4].toInt() and 0xff) or ((header[5].toInt() and 0xff) shl 8) or ((header[6].toInt() and 0xff) shl 16) or ((header[7].toInt() and 0xff) shl 24)
            assertEquals(0x0100000c, cpu, "CPU_TYPE_ARM64")
        }
    }

    /**
     * #82: a setuptools sdist builds for wasm32-emscripten in the cross environment with Emscripten 5.0.3, tagged as
     * the 3.15t wasm runtime's sysconfig is (`pyemscripten_2026_0_wasm32`), its extension a wasm side module. Needs
     * emsdk and the runtime (interim: `PPP_WASM_RUNTIME_MANIFEST`), so besides `PPP_CROSS_SDIST_REAL_BUILD` it runs only
     * with `PPP_WASM_REAL_BUILD=1`, which CI does not set until the runtime is published (team lead 2026-11-27).
     */
    @Test
    fun `a setuptools sdist builds for wasm32-emscripten with emcc as a pyemscripten side module`() {
        assumeTrue(System.getenv("PPP_WASM_REAL_BUILD") == "1", "set PPP_WASM_REAL_BUILD=1 (with EMSDK and PPP_WASM_RUNTIME_MANIFEST) to build for wasm")
        assumeTrue(!System.getenv(PythonDistributions.WASM_RUNTIME_MANIFEST_ENV).isNullOrBlank(), "needs ${PythonDistributions.WASM_RUNTIME_MANIFEST_ENV}")
        setUp("wasm32-emscripten", "wasm32_emscripten", Platforms.getPlatformFamily(Platforms.detectHostTarget()))
        buildAndCheck(
            "wsdemo", setuptoolsProject("wsdemo"), target = "wasm32-emscripten",
            tagSuffix = "-cp315t-pyemscripten_2026_0_wasm32.whl", extSuffix = ".cpython-315t-wasm32-emscripten.so",
        ) { header ->
            assertEquals(listOf(0, 'a'.code, 's'.code, 'm'.code), header.take(4).map { it.toInt() and 0xff }, "wasm magic")
        }
        val so = File(root, "unpacked-wsdemo.so").readBytes()
        assertTrue(String(so, Charsets.ISO_8859_1).contains("dylink.0"), "not a side module: no dylink.0 section")
    }

    /** #82: the same through meson-python and the Meson cross file (emcc, `system = 'emscripten'`, `-sSIDE_MODULE=1`). */
    @Test
    fun `a meson-python sdist builds for wasm32-emscripten through the Meson cross file`() {
        assumeTrue(System.getenv("PPP_WASM_REAL_BUILD") == "1", "set PPP_WASM_REAL_BUILD=1 (with EMSDK and PPP_WASM_RUNTIME_MANIFEST) to build for wasm")
        assumeTrue(!System.getenv(PythonDistributions.WASM_RUNTIME_MANIFEST_ENV).isNullOrBlank(), "needs ${PythonDistributions.WASM_RUNTIME_MANIFEST_ENV}")
        setUp("wasm32-emscripten", "wasm32_emscripten", Platforms.getPlatformFamily(Platforms.detectHostTarget()))
        buildAndCheck(
            "wmdemo",
            mapOf(
                "pyproject.toml" to "[build-system]\nrequires = [\"meson-python>=0.16\"]\nbuild-backend = \"mesonpy\"\n\n" +
                    "[project]\nname = \"wmdemo\"\nversion = \"1.0\"\n",
                "meson.build" to "project('wmdemo', 'c', version: '1.0')\n" +
                    "py = import('python').find_installation(pure: false)\n" +
                    "py.extension_module('_fast', 'src/fast.c', install: true, subdir: 'wmdemo')\n" +
                    "py.install_sources('wmdemo/__init__.py', subdir: 'wmdemo')\n",
            ),
            target = "wasm32-emscripten",
            tagSuffix = "-cp315t-pyemscripten_2026_0_wasm32.whl", extSuffix = ".cpython-315t-wasm32-emscripten.so",
        ) { header ->
            assertEquals(listOf(0, 'a'.code, 's'.code, 'm'.code), header.take(4).map { it.toInt() and 0xff }, "wasm magic")
        }
        assertTrue(String(File(root, "unpacked-wmdemo.so").readBytes(), Charsets.ISO_8859_1).contains("dylink.0"), "not a side module")
    }

    @Test
    fun `a meson-python sdist builds for aarch64 Android with the target's suffix`() {
        setUpAndroid()
        buildAndCheck(
            "mpdemo",
            mapOf(
                "pyproject.toml" to "[build-system]\nrequires = [\"meson-python>=0.16\"]\nbuild-backend = \"mesonpy\"\n\n" +
                    "[project]\nname = \"mpdemo\"\nversion = \"1.0\"\n",
                "meson.build" to "project('mpdemo', 'c', version: '1.0')\n" +
                    "py = import('python').find_installation(pure: false)\n" +
                    "py.extension_module('_fast', 'src/fast.c', install: true, subdir: 'mpdemo')\n" +
                    "py.install_sources('mpdemo/__init__.py', subdir: 'mpdemo')\n",
            ),
        )
    }

    @Test
    fun `a scikit-build-core sdist builds for aarch64 Android with the target's suffix`() {
        setUpAndroid()
        buildAndCheck(
            "skdemo",
            mapOf(
                "pyproject.toml" to "[build-system]\nrequires = [\"scikit-build-core>=0.10\"]\nbuild-backend = \"scikit_build_core.build\"\n\n" +
                    "[project]\nname = \"skdemo\"\nversion = \"1.0\"\n",
                "CMakeLists.txt" to "cmake_minimum_required(VERSION 3.15...3.31)\n" +
                    "project(\${SKBUILD_PROJECT_NAME} LANGUAGES C)\n" +
                    "find_package(Python COMPONENTS Interpreter Development.Module REQUIRED)\n" +
                    "python_add_library(_fast MODULE src/fast.c WITH_SOABI)\n" +
                    "install(TARGETS _fast DESTINATION skdemo)\n",
            ),
        )
    }

    /** #168: a maturin (PyO3) sdist, cross-compiled by Cargo for the Rust target with the NDK as linker. */
    @Test
    fun `a maturin sdist builds for aarch64 Android with the target's suffix`() {
        setUpAndroid()
        buildAndCheck(
            "rsdemo",
            mapOf(
                "pyproject.toml" to "[build-system]\nrequires = [\"maturin>=1.9,<2\"]\nbuild-backend = \"maturin\"\n\n" +
                    "[project]\nname = \"rsdemo\"\nversion = \"1.0\"\n\n[tool.maturin]\nmodule-name = \"rsdemo._fast\"\n",
                "Cargo.toml" to "[package]\nname = \"rsdemo\"\nversion = \"1.0.0\"\nedition = \"2021\"\n\n" +
                    "[lib]\nname = \"_fast\"\ncrate-type = [\"cdylib\"]\n\n[dependencies]\npyo3 = \"0.30\"\n",
                "src/lib.rs" to "use pyo3::prelude::*;\n\n" +
                    "#[pymodule(gil_used = false)]\nfn _fast(_m: &Bound<'_, PyModule>) -> PyResult<()> { Ok(()) }\n",
            ),
        )
    }

    /**
     * #182: a maturin (Rust, stable) sdist builds for the arm64 iOS simulator on a macOS host: Cargo's
     * `aarch64-apple-ios-sim` target (`rustup target add aarch64-apple-ios-sim`), Xcode's clang, PyO3 reading the slice's
     * lib-arm64, and the link naming Python.framework. Tagged `ios_13_0_arm64_iphonesimulator`; an arm64 Mach-O.
     */
    @Test
    fun `a maturin sdist builds for the arm64 iOS simulator with stable Rust`() {
        setUp("arm64-apple-ios-simulator", "ios_simulator_arm64", "macos")
        buildAndCheck(
            "rsios",
            mapOf(
                "pyproject.toml" to "[build-system]\nrequires = [\"maturin>=1.9,<2\"]\nbuild-backend = \"maturin\"\n\n" +
                    "[project]\nname = \"rsios\"\nversion = \"1.0\"\n\n[tool.maturin]\nmodule-name = \"rsios._fast\"\n",
                "Cargo.toml" to "[package]\nname = \"rsios\"\nversion = \"1.0.0\"\nedition = \"2021\"\n\n" +
                    "[lib]\nname = \"_fast\"\ncrate-type = [\"cdylib\"]\n\n[dependencies]\npyo3 = \"0.30\"\n",
                "src/lib.rs" to "use pyo3::prelude::*;\n\n" +
                    "#[pymodule(gil_used = false)]\nfn _fast(_m: &Bound<'_, PyModule>) -> PyResult<()> { Ok(()) }\n",
            ),
            target = "arm64-apple-ios-simulator",
            tagSuffix = "-ios_13_0_arm64_iphonesimulator.whl", extSuffix = ".cpython-315t-iphonesimulator.so",
        ) { header ->
            assertEquals(listOf(0xcf, 0xfa, 0xed, 0xfe), header.take(4).map { it.toInt() and 0xff }, "MH_MAGIC_64")
            val cpu = (header[4].toInt() and 0xff) or ((header[5].toInt() and 0xff) shl 8) or ((header[6].toInt() and 0xff) shl 16) or ((header[7].toInt() and 0xff) shl 24)
            assertEquals(0x0100000c, cpu, "CPU_TYPE_ARM64")
        }
    }

    /**
     * #182 (team lead 2026-11-27): numpy 2.5.5 from PyPI (meson-python) builds for the arm64 iOS simulator through the
     * Meson cross file with a recipe. A cross build cannot run numpy's long double probe, and Apple arm64's long double
     * is an IEEE double, so the recipe's patch defaults `longdouble_format` to `IEEE_DOUBLE_LE` (Android's is
     * `IEEE_QUAD_LE`). Long (numpy and its build requirements) and on the network, so it also needs
     * `PPP_NUMPY_IOS_REAL_BUILD=1`; `PPP_NUMPY_IOS_NO_RECIPE=1` runs it without the recipe (the red run).
     */
    @Test
    fun `numpy builds for the arm64 iOS simulator with a long double recipe`() {
        assumeTrue(System.getenv("PPP_NUMPY_IOS_REAL_BUILD") == "1", "set PPP_NUMPY_IOS_REAL_BUILD=1 to build numpy for iOS")
        setUp("arm64-apple-ios-simulator", "ios_simulator_arm64", "macos")
        val recipes = File(root, "numpy-ios-recipes").apply { deleteRecursively(); mkdirs() }
        File(recipes, "patches").mkdirs()
        File(recipes, "numpy.toml").writeText("patches = [\"patches/numpy-ios-longdouble.patch\"]\n")
        val q = "'''"
        File(recipes, "patches/numpy-ios-longdouble.patch").writeText(
            "--- a/numpy/_core/meson.build\n+++ b/numpy/_core/meson.build\n@@ -502,7 +502,7 @@\n" +
                " # https://github.com/numpy/numpy/blob/eead09a3d02c09374942cdc787c0b5e4fe9e7472/numpy/core/setup_common.py#L264-L434\n" +
                " # This port is in service of solving gh-23972\n" +
                " # as well as https://github.com/mesonbuild/meson/issues/11068\n" +
                "-longdouble_format = meson.get_external_property('longdouble_format', 'UNKNOWN')\n" +
                "+longdouble_format = meson.get_external_property('longdouble_format', 'IEEE_DOUBLE_LE')\n" +
                " if longdouble_format == 'UNKNOWN'\n   longdouble_format = meson.get_compiler('c').run(\n $q\n",
        )
        val recipeDirs = if (System.getenv("PPP_NUMPY_IOS_NO_RECIPE") == "1") emptyList() else listOf(recipes)

        val wheel = CrossSdistBuilder().buildFromPyPI(pkg, "arm64-apple-ios-simulator", "numpy==2.5.5", recipeDirs).getOrThrow()

        assertTrue(wheel.name.startsWith("numpy-2.5.5-cp315-cp315t-ios_13_0_arm64_iphonesimulator"), wheel.name)
        ZipFile(wheel).use { zip ->
            val entry = zip.getEntry("numpy/_core/_multiarray_umath.cpython-315t-iphonesimulator.so")
            assertTrue(entry != null, "no numpy/_core/_multiarray_umath.cpython-315t-iphonesimulator.so in ${wheel.name}")
            val header = zip.getInputStream(entry).use { it.readNBytes(8) }
            assertEquals(listOf(0xcf, 0xfa, 0xed, 0xfe), header.take(4).map { it.toInt() and 0xff }, "MH_MAGIC_64")
        }
    }
}
