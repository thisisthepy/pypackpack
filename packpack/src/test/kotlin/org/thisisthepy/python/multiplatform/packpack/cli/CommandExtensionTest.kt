package org.thisisthepy.python.multiplatform.packpack.cli

import kotlin.test.Test
import kotlin.test.assertEquals

class CommandExtensionTest {
    @Test
    fun parsePassthroughArgs_separatesPositionalsFromFlags() {
        val result = parsePassthroughArgs(listOf("requests", "numpy"))

        assertEquals(listOf("requests", "numpy"), result.positionals)
        assertEquals(emptyMap(), result.extraArgs)
    }

    @Test
    fun parsePassthroughArgs_treatsTrailingFlagAsBooleanWithNoValue() {
        val result = parsePassthroughArgs(listOf("requests", "--dev"))

        assertEquals(listOf("requests"), result.positionals)
        assertEquals(mapOf("dev" to ""), result.extraArgs)
    }

    @Test
    fun parsePassthroughArgs_knownValueTakingFlagConsumesTheFollowingTokenAsItsValue() {
        val result = parsePassthroughArgs(listOf("--extra-index-url", "https://example.com/simple", "requests"))

        assertEquals(listOf("requests"), result.positionals)
        assertEquals(mapOf("extra-index-url" to "https://example.com/simple"), result.extraArgs)
    }

    @Test
    fun parsePassthroughArgs_unknownFlagDoesNotSwallowAFollowingPositional() {
        // --dev is not in the value-taking allowlist, so it must stay boolean and "numpy" must
        // stay a dependency, not become the value of --dev.
        val result = parsePassthroughArgs(listOf("requests", "--dev", "numpy"))

        assertEquals(listOf("requests", "numpy"), result.positionals)
        assertEquals(mapOf("dev" to ""), result.extraArgs)
    }

    @Test
    fun parsePassthroughArgs_treatsFlagFollowedByAnotherFlagAsTwoBooleanFlags() {
        val result = parsePassthroughArgs(listOf("--dev", "--editable"))

        assertEquals(emptyList(), result.positionals)
        assertEquals(mapOf("dev" to "", "editable" to ""), result.extraArgs)
    }

    @Test
    fun parsePassthroughArgs_handlesEmptyInput() {
        val result = parsePassthroughArgs(emptyList())

        assertEquals(emptyList(), result.positionals)
        assertEquals(emptyMap(), result.extraArgs)
    }

    @Test
    fun parsePassthroughArgs_preservesInterleavedOrder() {
        val result = parsePassthroughArgs(listOf("requests", "--dev", "numpy", "--upgrade"))

        assertEquals(listOf("requests", "numpy"), result.positionals)
        assertEquals(mapOf("dev" to "", "upgrade" to ""), result.extraArgs)
    }
}
