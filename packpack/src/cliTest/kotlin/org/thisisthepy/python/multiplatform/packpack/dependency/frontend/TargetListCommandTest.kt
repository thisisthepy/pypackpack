package org.thisisthepy.python.multiplatform.packpack.dependency.frontend

import com.github.ajalt.clikt.testing.test
import org.thisisthepy.python.multiplatform.packpack.utils.Platforms
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `pypackpack target list` (docs/SPEC.md *Viewing build target platforms*): every
 * `Platforms.SUPPORTED_TARGETS` entry is printed exactly once, under its family's heading, so a target
 * added to the table without a section rule cannot silently disappear from the list.
 */
class TargetListCommandTest {
    @Test
    fun everySupportedTargetIsListedOnceUnderItsFamily() {
        val result = TargetListCommand().test("")

        assertEquals(0, result.statusCode, result.output)
        val lines = result.output.lines()
        assertEquals("Supported target platforms", lines.first())

        var heading: String? = null
        val listed = mutableListOf<Pair<String, String>>()
        lines.forEach { line ->
            when {
                line.endsWith(":") && !line.startsWith(" ") -> heading = line.removeSuffix(":")
                line.startsWith("  - ") -> listed += (heading ?: "") to line.removePrefix("  - ")
            }
        }

        assertEquals(Platforms.SUPPORTED_TARGETS.sorted(), listed.map { it.second }.sorted())
        val family = listed.associate { (h, t) -> t to h }
        assertEquals("Aliases", family["linux"])
        assertEquals("Windows", family["aarch64-pc-windows-msvc"])
        assertEquals("Linux", family["x86_64-manylinux_2_28"])
        assertEquals("macOS", family["aarch64-apple-darwin"])
        assertEquals("Android", family["aarch64-linux-android"])
        assertEquals("iOS", family["arm64-apple-ios-simulator"])
        assertTrue(Platforms.SUPPORTED_TARGETS.filter { "emscripten" in it || "wasm" in it }.all { family[it] == "Wasm" })
    }
}
