package org.thisisthepy.python.multiplatform.packpack.dependency.backend

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Fixtures are real output of uv 0.12.3 for
 * `uv pip install --target <dir> --python-platform aarch64-linux-android <pkg>`.
 */
class MissingWheelTest {
    private val target = "aarch64-linux-android"

    // torch==2.5.0 --python-version 3.13: wheels exist only for manylinux1_x86_64, no sdist.
    private val wheelsForOtherPlatformsOnly =
        """
        Using CPython 3.14.7
          × No solution found when resolving dependencies:
          ╰─▶ Because torch==2.5.0 has no wheels with a matching platform tag (e.g.,
              `android_24_arm64_v8a`) and you require torch==2.5.0, we can conclude
              that your requirements are unsatisfiable.

        hint: Wheels are available for `torch` (v2.5.0) on the following platform: `manylinux1_x86_64`
        """.trimIndent()

    // pycrypto (sdist only): uv builds it on the host, and the wheel does not fit the target.
    private val sdistOnly =
        """
        Using CPython 3.14.7
        Resolved 1 package in 8.45s
           Building pycrypto==2.6.1
              Built pycrypto==2.6.1
          × Failed to download and build `pycrypto==2.6.1`
          ╰─▶ The built wheel `pycrypto-2.6.1-cp314-cp314-macosx_11_0_arm64.whl` is
              not compatible with the target Python 3.14 on Android aarch64. Consider
              using `--no-build` to disable building wheels.
        """.trimIndent()

    // --only-binary :all: pycrypto: building is disabled, so uv does not say whether an sdist exists.
    private val noBuild =
        """
        Using CPython 3.14.7
          × No solution found when resolving dependencies:
          ╰─▶ Because all versions of pycrypto have no usable wheels and you require
              pycrypto, we can conclude that your requirements are unsatisfiable.

        hint: Wheels are required for `pycrypto` because building from source is disabled for all packages (i.e., with `--no-build`)
        """.trimIndent()

    @Test
    fun parse_namesPackageTargetAndNoSdistWhenOnlyOtherPlatformWheelsExist() {
        val missing = parseMissingWheel(wheelsForOtherPlatformsOnly, target)
        assertEquals(MissingWheel("torch==2.5.0", target, SdistAvailability.NO_SDIST, "manylinux1_x86_64"), missing)
        val message = missing!!.message()
        assertTrue("torch==2.5.0" in message && target in message && "no sdist exists" in message, message)
        assertTrue("manylinux1_x86_64" in message, message)
    }

    @Test
    fun parse_saysOnlyAnSdistExists() {
        val missing = parseMissingWheel(sdistOnly, target)
        assertEquals(MissingWheel("pycrypto==2.6.1", target, SdistAvailability.ONLY_SDIST), missing)
        assertTrue("only an sdist exists" in missing!!.message())
    }

    @Test
    fun parse_reportsUnknownSdistWhenBuildingIsDisabled() {
        val missing = parseMissingWheel(noBuild, target)
        assertEquals(MissingWheel("pycrypto", target, SdistAvailability.UNKNOWN), missing)
    }

    @Test
    fun parse_returnsNullForUnrecognizedText() {
        assertNull(parseMissingWheel("error: Failed to fetch: https://pypi.org/simple/x/", target))
        assertNull(parseMissingWheel("", target))
    }

    @Test
    fun installDependenciesToTarget_throughBackend_namesPackageAndTarget() {
        val backend = FailingUVBackend(sdistOnly)
        val result =
            runBlocking {
                backend.installDependenciesToTarget(
                    targetDir = "build/crossenv/$target",
                    pythonPlatform = target,
                    workingDir = File("/tmp/project/core"),
                )
            }
        val error = result.exceptionOrNull()
        assertIs<NoWheelForTargetException>(error)
        assertTrue("pycrypto==2.6.1" in error.message!! && target in error.message!!, error.message)
        assertTrue("Failed to download and build" in error.message!!, "raw uv text is kept")
    }

    @Test
    fun installDependenciesToTarget_keepsUnrecognizedFailureUntouched() {
        val backend = FailingUVBackend("error: something else broke")
        val result =
            runBlocking {
                backend.installDependenciesToTarget("d", target, workingDir = File("/tmp/p"))
            }
        assertEquals("error: something else broke", result.exceptionOrNull()?.message)
    }

    private class FailingUVBackend(
        private val output: String,
    ) : UVBackend() {
        override suspend fun executeCommand(
            command: List<String>,
            workingDir: File?,
        ): Result<String> = Result.failure(Exception(output))
    }
}
