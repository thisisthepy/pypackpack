package org.thisisthepy.python.multiplatform.packpack.utils

import org.thisisthepy.python.multiplatform.packpack.dependency.frontend.*
import org.thisisthepy.python.multiplatform.packpack.compile.frontend.*
import org.thisisthepy.python.multiplatform.packpack.deploy.frontend.*

import com.github.ajalt.clikt.core.obj
import com.github.ajalt.clikt.testing.test
import kotlin.test.Test
import kotlin.test.assertEquals

class BuildInfoTest {
    // packpack/build.gradle.kts passes `cliVersion` to the test JVM; `pypackpack version` must print that,
    // not a literal (docs/SPEC.md, CLI Version). publish-pypi.yml then checks it against pyproject.toml.
    @Test
    fun version_isTheOneTheBuildDeclares() {
        val expected = System.getProperty("pypackpack.expectedVersion")
        assertEquals(true, !expected.isNullOrBlank(), "the build passes no pypackpack.expectedVersion")
        assertEquals(expected, BuildInfo.version)
    }

    // `pypackpack --version` (and `ppp --version`, which runs the same binary) prints the first line
    // `pypackpack version` prints, without needing uv (docs/SPEC.md, CLI Version).
    @Test
    fun versionOption_printsTheSameVersionLineAsTheVersionCommand() {
        val expected = "PyPackPack version ${BuildInfo.version}"
        for (flag in listOf("--version", "-v")) {
            val result = PyPackPackCommand().test(flag)
            assertEquals(0, result.statusCode, "$flag: ${result.output}")
            assertEquals(expected, result.stdout.trim(), flag)
        }
    }

    // `pypackpack v` is the `version` command under a short name; it reaches the command (and so its
    // middleware), which is all this checks: the parse no longer stops at "no such subcommand v".
    @Test
    fun vAlias_resolvesToTheVersionCommand() {
        val root = PyPackPackCommand()
        assertEquals(listOf("version"), root.aliases()["v"])
    }

    // `pypackpack version` prints the pypackpack line, then the uv version the middleware reports
    // (docs/SPEC.md, CLI Version; #159). The real uv call is UVBackendVersionTest's.
    @Test
    fun versionCommand_printsTheUvVersionTheMiddlewareReports() {
        val middleware = RecordingMiddleware().apply { uvVersionResult = Result.success("uv 0.13.6 (abc 2026-01-01)\n") }
        val result = VersionCommand().apply { configureContext { obj = middleware } }.test("")

        assertEquals(0, result.statusCode, result.output)
        assertEquals(
            listOf("PyPackPack version ${BuildInfo.version}", "UV version: uv 0.13.6 (abc 2026-01-01)"),
            result.stdout.trim().lines(),
        )
    }

    // A failed uv check still prints the pypackpack line and says so on stderr.
    @Test
    fun versionCommand_reportsAFailedUvCheckOnStderr() {
        val middleware = RecordingMiddleware().apply { uvVersionResult = Result.failure(IllegalStateException("no uv")) }
        val result = VersionCommand().apply { configureContext { obj = middleware } }.test("")

        assertEquals("PyPackPack version ${BuildInfo.version}", result.stdout.trim())
        assertEquals("UV is installed but version check failed", result.stderr.trim())
    }
}
