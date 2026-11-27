package org.thisisthepy.python.multiplatform.packpack.dependency.backend

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * docs/SPEC.md *CLI Version* (#159): the uv line of `pypackpack version` comes from
 * [UVBackend.getVersion], which runs the real `uv --version` (from `PATH`, or the copy pypackpack
 * downloads to `~/.pypackpack/uv`). Like [UVBackendRealInstallTest], a missing uv fails with its output.
 */
class UVBackendVersionTest {
    @Test
    fun getVersion_runsTheRealUvAndReturnsItsVersionLine() {
        val result = runBlocking { UVBackend().getVersion() }

        val line = result.getOrElse { throw AssertionError("uv --version failed: ${it.message}", it) }.trim()
        assertTrue(Regex("""^uv \d+\.\d+\.\d+\b""").containsMatchIn(line), "not uv's version line: '$line'")
    }
}
