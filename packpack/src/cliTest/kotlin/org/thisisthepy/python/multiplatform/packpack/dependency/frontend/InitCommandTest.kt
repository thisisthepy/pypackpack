package org.thisisthepy.python.multiplatform.packpack.dependency.frontend

import org.thisisthepy.python.multiplatform.packpack.utils.*

import com.github.ajalt.clikt.core.obj
import com.github.ajalt.clikt.testing.test
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** `pypackpack init` (docs/SPEC.md *Project creation*, #159): its options reach the middleware. */
class InitCommandTest {
    @Test
    fun targetsAndOptionsReachTheMiddleware() {
        val middleware = RecordingMiddleware()
        val result = InitCommand().apply { configureContext { obj = middleware } }
            .test("demo --name demo_pkg --python 3.15t --target aarch64-linux-android arm64-apple-ios")

        assertEquals(0, result.statusCode, result.output)
        assertEquals("demo", middleware.lastInitPath)
        assertEquals(listOf("aarch64-linux-android", "arm64-apple-ios"), middleware.lastInitTargets)
        assertEquals(mapOf("name" to "demo_pkg", "python" to "3.15t"), middleware.lastInitExtraArgs)
    }

    @Test
    fun withoutTargetTheMiddlewareGetsNone() {
        val middleware = RecordingMiddleware()
        val result = InitCommand().apply { configureContext { obj = middleware } }.test("demo")

        assertEquals(0, result.statusCode, result.output)
        assertNull(middleware.lastInitTargets)
    }
}
