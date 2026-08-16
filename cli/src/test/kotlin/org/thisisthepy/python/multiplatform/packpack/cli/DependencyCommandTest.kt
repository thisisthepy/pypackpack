package org.thisisthepy.python.multiplatform.packpack.cli

import com.github.ajalt.clikt.core.obj
import com.github.ajalt.clikt.testing.test
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the CLI-layer gap documented in docs/SPEC.md: `add`/`remove`/`sync`/`tree` used to
 * hardcode `extraArgs = null`, so there was no way to forward flags like `--dev` to the
 * underlying `uv` call even though the backend (UVBackend.appendOptions) already accepts an
 * arbitrary extraArgs map. These tests exercise the passthrough wiring end to end through the
 * real Clikt parser, not just the parsePassthroughArgs helper in isolation
 * (see CommandExtensionTest for that).
 */
class DependencyCommandTest {
    @Test
    fun add_forwardsUnrecognizedFlagsAsExtraArgsAndKeepsDependenciesSeparate() {
        val middleware = RecordingMiddleware()
        val command = AddCommand().apply { configureContext { obj = middleware } }

        val result = command.test("requests --dev --extra-index-url https://example.com/simple")

        assertEquals(0, result.statusCode, result.output)
        assertEquals(listOf("requests"), middleware.lastAddDependencies)
        assertEquals(
            mapOf("dev" to "", "extra-index-url" to "https://example.com/simple"),
            middleware.lastAddExtraArgs,
        )
    }

    @Test
    fun add_failsWithoutSilentlySwallowingWhenOnlyFlagsAreGiven() {
        val middleware = RecordingMiddleware()
        val command = AddCommand().apply { configureContext { obj = middleware } }

        val result = command.test("--dev")

        assertEquals(1, result.statusCode)
        assertTrue(result.output.contains("No dependencies specified"), result.output)
    }

    @Test
    fun remove_forwardsUnrecognizedFlagsAsExtraArgs() {
        val middleware = RecordingMiddleware()
        val command = RemoveCommand().apply { configureContext { obj = middleware } }

        val result = command.test("requests --dev")

        assertEquals(0, result.statusCode, result.output)
        assertEquals(listOf("requests"), middleware.lastRemoveDependencies)
        assertEquals(mapOf("dev" to ""), middleware.lastRemoveExtraArgs)
    }

    @Test
    fun sync_forwardsFlagsWithoutRequiringPositionalArguments() {
        val middleware = RecordingMiddleware()
        val command = SyncCommand().apply { configureContext { obj = middleware } }

        val result = command.test("--frozen --locked")

        assertEquals(0, result.statusCode, result.output)
        assertEquals(mapOf("frozen" to "", "locked" to ""), middleware.lastSyncExtraArgs)
    }

    @Test
    fun sync_rejectsStrayPositionalArguments() {
        val middleware = RecordingMiddleware()
        val command = SyncCommand().apply { configureContext { obj = middleware } }

        val result = command.test("bogus")

        assertEquals(1, result.statusCode)
        assertTrue(result.output.contains("Unexpected argument"), result.output)
    }

    @Test
    fun tree_combinesKnownTargetOptionWithPassthroughFlags() {
        val middleware = RecordingMiddleware()
        val command = TreeCommand().apply { configureContext { obj = middleware } }

        val result = command.test("--quiet --target windows linux")

        assertEquals(0, result.statusCode, result.output)
        assertEquals(listOf("windows", "linux"), middleware.lastTreeTargets)
        assertEquals(mapOf("quiet" to ""), middleware.lastTreeExtraArgs)
    }
}
