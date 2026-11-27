package org.thisisthepy.python.multiplatform.packpack.dependency.backend

import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #82 (team lead 2026-11-27): uv (0.12.3, 0.13.6) has no `--python-platform` for PEP 783's `pyemscripten_2026_0`, so
 * the wasm wheels pypackpack builds are installed by [installWheel], following PEP 427: every file checked against
 * the wheel's own RECORD, `.data/` mapped, RECORD and INSTALLER written, and a wheel for another target refused.
 */
class WheelInstallTest {
    @TempDir
    lateinit var tempDir: File

    private val wasmTags = setOf("cp315-cp315t-pyemscripten_2026_0_wasm32")

    private fun hash(bytes: ByteArray) =
        "sha256=" + Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** A wheel of [files] (path -> content) with a RECORD naming each, [record] rewriting that RECORD's text if given. */
    private fun wheel(
        name: String = "demo-1.0-cp315-cp315t-pyemscripten_2026_0_wasm32.whl",
        files: Map<String, String>,
        record: ((String) -> String)? = null,
        unlisted: Map<String, String> = emptyMap(),
    ): File {
        val distInfo = "demo-1.0.dist-info"
        val all = files + mapOf(
            "$distInfo/METADATA" to "Metadata-Version: 2.1\nName: demo\nVersion: 1.0\n",
            "$distInfo/WHEEL" to "Wheel-Version: 1.0\nRoot-Is-Purelib: false\nTag: cp315-cp315t-pyemscripten_2026_0_wasm32\n",
        )
        val recordText = all.entries.joinToString("") { (path, text) -> "$path,${hash(text.toByteArray())},${text.toByteArray().size}\n" } +
            "$distInfo/RECORD,,\n"
        return File(tempDir, name).also { out ->
            ZipOutputStream(out.outputStream()).use { zip ->
                (all + unlisted + ("$distInfo/RECORD" to (record?.invoke(recordText) ?: recordText))).forEach { (path, text) ->
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(text.toByteArray())
                    zip.closeEntry()
                }
            }
        }
    }

    @Test
    fun aWheelIsInstalledWithItsDataDirectoriesMappedAndItsRecordAndInstallerWritten() {
        val target = File(tempDir, "target")
        val whl = wheel(
            files = mapOf(
                "demo/__init__.py" to "X = 1\n",
                "demo/_fast.cpython-315t-wasm32-emscripten.so" to "\u0000asm",
                "demo-1.0.data/purelib/demo_pure.py" to "P = 1\n",
                "demo-1.0.data/platlib/demo_plat.py" to "Q = 1\n",
                "demo-1.0.data/scripts/demo-tool" to "#!python\n",
                "demo-1.0.data/headers/demo.h" to "/* h */\n",
            ),
        )

        installWheel(whl, target, wasmTags).getOrThrow()

        assertEquals("X = 1\n", File(target, "demo/__init__.py").readText())
        assertTrue(File(target, "demo/_fast.cpython-315t-wasm32-emscripten.so").isFile)
        assertEquals("P = 1\n", File(target, "demo_pure.py").readText())
        assertEquals("Q = 1\n", File(target, "demo_plat.py").readText())
        assertTrue(File(target, "bin/demo-tool").isFile)
        assertTrue(File(target, "include/demo/demo.h").isFile)
        assertFalse(File(target, "demo-1.0.data").exists(), ".data/ is mapped, not copied")
        assertEquals("pypackpack\n", File(target, "demo-1.0.dist-info/INSTALLER").readText())
        val record = File(target, "demo-1.0.dist-info/RECORD").readLines()
        assertTrue("demo_pure.py,${hash("P = 1\n".toByteArray())},6" in record, "$record")
        assertTrue("bin/demo-tool,${hash("#!python\n".toByteArray())},9" in record, "$record")
        assertTrue("demo-1.0.dist-info/INSTALLER,${hash("pypackpack\n".toByteArray())},11" in record, "$record")
        assertTrue("demo-1.0.dist-info/RECORD,," in record, "$record")
    }

    @Test
    fun aWheelWhoseRecordDoesNotMatchItsFilesIsRefusedAndInstallsNothing() {
        val target = File(tempDir, "target")
        val corrupted = wheel(
            files = mapOf("demo/__init__.py" to "X = 1\n"),
            record = { it.replace(hash("X = 1\n".toByteArray()), hash("X = 2\n".toByteArray())) },
        )

        val error = installWheel(corrupted, target, wasmTags).exceptionOrNull()?.message.orEmpty()

        assertTrue("demo/__init__.py" in error && "RECORD" in error, error)
        assertFalse(File(target, "demo").exists(), "nothing is installed from a refused wheel")
    }

    @Test
    fun aFileTheRecordDoesNotListIsRefused() {
        val error = installWheel(wheel(files = mapOf("demo/__init__.py" to ""), unlisted = mapOf("demo/extra.py" to "")), File(tempDir, "t"), wasmTags)
            .exceptionOrNull()?.message.orEmpty()
        assertTrue("demo/extra.py" in error, error)
    }

    @Test
    fun aWheelForAnotherTargetIsRefused() {
        val android = wheel(name = "demo-1.0-cp315-cp315t-android_24_arm64_v8a.whl", files = mapOf("demo/__init__.py" to ""))
        val error = installWheel(android, File(tempDir, "t"), wasmTags).exceptionOrNull()?.message.orEmpty()
        assertTrue("android_24_arm64_v8a" in error && "pyemscripten_2026_0_wasm32" in error, error)
    }

    @Test
    fun aPathOutsideTheTargetIsRefused() {
        val error = installWheel(wheel(files = mapOf("../escape.py" to "")), File(tempDir, "t"), wasmTags).exceptionOrNull()?.message.orEmpty()
        assertTrue("../escape.py" in error, error)
        assertFalse(File(tempDir, "escape.py").exists())
    }
}
