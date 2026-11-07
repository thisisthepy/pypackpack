package org.thisisthepy.python.multiplatform.packpack.utils

import org.thisisthepy.python.multiplatform.packpack.dependency.frontend.*
import org.thisisthepy.python.multiplatform.packpack.compile.frontend.*
import org.thisisthepy.python.multiplatform.packpack.deploy.frontend.*

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
}
