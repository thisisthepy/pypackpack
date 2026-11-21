package org.thisisthepy.python.multiplatform.packpack.compile.backend.external

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.thisisthepy.python.multiplatform.packpack.compile.backend.DefaultBackend
import org.thisisthepy.python.multiplatform.packpack.compile.backend.DependencyCheck
import org.thisisthepy.python.multiplatform.packpack.dependency.backend.BackendInterface
import org.thisisthepy.python.multiplatform.packpack.dependency.backend.BackendType
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A real Meson cross build (#155): a C extension for `aarch64-unknown-linux-gnu` built on an x86_64 Linux
 * host with `zig cc`, against the target 3.15t that `installPython` puts in `linux_arm64`. The result is an
 * aarch64 ELF named with the target's `EXT_SUFFIX`, in the `site-packages` the bundlers read.
 *
 * Needs `uv`, network access (PyPI for meson, ninja and ziglang; python-build-standalone) and an x86_64
 * Linux host, so it runs only with `PPP_MESON_CROSS_REAL_BUILD=1` (pypackpack test.yml's `real-builds`
 * job). Everything it creates is under this module's `build/`.
 */
class MesonCrossRealBuildTest {
    private val root = File("build/meson-cross-real").absoluteFile

    @Test
    fun `a C extension cross-builds for aarch64 Linux with the target's suffix`() {
        assumeTrue(System.getenv("PPP_MESON_CROSS_REAL_BUILD") == "1", "set PPP_MESON_CROSS_REAL_BUILD=1 to cross-build for real")
        root.deleteRecursively()
        val pkg = File(root, "pkg").apply { mkdirs() }
        // A workspace of its own, so the compile never walks up into this repository's pyproject.toml.
        File(root, "pyproject.toml").writeText("[project]\nname = \"ws\"\nversion = \"0.0.0\"\n\n[tool.uv.workspace]\nmembers = [\"pkg\"]\n")
        File(pkg, "pyproject.toml").writeText("[project]\nname = \"core\"\nversion = \"0.1.0\"\n\n[tool.ppp]\nc-extensions = [\"src/main/core/_fast.c\"]\n")
        File(pkg, "src/main/core").mkdirs()
        File(pkg, "src/main/core/__init__.py").writeText("")
        File(pkg, "src/main/core/_fast.c").writeText(
            "#include <Python.h>\n" +
                "static PyModuleDef_Slot slots[] = {{Py_mod_gil, Py_MOD_GIL_NOT_USED}, {0, NULL}};\n" +
                "static struct PyModuleDef def = {PyModuleDef_HEAD_INIT, \"_fast\", NULL, 0, NULL, slots};\n" +
                "PyMODINIT_FUNC PyInit__fast(void) { return PyModuleDef_Init(&def); }\n",
        )
        val target = "aarch64-unknown-linux-gnu"
        runBlocking { BackendInterface.create(BackendType.UV).installPython("3.15t", target, File(root, "linux_arm64")) }.getOrThrow()

        val backend = DefaultBackend(dependencyCheck = DependencyCheck { _, _ -> Result.success(Unit) })
        runBlocking { backend.compile("core", mapOf("target" to target), root) }.getOrThrow()

        val so = File(pkg, "dist/$target").walkTopDown().single { it.isFile && it.name.startsWith("_fast.") }
        assertTrue(so.parentFile.name == "core" && so.parentFile.parentFile.name == "site-packages", so.path)
        assertEquals("_fast.cpython-315t-aarch64-linux-gnu.so", so.name)
        val header = so.inputStream().use { it.readNBytes(20) }
        assertEquals(listOf(0x7f, 'E'.code, 'L'.code, 'F'.code), header.take(4).map { it.toInt() and 0xff })
        assertEquals(183, (header[18].toInt() and 0xff) or ((header[19].toInt() and 0xff) shl 8), "e_machine must be EM_AARCH64")
    }
}
