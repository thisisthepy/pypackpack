package org.thisisthepy.python.multiplatform.packpack.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PlatformsTest {
    @Test
    fun suggestTargets_returnsIosTargetsForIosQuery() {
        val suggestions = Platforms.suggestTargets("ios")

        assertEquals(
            listOf(
                "arm64-apple-ios",
                "arm64-apple-ios-simulator",
                "x86_64-apple-ios-simulator",
            ),
            suggestions,
        )
    }

    @Test
    fun suggestTargets_returnsAliasForSmallTypo() {
        val suggestions = Platforms.suggestTargets("linx")

        assertTrue("linux" in suggestions)
    }

    /**
     * `BundleRequest.minSdk` (the Android min SDK / API level `toolchain`'s per-variant graph reads
     * but has nowhere to send -- `toolchain`'s `f60bc3b`) is only meaningful for the `android` family:
     * PEP 738 is the only wheel tag scheme in this project that carries an API level
     * (`android_<api-level>_<abi>`). `null` (undeclared) must always be accepted regardless of family.
     */
    @Test
    fun requireValidMinSdk_acceptsNullForAnyFamily() {
        Platforms.requireValidMinSdk(null, "windows")
        Platforms.requireValidMinSdk(null, "android")
        Platforms.requireValidMinSdk(null, "macos")
    }

    @Test
    fun requireValidMinSdk_acceptsAPositiveValueForAndroid() {
        Platforms.requireValidMinSdk(24, "android")
    }

    @Test
    fun requireValidMinSdk_rejectsADeclaredValueForANonAndroidFamily() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                Platforms.requireValidMinSdk(24, "windows")
            }
        assertTrue(error.message.orEmpty().contains("windows"), error.message.orEmpty())
    }

    @Test
    fun requireValidMinSdk_rejectsANonPositiveValue() {
        assertFailsWith<IllegalArgumentException> { Platforms.requireValidMinSdk(0, "android") }
        assertFailsWith<IllegalArgumentException> { Platforms.requireValidMinSdk(-1, "android") }
    }
}
