package org.thisisthepy.python.multiplatform.packpack.compile.extension

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.thisisthepy.python.multiplatform.packpack.compile.backend.external.locateTargetPython
import org.thisisthepy.python.multiplatform.packpack.dependency.backend.BackendInterface
import org.thisisthepy.python.multiplatform.packpack.dependency.backend.BackendType
import org.thisisthepy.python.multiplatform.packpack.utils.Platforms
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real builds through the native compile slot for the targets #155 added, each on the host that can build
 * it: Android (`aarch64-linux-android`, NDK) on Linux, iOS (`arm64-apple-ios-simulator`, Xcode) on macOS,
 * and Windows (`x86_64-pc-windows-msvc`, LLVM clang) on Windows, where the `.pyd` is also imported.
 *
 * Needs the toolchain of each, `uv` and network access (the target Python), so it runs only with
 * `PPP_NATIVE_SLOT_REAL_BUILD=1` (pypackpack test.yml's `real-builds*` jobs). A test whose host cannot
 * build its target is skipped. Everything it creates is under this module's `build/`.
 */
class NativeSlotRealBuildTest {
    private val root = File("build/native-slot-real").absoluteFile
    private val host = Platforms.detectHostTarget()
    private val abi = PythonAbi("3.15")

    private fun optIn(family: String) {
        assumeTrue(System.getenv("PPP_NATIVE_SLOT_REAL_BUILD") == "1", "set PPP_NATIVE_SLOT_REAL_BUILD=1 to build for real")
        assumeTrue(Platforms.getPlatformFamily(host) == family, "this target builds on a $family host")
    }

    private fun source(dir: File): File =
        File(dir, "src/k.c").apply {
            parentFile.mkdirs()
            writeText(
                "#include <Python.h>\n" +
                    "static PyObject *answer(PyObject *self, PyObject *args) { return PyLong_FromLong(42); }\n" +
                    "static PyMethodDef methods[] = {{\"answer\", answer, METH_NOARGS, NULL}, {NULL, NULL, 0, NULL}};\n" +
                    "static PyModuleDef_Slot slots[] = {{Py_mod_gil, Py_MOD_GIL_NOT_USED}, {0, NULL}};\n" +
                    "static struct PyModuleDef def = {PyModuleDef_HEAD_INIT, \"k\", NULL, 0, methods, slots};\n" +
                    "PyMODINIT_FUNC PyInit_k(void) { return PyModuleDef_Init(&def); }\n",
            )
        }

    private fun build(target: String, dir: File, include: File, suffix: String, libDir: File?, libraryName: String?): File {
        val result = ExtensionCompilerInterface.create().compile(
            ExtensionCompileRequest(
                modules = listOf(ExtensionModuleSource("k", listOf(source(dir)))),
                target = target, pythonAbi = abi, buildType = "release", workingDir = dir,
                buildDir = File(dir, "work"), outputDir = File(dir, "out"), includeDir = include,
                extensionSuffix = suffix, libDir = libDir, libraryName = libraryName,
            ),
        ).getOrThrow()
        return File(result.outputDir, result.modules.single().relativePath).also { assertTrue(it.isFile, it.path) }
    }

    private fun install(target: String, dir: File) {
        runBlocking { BackendInterface.create(BackendType.UV).installPython("3.15t", target, dir) }.getOrThrow()
    }

    @Test
    fun `an Android arm64 extension links libpython and is an aarch64 ELF`() {
        optIn("linux")
        val work = File(root, "android").apply { deleteRecursively(); mkdirs() }
        install("aarch64-linux-android", File(work, "android_arm64"))
        val python = locateTargetPython(work, "aarch64-linux-android")

        val so = build("aarch64-linux-android", work, python.includeDir, python.extensionSuffix, python.libDir, python.libraryName)

        val header = so.inputStream().use { it.readNBytes(20) }
        assertEquals(183, (header[18].toInt() and 0xff) or ((header[19].toInt() and 0xff) shl 8), "e_machine must be EM_AARCH64")
    }

    @Test
    fun `an iOS simulator extension links Python framework and is an arm64 Mach-O`() {
        optIn("macos")
        val work = File(root, "ios").apply { deleteRecursively(); mkdirs() }
        install("arm64-apple-ios-simulator", File(work, "ios_simulator_arm64"))
        val python = locateTargetPython(work, "arm64-apple-ios-simulator")

        val so = build("arm64-apple-ios-simulator", work, python.includeDir, python.extensionSuffix, python.libDir, null)

        val magic = so.inputStream().use { it.readNBytes(8) }
        assertEquals(listOf(0xcf, 0xfa, 0xed, 0xfe), magic.take(4).map { it.toInt() and 0xff }, "MH_MAGIC_64")
        assertEquals(0x0100000c, (magic[4].toInt() and 0xff) or ((magic[5].toInt() and 0xff) shl 8) or ((magic[6].toInt() and 0xff) shl 16) or ((magic[7].toInt() and 0xff) shl 24), "CPU_TYPE_ARM64")
    }

    @Test
    fun `a Windows extension links python315t lib and imports into the target Python`() {
        optIn("windows")
        val target = org.thisisthepy.python.multiplatform.packpack.compile.backend.external.CargoEnvironment.windowsMachineTarget() ?: host
        val work = File(root, "windows").apply { deleteRecursively(); mkdirs() }
        val runtime = File(work, "python").also { install(target, it) }
        val pythonExe = runtime.walkTopDown().first { it.name.equals("python.exe", ignoreCase = true) }
        val home = pythonExe.parentFile
        val lib = File(home, "libs").listFiles { f -> Regex("""python3\d+t\.lib""").matches(f.name) }!!.single()
        val suffix = if (target.startsWith("aarch64")) ".cp315t-win_arm64.pyd" else ".cp315t-win_amd64.pyd"

        val pyd = build(target, work, File(home, "include"), suffix, lib.parentFile, lib.name)

        val process = ProcessBuilder(pythonExe.path, "-c", "import sys; sys.path.insert(0, sys.argv[1]); import k; print(k.answer())", pyd.parentFile.path)
            .redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(2, TimeUnit.MINUTES))
        assertEquals(0, process.exitValue(), out)
        assertEquals("42", out.trim())
    }
}
