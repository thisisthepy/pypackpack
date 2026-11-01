package org.thisisthepy.python.multiplatform.packpack.utils

import org.thisisthepy.python.multiplatform.packpack.dependency.frontend.*
import org.thisisthepy.python.multiplatform.packpack.compile.frontend.*
import org.thisisthepy.python.multiplatform.packpack.deploy.frontend.*

import java.util.Properties

/**
 * Facts the build writes into the CLI's resources (`generateBuildInfo` in `packpack/build.gradle.kts`).
 *
 * [version] is the CLI's version, and so the PyPI wheel's: `publish-pypi.yml` refuses to publish
 * unless the installed binary prints the version in `pyproject.toml` (docs/SPEC.md, CLI Version).
 */
internal object BuildInfo {
    val version: String by lazy {
        BuildInfo::class.java.getResourceAsStream("build-info.properties")
            ?.use { stream -> Properties().apply { load(stream) }.getProperty("version") }
            ?: error("build-info.properties is missing from the CLI's resources")
    }
}
