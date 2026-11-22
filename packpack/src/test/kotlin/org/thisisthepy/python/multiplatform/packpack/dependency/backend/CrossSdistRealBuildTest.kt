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
 * sdist-only C package (a setuptools project with one extension module) builds for `aarch64-linux-android`,
 * and its extension carries the Android suffix and is an aarch64 ELF.
 *
 * Needs a Linux host, the Android NDK, `uv` and network access (the host and target CPython, setuptools), so it
 * runs only with `PPP_CROSS_SDIST_REAL_BUILD=1` (pypackpack test.yml's `real-builds` job). Everything it creates
 * is under this module's `build/`.
 */
class CrossSdistRealBuildTest {
    private val root = File("build/cross-sdist-real").absoluteFile

    @Test
    fun `an sdist-only C package builds for aarch64 Android with the target's suffix`() {
        assumeTrue(System.getenv("PPP_CROSS_SDIST_REAL_BUILD") == "1", "set PPP_CROSS_SDIST_REAL_BUILD=1 to build for real")
        assumeTrue(Platforms.getPlatformFamily(Platforms.detectHostTarget()) == "linux", "the NDK cross build runs on a Linux host here")
        root.deleteRecursively()
        val target = "aarch64-linux-android"
        val pkg = File(root, "pkg").apply { mkdirs() }
        File(pkg, "pyproject.toml").writeText("[project]\nname = \"app\"\nversion = \"0.1.0\"\n")
        runBlocking { UVBackend().installPython("3.15t", target, File(root, "android_arm64")) }.getOrThrow()

        val project = File(root, "sdist-tree").apply { mkdirs() }
        File(project, "pyproject.toml").writeText(
            "[build-system]\nrequires = [\"setuptools>=70\"]\nbuild-backend = \"setuptools.build_meta\"\n\n" +
                "[project]\nname = \"crossdemo\"\nversion = \"1.0\"\n",
        )
        File(project, "setup.py").writeText(
            "from setuptools import Extension, setup\nsetup(ext_modules=[Extension(\"crossdemo._fast\", [\"src/fast.c\"])], packages=[\"crossdemo\"], package_dir={\"\": \"lib\"})\n",
        )
        File(project, "lib/crossdemo").mkdirs()
        File(project, "lib/crossdemo/__init__.py").writeText("")
        File(project, "src").mkdirs()
        File(project, "src/fast.c").writeText(
            "#include <Python.h>\n" +
                "static PyModuleDef_Slot slots[] = {{Py_mod_gil, Py_MOD_GIL_NOT_USED}, {0, NULL}};\n" +
                "static struct PyModuleDef def = {PyModuleDef_HEAD_INIT, \"_fast\", NULL, 0, NULL, slots};\n" +
                "PyMODINIT_FUNC PyInit__fast(void) { return PyModuleDef_Init(&def); }\n",
        )
        val sdist = File(root, "crossdemo-1.0.tar.gz")
        ArchiveWriter.writeTarGz(project, "crossdemo-1.0", sdist)

        val wheel = CrossSdistBuilder().buildSdist(pkg, target, sdist, "crossdemo").getOrThrow()

        assertTrue(wheel.name.endsWith("-cp315-cp315t-android_24_arm64_v8a.whl"), wheel.name)
        val so = File(root, "unpacked").apply { mkdirs() }
        ZipFile(wheel).use { zip ->
            val entry = zip.entries().asSequence().single { it.name.startsWith("crossdemo/_fast.") }
            assertEquals("crossdemo/_fast.cpython-315t-aarch64-linux-android.so", entry.name)
            File(so, "_fast.so").also { out -> zip.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } } }
        }
        val header = File(so, "_fast.so").inputStream().use { it.readNBytes(20) }
        assertEquals(listOf(0x7f, 'E'.code, 'L'.code, 'F'.code), header.take(4).map { it.toInt() and 0xff })
        assertEquals(183, (header[18].toInt() and 0xff) or ((header[19].toInt() and 0xff) shl 8), "e_machine must be EM_AARCH64")
    }
}
