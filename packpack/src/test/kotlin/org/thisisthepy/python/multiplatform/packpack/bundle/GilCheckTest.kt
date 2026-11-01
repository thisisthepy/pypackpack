package org.thisisthepy.python.multiplatform.packpack.bundle

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SPEC Scope decisions: a dependency whose extension makes CPython 3.15t run with the GIL is warned
 * about at build time. Two checks (lead decision 2026-10-17, #72): the file's ABI tag on every target,
 * and on the host an import in a 3.15t subprocess that reads `sys._is_gil_enabled()`.
 */
class GilCheckTest {
    private val tempDir: File = Files.createTempDirectory("gil-check").toFile()

    @AfterTest
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    @Test
    fun extensionAbiIsReadFromTheFileNameTag() {
        val expected =
            mapOf(
                "_core.cpython-315t-darwin.so" to ExtensionAbi.FREE_THREADED,
                "_core.cpython-315t-aarch64-linux-android.so" to ExtensionAbi.FREE_THREADED,
                "_core.abi3t.so" to ExtensionAbi.FREE_THREADED,
                "_core.abi3t-darwin.so" to ExtensionAbi.FREE_THREADED,
                "_core.cp315t-win_amd64.pyd" to ExtensionAbi.FREE_THREADED,
                "_core.cpython-315-darwin.so" to ExtensionAbi.GIL,
                "_core.cpython-314-x86_64-linux-gnu.so" to ExtensionAbi.GIL,
                "_core.abi3.so" to ExtensionAbi.GIL,
                "_core.cp314-win_amd64.pyd" to ExtensionAbi.GIL,
                "_core.so" to ExtensionAbi.UNTAGGED,
                "_core.pyd" to ExtensionAbi.UNTAGGED,
                "core.py" to null,
                "libfoo.so.1" to null,
            )
        for ((name, abi) in expected) {
            assertEquals(abi, GilCheck.extensionAbi(name), name)
        }
    }

    @Test
    fun gilTaggedExtensionsAreWarnedAboutNamingTheModule() {
        val warnings =
            GilCheck.tagWarnings(
                listOf(
                    "numpy/_core/_multiarray.cpython-315-darwin.so",
                    "fast/_x.abi3.so",
                    "ok/_y.abi3t.so",
                    "ok/_z.cpython-315t-darwin.so",
                    "plain/_w.so",
                    "pure/mod.py",
                ),
            )

        assertEquals(2, warnings.size, warnings.joinToString("\n"))
        assertTrue(warnings.any { "numpy._core._multiarray" in it && "will not load" in it }, warnings.joinToString("\n"))
        assertTrue(warnings.any { "fast._x" in it }, warnings.joinToString("\n"))
    }

    @Test
    fun theProbeImportsEachModuleInItsOwnProcessAndWarnsWhenTheGilComesOn() {
        val probed = mutableListOf<List<String>>()
        val warnings =
            GilCheck.probeWarnings(
                pythonRoot = tempDir,
                modules = listOf("good._a", "bad._b"),
                interpreter = File("/fake/python3.15t"),
                run = { command, _ ->
                    probed += command
                    ProbeOutcome.Finished(if ("bad._b" in command.last()) "1" else "0")
                },
            )

        assertEquals(2, probed.size, "one process per module")
        assertTrue(probed.all { it.first() == "/fake/python3.15t" && "-I" in it }, "$probed")
        assertEquals(1, warnings.size, warnings.joinToString("\n"))
        assertTrue("bad._b" in warnings.single() && "re-enables the GIL" in warnings.single(), warnings.single())
    }

    @Test
    fun aProbeThatTimesOutOrFailsIsReportedNotFatal() {
        val warnings =
            GilCheck.probeWarnings(
                pythonRoot = tempDir,
                modules = listOf("slow._a", "broken._b"),
                interpreter = File("/fake/python3.15t"),
                run = { command, _ ->
                    if ("slow._a" in command.last()) ProbeOutcome.TimedOut else ProbeOutcome.Failed("ImportError: libfoo")
                },
            )

        assertEquals(2, warnings.size, warnings.joinToString("\n"))
        assertTrue(warnings.any { "slow._a" in it && "timed out" in it }, warnings.joinToString("\n"))
        assertTrue(warnings.any { "broken._b" in it && "ImportError: libfoo" in it }, warnings.joinToString("\n"))
    }

    @Test
    fun extensionModuleNamesFollowThePayloadPath() {
        assertEquals(
            listOf("numpy._core._multiarray", "_top"),
            GilCheck.extensionModules(listOf("numpy/_core/_multiarray.cpython-315t-darwin.so", "_top.abi3t.so", "pkg/mod.py")),
        )
    }
}
