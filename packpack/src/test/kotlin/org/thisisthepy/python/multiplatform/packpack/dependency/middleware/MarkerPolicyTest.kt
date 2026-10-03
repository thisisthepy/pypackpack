package org.thisisthepy.python.multiplatform.packpack.dependency.middleware

import kotlin.test.Test
import kotlin.test.assertEquals

class MarkerPolicyTest {
    // Before #49 every aarch64 target was written as platform_machine == 'arm64', and i686 Windows
    // as 'i686'. Those entries never matched on Linux, Android or Windows, but they are in users'
    // pyproject.toml files, so `remove --target` has to keep finding them under the corrected key.
    @Test
    fun targetKeyFor_readsMarkersWrittenBeforeTheMachineFixUnderTheCorrectedKey() {
        val legacy =
            mapOf(
                "platform_machine == 'arm64' and sys_platform == 'android'" to "aarch64-linux-android",
                "platform_system == 'Android' and platform_machine == 'arm64'" to "aarch64-linux-android",
                "platform_machine == 'arm64' and sys_platform == 'linux'" to "aarch64-unknown-linux-gnu",
                "platform_machine == 'arm64' and sys_platform == 'win32'" to "aarch64-pc-windows-msvc",
                "platform_machine == 'i686' and sys_platform == 'win32'" to "i686-pc-windows-msvc",
            )
        for ((marker, target) in legacy) {
            assertEquals(MarkerPolicy.targetKeyForTarget(target), MarkerPolicy.targetKeyFor(marker), marker)
        }
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
