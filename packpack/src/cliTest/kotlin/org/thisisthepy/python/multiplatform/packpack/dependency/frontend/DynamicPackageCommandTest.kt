package org.thisisthepy.python.multiplatform.packpack.dependency.frontend

import org.thisisthepy.python.multiplatform.packpack.utils.*
import org.thisisthepy.python.multiplatform.packpack.compile.frontend.*
import org.thisisthepy.python.multiplatform.packpack.deploy.frontend.*

import com.github.ajalt.clikt.core.obj
import com.github.ajalt.clikt.testing.test
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the per-package half of the same CLI-passthrough gap DependencyCommandTest covers for
 * root-level commands: `pypackpack <package> add/remove/sync/tree` also hardcoded
 * `extraArgs = null`, so e.g. `pypackpack mypackage add numpy --target windows linux
 * --extra-index-url ...` failed even though docs/SPEC.md showed it as a working example.
 */
class DynamicPackageCommandTest {
    @Test
    fun add_forwardsPassthroughFlagsAndTargetsSeparately() {
        val middleware = RecordingMiddleware()
        val command = DynamicPackageCommand("mypackage", "add").apply { configureContext { obj = middleware } }

        val result = command.test("numpy --extra-index-url https://example.com/simple --target windows linux")

        assertEquals(0, result.statusCode, result.output)
        assertEquals("mypackage", middleware.lastAddPackageName)
        assertEquals(listOf("numpy"), middleware.lastAddDependencies)
        assertEquals(listOf("windows", "linux"), middleware.lastAddTargets)
        assertEquals(mapOf("extra-index-url" to "https://example.com/simple"), middleware.lastAddExtraArgs)
    }

    @Test
    fun remove_forwardsPassthroughFlags() {
        val middleware = RecordingMiddleware()
        val command = DynamicPackageCommand("mypackage", "remove").apply { configureContext { obj = middleware } }

        val result = command.test("numpy --dev")

        assertEquals(0, result.statusCode, result.output)
        assertEquals(listOf("numpy"), middleware.lastRemoveDependencies)
        assertEquals(mapOf("dev" to ""), middleware.lastRemoveExtraArgs)
    }

    @Test
    fun sync_forwardsPassthroughFlagsWithoutDependencies() {
        val middleware = RecordingMiddleware()
        val command = DynamicPackageCommand("mypackage", "sync").apply { configureContext { obj = middleware } }

        val result = command.test("--frozen")

        assertEquals(0, result.statusCode, result.output)
        assertEquals(mapOf("frozen" to ""), middleware.lastSyncExtraArgs)
    }

    @Test
    fun sync_rejectsStrayPositionalArguments() {
        val middleware = RecordingMiddleware()
        val command = DynamicPackageCommand("mypackage", "sync").apply { configureContext { obj = middleware } }

        val result = command.test("bogus")

        assertEquals(1, result.statusCode)
        assertTrue(result.output.contains("Unexpected argument"), result.output)
    }

    @Test
    fun tree_combinesKnownTargetOptionWithPassthroughFlags() {
        val middleware = RecordingMiddleware()
        val command = DynamicPackageCommand("mypackage", "tree").apply { configureContext { obj = middleware } }

        val result = command.test("--quiet --target windows")

        assertEquals(0, result.statusCode, result.output)
        assertEquals(listOf("windows"), middleware.lastTreeTargets)
        assertEquals(mapOf("quiet" to ""), middleware.lastTreeExtraArgs)
    }
}
