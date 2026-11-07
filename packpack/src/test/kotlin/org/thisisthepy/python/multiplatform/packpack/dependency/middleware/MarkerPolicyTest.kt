package org.thisisthepy.python.multiplatform.packpack.dependency.middleware

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MarkerPolicyTest {
    // Before #49 every aarch64 target was written as platform_machine == 'arm64', and i686 Windows
    // as 'i686'; before #107 (uv 0.13.6, astral-sh/uv#22935) x86_64 Windows was written as
    // 'x86_64' instead of 'AMD64'. Those entries are in users' pyproject.toml files, so
    // `remove --target` has to keep finding them under the corrected key.
    @Test
    fun targetKeyFor_readsMarkersWrittenBeforeTheMachineFixUnderTheCorrectedKey() {
        val legacy =
            mapOf(
                "platform_machine == 'arm64' and sys_platform == 'android'" to "aarch64-linux-android",
                "platform_system == 'Android' and platform_machine == 'arm64'" to "aarch64-linux-android",
                "platform_machine == 'arm64' and sys_platform == 'linux'" to "aarch64-unknown-linux-gnu",
                "platform_machine == 'arm64' and sys_platform == 'win32'" to "aarch64-pc-windows-msvc",
                "platform_machine == 'x86_64' and sys_platform == 'win32'" to "x86_64-pc-windows-msvc",
                "platform_system == 'Windows' and platform_machine == 'x86_64'" to "x86_64-pc-windows-msvc",
                "platform_machine == 'i686' and sys_platform == 'win32'" to "i686-pc-windows-msvc",
            )
        for ((marker, target) in legacy) {
            assertEquals(MarkerPolicy.targetKeyForTarget(target), MarkerPolicy.targetKeyFor(marker), marker)
        }
    }

    @Test
    fun markerForTarget_usesAmd64ForX8664Windows() {
        assertEquals(
            "platform_system == 'Windows' and platform_machine == 'AMD64'",
            MarkerPolicy.markerForTarget("x86_64-pc-windows-msvc"),
        )
    }

    @Test
    fun targetKeyFor_keepsAppleArm64() {
        assertEquals(
            MarkerPolicy.targetKeyForTarget("aarch64-apple-darwin"),
            MarkerPolicy.targetKeyFor("platform_machine == 'arm64' and sys_platform == 'darwin'"),
        )
        assertEquals(
            MarkerPolicy.targetKeyForTarget("arm64-apple-ios"),
            MarkerPolicy.targetKeyFor("platform_machine == 'arm64' and sys_platform == 'ios'"),
        )
    }

    @Test
    fun parseUvVersion_extractsTripleFromStandardAndHomebrewOutputs() {
        assertEquals(Triple(0, 13, 6), MarkerPolicy.parseUvVersion("uv 0.13.6"))
        assertEquals(
            Triple(0, 12, 3),
            MarkerPolicy.parseUvVersion("uv 0.12.3 (Homebrew 2026-08-07 aarch64-apple-darwin)\n"),
        )
        assertNull(MarkerPolicy.parseUvVersion("unexpected output"))
    }

    @Test
    fun requireUvForTargets_rejectsUvOlderThan0136ForX8664WindowsOnly() {
        val error =
            assertFailsWith<IllegalStateException> {
                MarkerPolicy.requireUvForTargets(
                    listOf("x86_64-pc-windows-msvc"),
                    "uv 0.13.5 (deadbeef 2026-10-20)",
                )
            }
        val msg = error.message.orEmpty()
        assertTrue("0.13.6" in msg && "0.13.5" in msg && "AMD64" in msg, msg)

        // 0.13.6 and newer are accepted for x86_64-pc-windows-msvc.
        MarkerPolicy.requireUvForTargets(listOf("x86_64-pc-windows-msvc"), "uv 0.13.6")
        MarkerPolicy.requireUvForTargets(listOf("x86_64-pc-windows-msvc"), "uv 0.14.0")

        // Non-x86_64-Windows targets are accepted even on older uv.
        MarkerPolicy.requireUvForTargets(
            listOf("aarch64-pc-windows-msvc", "aarch64-linux-android", "aarch64-apple-darwin"),
            "uv 0.12.3",
        )
    }
}
