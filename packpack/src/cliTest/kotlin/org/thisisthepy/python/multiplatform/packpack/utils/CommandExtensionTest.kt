package org.thisisthepy.python.multiplatform.packpack.utils

import org.thisisthepy.python.multiplatform.packpack.dependency.frontend.*
import org.thisisthepy.python.multiplatform.packpack.compile.frontend.*
import org.thisisthepy.python.multiplatform.packpack.deploy.frontend.*

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
    fun parsePassthroughArgs_takesTheValuesTheSpecTellsUsersToPass() {
        // #50: docs/SPEC.md tells users to pass --python-version and --only-binary :all:.
        val result = parsePassthroughArgs(listOf("--python-version", "3.13", "--only-binary", ":all:", "six"))

        assertEquals(listOf("six"), result.positionals)
        assertEquals(mapOf("python-version" to "3.13", "only-binary" to ":all:"), result.extraArgs)
    }

    @Test
    fun parsePassthroughArgs_splitsAnyFlagWrittenWithAnEqualsSign() {
        val result = parsePassthroughArgs(listOf("--python-version=3.13", "--exclude-newer=2026-01-01", "--dev"))

        assertEquals(emptyList(), result.positionals)
        assertEquals(mapOf("python-version" to "3.13", "exclude-newer" to "2026-01-01", "dev" to ""), result.extraArgs)
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

    // `ppp python install 3.15t <target>` (docs/SPEC.md, *Version and target selection*): the
    // free-threaded `t` suffix is part of the version the CLI accepts, for every `python` subcommand.
    @Test
    fun validatePythonVersion_acceptsTheFreeThreadedSuffix() {
        for (accepted in listOf("3.15", "3.15t", "3.15.0", "3.15.0t")) {
            assertEquals(true, validatePythonVersion(accepted), accepted)
        }
        for (rejected in listOf("3", "3.15tt", "t3.15", "3.15.0.1", "3.15-t", "latest")) {
            assertEquals(false, validatePythonVersion(rejected), rejected)
        }
    }
}
