package org.thisisthepy.python.multiplatform.packpack.utils

import org.thisisthepy.python.multiplatform.packpack.dependency.frontend.*
import org.thisisthepy.python.multiplatform.packpack.compile.frontend.*
import org.thisisthepy.python.multiplatform.packpack.deploy.frontend.*

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
}
