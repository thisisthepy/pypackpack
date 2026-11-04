package org.thisisthepy.python.multiplatform.packpack.dependency.middleware

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
