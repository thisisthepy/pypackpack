package org.thisisthepy.python.multiplatform.packpack.cli

import java.util.Properties

/**
 * Facts the build writes into the CLI's resources (`generateBuildInfo` in `cli/build.gradle.kts`).
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
