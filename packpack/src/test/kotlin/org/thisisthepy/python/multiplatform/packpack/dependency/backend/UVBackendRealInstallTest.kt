package org.thisisthepy.python.multiplatform.packpack.dependency.backend

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Runs the real `uv pip install -r pyproject.toml --target <dir> --python-platform <triple>` that
 * [UVBackend.installDependenciesToTarget] builds, for the two mobile targets, and inspects what
 * lands on disk (`docs/SPEC.md` in this repository, *Per-package target dependency management*,
 * `sync`). [UVBackendTest] and `CrossEnvTest.syncDependenciesInstallsPerTargetCrossenvDirectories`
 * only check the command line against a recording backend; this is the test that proves uv
 * actually picks the target's wheels.
 *
 * **Requires network access and `uv`.** Nothing is faked: the wheels come from PyPI, and
 * [UVBackend] uses `uv` from `PATH` or downloads it to `~/.pypackpack/uv` (which also needs the
 * network). Like `ResourceBundlerTest`'s dependency on `python3`, a missing prerequisite fails the
 * test with uv's own output rather than skipping it. The `network` tag names the requirement so a
 * run can select or exclude it (`--tests`, or `includeTags`/`excludeTags` in Gradle).
 *
 * Packages, both pinned so the expected tags cannot drift:
 * - `six==1.17.0`: pure Python (`py3-none-any`), one module `six.py`.
 * - `markupsafe==3.0.4`: ships `markupsafe/_speedups` as a C extension and publishes
 *   `android_24_arm64_v8a` and `ios_13_0_arm64_iphoneos` wheels on PyPI for cp313 and cp314.
 *
 * `--python-version 3.13` (the runtime's version) pins the ABI independently of whichever Python
 * the CI host has, and `--only-binary :all:` stops uv from building an sdist with the host
 * compiler: without it uv builds a host wheel and only then rejects it as incompatible.
 *
 * Swapping the target for the host fails every native assertion: with `aarch64-apple-darwin` uv
 * installs `_speedups.cpython-313-darwin.so` tagged `cp313-cp313-macosx_11_0_arm64`, and with
 * `x86_64-unknown-linux-gnu` `_speedups.cpython-313-x86_64-linux-gnu.so` tagged
 * `cp313-cp313-manylinux_2_17_x86_64` -- neither matches the expected suffix or tag below, and
 * [assertNoHostWheels] rejects them explicitly.
 */
@Tag("network")
class UVBackendRealInstallTest {
    @Test
    fun installDependenciesToTarget_installsAndroidWheelsIntoCrossenvDirectory() {
        assertInstallsTargetWheels(
            target = "aarch64-linux-android",
            extensionSuffix = ".cpython-313-aarch64-linux-android.so",
            wheelTag = "cp313-cp313-android_24_arm64_v8a",
        )
    }

    @Test
    fun installDependenciesToTarget_installsIosWheelsIntoCrossenvDirectory() {
        assertInstallsTargetWheels(
            target = "arm64-apple-ios",
            extensionSuffix = ".cpython-313-iphoneos.so",
            wheelTag = "cp313-cp313-ios_13_0_arm64_iphoneos",
        )
    }

    @Test
    fun installDependenciesToTarget_installsRequirementListWithoutPyproject() {
        val packageDir = Files.createTempDirectory("uvbackend-reqlist").toFile()
        try {
            val targetDir = File(packageDir, "build/crossenv/aarch64-linux-android")
            val result =
                runBlocking {
                    UVBackend().installDependenciesToTarget(
                        targetDir = targetDir.absolutePath,
                        pythonPlatform = "aarch64-linux-android",
                        extraArgs = mapOf("python-version" to "3.13", "only-binary" to ":all:"),
                        workingDir = packageDir,
                        requirements = listOf("six==1.17.0"),
                    )
                }
            result.exceptionOrNull()?.let {
                fail("uv pip install of a requirement list failed (needs network access and uv):\n${it.message}", it)
            }
            assertFalse(File(packageDir, "pyproject.toml").exists())
            assertTrue(File(targetDir, "six.py").isFile, "six.py missing from $targetDir")
        } finally {
            packageDir.deleteRecursively()
        }
    }

    private fun assertInstallsTargetWheels(
        target: String,
        extensionSuffix: String,
        wheelTag: String,
    ) {
        val packageDir = Files.createTempDirectory("uvbackend-realwheel").toFile()
        try {
            File(packageDir, "pyproject.toml").writeText(
                """
                [project]
                name = "core"
                version = "0.1.0"
                requires-python = ">=3.13"
                dependencies = ["six==1.17.0", "markupsafe==3.0.4"]
                """.trimIndent() + "\n",
            )
            // The same layout CrossEnv.syncDependencies passes: <package>/build/crossenv/<triple>.
            val targetDir = File(packageDir, "build/crossenv/$target")

            val result =
                runBlocking {
                    UVBackend().installDependenciesToTarget(
                        targetDir = targetDir.absolutePath,
                        pythonPlatform = target,
                        extraArgs = mapOf("python-version" to "3.13", "only-binary" to ":all:"),
                        workingDir = packageDir,
                    )
                }
            result.exceptionOrNull()?.let {
                fail("uv pip install for $target failed (this test needs network access and uv):\n${it.message}", it)
            }

            // Pure-Python package.
            assertTrue(File(targetDir, "six.py").isFile, "six.py missing from $targetDir")
            assertTrue(
                readWheelTags(targetDir, "six-1.17.0.dist-info").contains("py3-none-any"),
                "six is not the pure-Python wheel",
            )

            // Native package: the extension module and the wheel tag are the target's.
            val markupsafe = File(targetDir, "markupsafe")
            assertTrue(File(markupsafe, "__init__.py").isFile, "markupsafe missing from $targetDir")
            val extensions = markupsafe.listFiles { f -> f.name.startsWith("_speedups.") && f.name.endsWith(".so") }.orEmpty()
            assertEquals(
                listOf("_speedups$extensionSuffix"),
                extensions.map { it.name },
                "markupsafe's extension module is not built for $target",
            )
            assertEquals(
                listOf(wheelTag),
                readWheelTags(targetDir, "markupsafe-3.0.4.dist-info"),
                "markupsafe's wheel is not the $target wheel",
            )
            assertNoHostWheels(targetDir)

            // uv wrote only into the target directory: no environment next to the package.
            assertFalse(File(packageDir, ".venv").exists(), "uv created a .venv in the package directory")
        } finally {
            packageDir.deleteRecursively()
        }
    }

    private fun readWheelTags(
        targetDir: File,
        distInfo: String,
    ): List<String> {
        val wheel = File(targetDir, "$distInfo/WHEEL")
        assertTrue(wheel.isFile, "$wheel missing")
        return wheel
            .readLines()
            .filter { it.startsWith("Tag:") }
            .map { it.removePrefix("Tag:").trim() }
    }

    /** Every desktop platform tag a host install could produce on a CI runner or a developer machine. */
    private fun assertNoHostWheels(targetDir: File) {
        val hostTagMarkers = listOf("macosx", "manylinux", "musllinux", "linux_", "win")
        targetDir
            .listFiles { f -> f.isDirectory && f.name.endsWith(".dist-info") }
            .orEmpty()
            .forEach { distInfo ->
                val tags = readWheelTags(targetDir, distInfo.name)
                val hostTags = tags.filter { tag -> hostTagMarkers.any { tag.substringAfterLast('-').startsWith(it) } }
                assertTrue(hostTags.isEmpty(), "${distInfo.name} has host wheel tags $hostTags")
            }
    }
}
