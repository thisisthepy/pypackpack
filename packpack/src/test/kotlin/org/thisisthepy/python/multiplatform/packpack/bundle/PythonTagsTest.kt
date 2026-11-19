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

    @Test
    fun abi3tExtensionProducesAbi3Abi3tTag() {
        for (path in listOf("core/_fast.abi3t.so", "core/_hello.abi3t-x86_64-linux-gnu.so", "core/_fast.abi3t.pyd")) {
            assertEquals(
                PythonTags("cp315", "abi3.abi3t"),
                PythonTags.forPayload(pythonVersion = null, payloadPaths = listOf("core/__init__.py", path)),
                path,
            )
        }
        assertEquals(
            PythonTags("cp315", "abi3.abi3t"),
            PythonTags.forPayload(
                pythonVersion = null,
                payloadPaths = listOf("core/_fast.abi3t.so"),
                requiresPython = ">=3.15",
            ),
        )
    }

    @Test
    fun abi3ExtensionUsesRequiresPythonFloorOrDefault() {
        assertEquals(
            PythonTags("cp313", "abi3"),
            PythonTags.forPayload(
                pythonVersion = null,
                payloadPaths = listOf("torch/__init__.py", "torch/_C.abi3.so"),
                requiresPython = ">=3.13",
            ),
        )
        assertEquals(
            PythonTags("cp315", "abi3"),
            PythonTags.forPayload(
                pythonVersion = null,
                payloadPaths = listOf("torch/_C.abi3.pyd"),
                requiresPython = null,
            ),
        )
        assertEquals(
            PythonTags("cp314", "abi3"),
            PythonTags.forPayload(
                pythonVersion = "3.14",
                payloadPaths = listOf("torch/_C.abi3.so"),
                requiresPython = ">=3.13",
            ),
        )
    }

    @Test
    fun freeThreadedCpythonExtensionDerivesVersionTag() {
        assertEquals(
            PythonTags("cp315", "cp315t"),
            PythonTags.forPayload(
                pythonVersion = null,
                payloadPaths = listOf("torch/_C.cpython-315t-darwin.so"),
            ),
        )
        assertEquals(
            PythonTags("cp315", "cp315t"),
            PythonTags.forPayload(
                pythonVersion = null,
                payloadPaths = listOf("torch/_C.cp315t-win_amd64.pyd"),
            ),
        )
    }

    @Test
    fun untaggedWindowsPydUsesConfiguredRustAbi() {
        assertEquals(
            PythonTags("cp313", "abi3"),
            PythonTags.forPayload(null, listOf("torch/__init__.py", "torch/_C.pyd"), ">=3.13", "abi3-py313"),
        )
        assertEquals(
            PythonTags("cp315", "abi3.abi3t"),
            PythonTags.forPayload(null, listOf("core/_fast.pyd"), null, "abi3t-py315"),
        )
        assertEquals(
            PythonTags("cp315", "cp315t"),
            PythonTags.forPayload(null, listOf("torch/_C.pyd"), ">=3.13", null),
        )
    }
}
