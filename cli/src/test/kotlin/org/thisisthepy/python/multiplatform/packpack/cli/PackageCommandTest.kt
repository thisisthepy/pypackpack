package org.thisisthepy.python.multiplatform.packpack.cli

import com.github.ajalt.clikt.core.obj
import com.github.ajalt.clikt.testing.test
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers the explicit `pypackpack package sync/tree <name>` alternative to the dynamic
 * `pypackpack <package> sync/tree` forms (docs/SPEC.md: "invoke the same logic as an explicit
 * alternative"); it had the same extraArgs = null gap as DynamicPackageCommand.
 */
class PackageCommandTest {
    @Test
    fun sync_forwardsPassthroughFlagsGivenBeforeTarget() {
        val middleware = RecordingMiddleware()
        val command = PackageSyncCommand().apply { configureContext { obj = middleware } }

        val result = command.test("mypackage --frozen --target windows")

        assertEquals(0, result.statusCode, result.output)
        assertEquals(listOf("windows"), middleware.lastSyncTargets)
        assertEquals(mapOf("frozen" to ""), middleware.lastSyncExtraArgs)
    }

    @Test
    fun tree_forwardsPassthroughFlagsGivenBeforeTarget() {
        val middleware = RecordingMiddleware()
        val command = PackageTreeCommand().apply { configureContext { obj = middleware } }

        val result = command.test("mypackage --quiet --target windows")

        assertEquals(0, result.statusCode, result.output)
        assertEquals(listOf("windows"), middleware.lastTreeTargets)
        assertEquals(mapOf("quiet" to ""), middleware.lastTreeExtraArgs)
    }
}
