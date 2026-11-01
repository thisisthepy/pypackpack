package org.thisisthepy.python.multiplatform.packpack.bundle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** `docs/SPEC.md`, "Wheel tags": the tags follow the target interpreter's version and build. */
class PythonTagsTest {
    @Test
    fun aFreeThreadedTargetGetsATTagOnBothTags() {
        for (v in listOf("3.15t", "3.15.0t")) {
            assertEquals(PythonTags("cp315", "cp315t"), PythonTags.of(v), v)
        }
    }

    @Test
    fun aGilTargetGetsPlainTags() {
        for (v in listOf("3.15", "3.15.0")) assertEquals(PythonTags("cp315", "cp315"), PythonTags.of(v), v)
        assertEquals(PythonTags("cp314", "cp314"), PythonTags.of("3.14.7"))
        assertEquals(PythonTags("cp313", "cp313"), PythonTags.of("3.13"))
    }

    @Test
    fun noVersionMeansTheEcosystemFreeThreaded315() {
        assertEquals(PythonTags("cp315", "cp315t"), PythonTags.of(null))
    }

    @Test
    fun anUnparsableVersionIsRefused() {
        for (v in listOf("", "3", "latest", "3.15x", "py315")) {
            assertFailsWith<IllegalArgumentException>(v) { PythonTags.of(v) }
        }
    }
}
