package org.thisisthepy.python.multiplatform.packpack.utils

import kotlin.test.Test
import kotlin.test.assertEquals
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
}
