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
 * aarch64 ELF. Once per build backend: setuptools (the criterion), meson-python and scikit-build-core.
 *
 * Needs a Linux host, the Android NDK, `uv`, `cmake` and network access (the host and target CPython, the
 * backends), so it runs only with `PPP_CROSS_SDIST_REAL_BUILD=1` (pypackpack test.yml's `real-builds` job). The
 * tests share the target CPython and the cross environment. Everything is under this module's `build/`.
 */
class CrossSdistRealBuildTest {
    private val root = File("build/cross-sdist-real").absoluteFile
    private val target = "aarch64-linux-android"
    private val pkg = File(root, "pkg")

    private val fastC =
        "#include <Python.h>\n" +
            "static PyModuleDef_Slot slots[] = {{Py_mod_gil, Py_MOD_GIL_NOT_USED}, {0, NULL}};\n" +
            "static struct PyModuleDef def = {PyModuleDef_HEAD_INIT, \"_fast\", NULL, 0, NULL, slots};\n" +
            "PyMODINIT_FUNC PyInit__fast(void) { return PyModuleDef_Init(&def); }\n"

    private fun setUp() {
        assumeTrue(System.getenv("PPP_CROSS_SDIST_REAL_BUILD") == "1", "set PPP_CROSS_SDIST_REAL_BUILD=1 to build for real")
        assumeTrue(Platforms.getPlatformFamily(Platforms.detectHostTarget()) == "linux", "the NDK cross build runs on a Linux host here")
        pkg.mkdirs()
        File(pkg, "pyproject.toml").writeText("[project]\nname = \"app\"\nversion = \"0.1.0\"\n")
        if (!File(root, "android_arm64").isDirectory) {
            runBlocking { UVBackend().installPython("3.15t", target, File(root, "android_arm64")) }.getOrThrow()
        }
    }

    /** Writes the project [files] as `<name>-1.0.tar.gz`, builds it for Android and checks the wheel. */
    private fun buildAndCheck(name: String, files: Map<String, String>) {
        val tree = File(root, "tree-$name").apply { deleteRecursively(); mkdirs() }
        files.forEach { (path, text) -> File(tree, path).apply { parentFile.mkdirs(); writeText(text) } }
        File(tree, "src/fast.c").apply { parentFile.mkdirs(); writeText(fastC) }
        File(tree, "$name/__init__.py").apply { parentFile.mkdirs(); writeText("") }
        val sdist = File(root, "$name-1.0.tar.gz").apply { delete() }
        ArchiveWriter.writeTarGz(tree, "$name-1.0", sdist)

        val wheel = CrossSdistBuilder().buildSdist(pkg, target, sdist, name).getOrThrow()

        assertTrue(wheel.name.startsWith("$name-1.0-") && wheel.name.endsWith("-cp315t-android_24_arm64_v8a.whl"), wheel.name)
        val so = File(root, "unpacked-$name.so")
        ZipFile(wheel).use { zip ->
            val entry = zip.entries().asSequence().single { it.name.startsWith("$name/_fast.") }
            assertEquals("$name/_fast.cpython-315t-aarch64-linux-android.so", entry.name)
            zip.getInputStream(entry).use { input -> so.outputStream().use { input.copyTo(it) } }
        }
        val header = so.inputStream().use { it.readNBytes(20) }
        assertEquals(listOf(0x7f, 'E'.code, 'L'.code, 'F'.code), header.take(4).map { it.toInt() and 0xff })
        assertEquals(183, (header[18].toInt() and 0xff) or ((header[19].toInt() and 0xff) shl 8), "e_machine must be EM_AARCH64")
    }

    @Test
    fun `a setuptools sdist builds for aarch64 Android with the target's suffix`() {
        setUp()
        buildAndCheck(
            "stdemo",
            mapOf(
                "pyproject.toml" to "[build-system]\nrequires = [\"setuptools>=70\"]\nbuild-backend = \"setuptools.build_meta\"\n\n" +
                    "[project]\nname = \"stdemo\"\nversion = \"1.0\"\n",
                "setup.py" to "from setuptools import Extension, setup\n" +
                    "setup(ext_modules=[Extension(\"stdemo._fast\", [\"src/fast.c\"])], packages=[\"stdemo\"])\n",
            ),
        )
    }

    @Test
    fun `a meson-python sdist builds for aarch64 Android with the target's suffix`() {
        setUp()
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
        setUp()
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
}
