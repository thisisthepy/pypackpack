package org.thisisthepy.python.multiplatform.packpack.dependency.middleware

import org.thisisthepy.python.multiplatform.packpack.utils.Platforms
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * The marker [MarkerPolicy.markerForTarget] writes must select exactly its own target when uv
 * evaluates it for `--python-platform <target>`, because that is how `ppp <package> sync` decides
 * what lands in `build/crossenv/<target>` (#49: `platform_machine == 'arm64'` for
 * `aarch64-linux-android` never matched, so sync reported success and installed nothing).
 *
 * uv is the judge, not a table copied into this test: for every canonical target in
 * [Platforms.SUPPORTED_TARGETS], `uv pip compile` resolves `probe ; <marker>` against a local
 * one-file wheel (`--no-index --find-links`, so no network) once per target. The probe must be
 * selected for the marker's own target and for no target whose (system, machine) key differs.
 *
 * Requires `uv` on `PATH` (CI installs it with astral-sh/setup-uv). A missing uv fails the test.
 */
class MarkerPolicyUvTest {
    private lateinit var workDir: File

    @BeforeTest
    fun setUp() {
        workDir = Files.createTempDirectory("marker-uv").toFile()
        writeProbeWheel(File(workDir, "wheels").apply { mkdirs() })
    }

    @AfterTest
    fun tearDown() {
        workDir.deleteRecursively()
    }

    @Test
    fun markerForTarget_selectsExactlyItsOwnTargetInUv() {
        val targets = Platforms.SUPPORTED_TARGETS.filter { Platforms.normalizeTarget(it) == it }
        val mismatches = mutableListOf<String>()
        for (markerTarget in targets) {
            val marker = MarkerPolicy.markerForTarget(markerTarget)
            val key = MarkerPolicy.targetKeyForTarget(markerTarget)
            for (platform in targets) {
                val expected = MarkerPolicy.targetKeyForTarget(platform) == key
                val selected = uvSelectsProbe(marker, platform)
                if (selected != expected) {
                    mismatches += "marker for $markerTarget ($marker) under --python-platform $platform: " +
                        "uv ${if (selected) "selects" else "skips"} it, expected ${if (expected) "selected" else "skipped"}"
                }
            }
        }
        assertEquals(emptyList(), mismatches, mismatches.joinToString("\n"))
    }

    private fun uvSelectsProbe(
        marker: String,
        platform: String,
    ): Boolean {
        val requirements = File(workDir, "requirements.in").apply { writeText("probe ; $marker\n") }
        val command =
            listOf(
                "uv", "pip", "compile", requirements.absolutePath, "--no-index",
                "--find-links", File(workDir, "wheels").absolutePath,
                "--python-platform", platform, "--python-version", "3.13", "--no-header", "--quiet",
            )
        val process =
            try {
                ProcessBuilder(command).directory(workDir).redirectErrorStream(true).start()
            } catch (e: java.io.IOException) {
                fail("uv is not on PATH; this test needs it: ${e.message}")
            }
        val output = process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0) fail("uv pip compile failed for --python-platform $platform:\n$output")
        return output.lines().any { it.startsWith("probe==") }
    }

    private fun writeProbeWheel(dir: File) {
        val files =
            linkedMapOf(
                "probe/__init__.py" to "",
                "probe-1.0.dist-info/METADATA" to "Metadata-Version: 2.1\nName: probe\nVersion: 1.0\n",
                "probe-1.0.dist-info/WHEEL" to "Wheel-Version: 1.0\nGenerator: test\nRoot-Is-Purelib: true\nTag: py3-none-any\n",
                "probe-1.0.dist-info/RECORD" to "",
            )
        ZipOutputStream(File(dir, "probe-1.0-py3-none-any.whl").outputStream()).use { zip ->
            for ((name, content) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
    }
}
