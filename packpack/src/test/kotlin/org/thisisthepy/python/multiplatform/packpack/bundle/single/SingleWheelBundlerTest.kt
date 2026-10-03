package org.thisisthepy.python.multiplatform.packpack.bundle.single

import org.junit.jupiter.api.io.TempDir
import org.thisisthepy.python.multiplatform.packpack.bundle.BundleRequest
import org.thisisthepy.python.multiplatform.packpack.bundle.BundleType
import org.thisisthepy.python.multiplatform.packpack.bundle.BundlerInterface
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Specification tests for bundle type `single`.
 *
 * Spec source: `docs/SPEC.md` -> "Bundle Type" -> "`single`: Produces a wheel-like archive that
 * includes only the current package", plus the output-path convention
 * `<package>/build/packpack/<bundle type>/<build type>/<build level>` from the same document.
 *
 * The SPEC says nothing about wheel internals (destdir layout, filename/tag scheme, METADATA/WHEEL/
 * RECORD contents). Those choices -- and the reasoning behind each -- are written down in
 * `SingleWheelBundler`'s KDoc and pinned here, following the precedent `ResourceBundlerTest` set for
 * `resource`.
 *
 * Tests build a *synthetic* Meson destdir by hand (`writeDestdir`) instead of shelling out to a real
 * `meson`/`ninja` install, matching `ResourceBundlerTest`'s approach of writing fixture files directly
 * rather than invoking the tool it is downstream of. The destdir shapes used here (nested under
 * `usr/local/lib/python3.13/site-packages`, or under a bare `site-packages` with no prefix at all) were
 * chosen to match what a real `meson install --destdir=...` was observed to produce for a POSIX host
 * install (verified locally by running an actual `meson setup`/`compile`/`install` against a throwaway
 * C-extension project: the destdir nested the payload under `usr/local/lib/python3.13/site-packages`,
 * i.e. `<destdir>/<absolute prefix, leading slash stripped>/lib/pythonX.Y/site-packages`) plus the
 * no-prefix-nesting case a differently configured host/target could plausibly produce.
 */
class SingleWheelBundlerTest {
    @TempDir
    lateinit var tempDir: File

    private fun packageDir(
        name: String = "core",
        version: String = "0.1.0",
    ): File {
        val dir = File(tempDir, name)
        dir.mkdirs()
        File(dir, "pyproject.toml").writeText(
            """
            [project]
            name = "$name"
            version = "$version"
            """.trimIndent(),
        )
        return dir
    }

    /** Writes a file under `<pkg>/dist/<prefixSegments>/site-packages/<relativePath>`. */
    private fun writeDestdir(
        pkg: File,
        relativePath: String,
        content: ByteArray = "x".toByteArray(),
        prefix: String = "usr/local/lib/python3.13",
        target: String = org.thisisthepy.python.multiplatform.packpack.utils.Platforms.normalizeTarget("macos")!!,
        type: String = "debug",
        level: String = "instant",
    ) {
        val file = File(File(pkg, "dist/$target/$type/$level/$prefix/site-packages"), relativePath)
        file.parentFile.mkdirs()
        file.writeBytes(content)
    }

    private fun bundler() = BundlerInterface.create(BundleType.SINGLE)

    private fun zipEntryNames(whl: File): Set<String> = ZipFile(whl).use { zf -> zf.entries().asSequence().map { it.name }.toSet() }

    private fun zipEntryText(
        whl: File,
        entryName: String,
    ): String =
        ZipFile(whl).use { zf ->
            val entry = zf.getEntry(entryName) ?: error("entry not found: $entryName")
            zf.getInputStream(entry).bufferedReader().readText()
        }

    @Test
    fun create_returnsSingleWheelBundlerForSingleType() {
        assertTrue(BundlerInterface.create(BundleType.SINGLE) is SingleWheelBundler)
    }

    @Test
    fun bundle_writesWheelUnderConventionalOutputDirectory() {
        val pkg = packageDir()
        writeDestdir(pkg, "core/__init__.py", "VERSION = '0.1.0'\n".toByteArray())

        val result =
            bundler()
                .bundle(BundleRequest(packageDir = pkg, target = "macos"))
                .also { assertTrue(it.isSuccess, it.exceptionOrNull()?.stackTraceToString()) }
                .getOrThrow()

        assertEquals(
            File(pkg, "build/packpack/single/debug/instant").canonicalFile,
            result.outputDir.canonicalFile,
        )
        assertNotNull(result.artifactFile, "single bundle must produce a .whl artifact")
        assertTrue(result.artifactFile.isFile)
        assertEquals(result.outputDir.canonicalFile, result.artifactFile.parentFile.canonicalFile)
        assertEquals(BundleType.SINGLE, result.bundleType)
    }

    @Test
    fun bundle_wheelFilenameEncodesNamedVersionAndPlatformTag() {
        val pkg = packageDir(name = "My-Core", version = "1.2.3")
        writeDestdir(pkg, "my_core/__init__.py")

        val result = bundler().bundle(BundleRequest(packageDir = pkg, target = "macos")).getOrThrow()

        // PEP 427 filename escaping: runs of -_. -> _, uppercase -> lowercase, for the *name* component.
        assertEquals("my_core-1.2.3-cp313-cp313-macosx_11_0_arm64.whl", result.artifactFile!!.name)
    }

    @Test
    fun bundle_locatesSitePackagesUnderPosixPrefixNesting() {
        val pkg = packageDir()
        writeDestdir(pkg, "core/__init__.py", prefix = "usr/local/lib/python3.13", target = "x86_64-unknown-linux-gnu")

        val result = bundler().bundle(BundleRequest(packageDir = pkg, target = "linux")).getOrThrow()

        assertTrue(zipEntryNames(result.artifactFile!!).contains("core/__init__.py"))
    }

    @Test
    fun bundle_locatesSitePackagesWithNoPrefixNesting() {
        val pkg = packageDir()
        writeDestdir(pkg, "core/__init__.py", prefix = "", target = "x86_64-unknown-linux-gnu")

        val result = bundler().bundle(BundleRequest(packageDir = pkg, target = "linux")).getOrThrow()

        assertTrue(zipEntryNames(result.artifactFile!!).contains("core/__init__.py"))
    }

    @Test
    fun bundle_failsWhenNoCompiledOutputExists() {
        val pkg = packageDir()
        // No dist/ directory at all -- `pypackpack build` was never run.

        val result = bundler().bundle(BundleRequest(packageDir = pkg, target = "macos"))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("build"), result.exceptionOrNull()?.message)
    }

    @Test
    fun bundle_failsWhenDestdirHasNoSitePackagesDirectory() {
        val pkg = packageDir()
        File(pkg, "dist/aarch64-apple-darwin/debug/instant/usr/local/lib/python3.13").mkdirs()
        File(pkg, "dist/aarch64-apple-darwin/debug/instant/usr/local/lib/python3.13/not-site-packages.txt").writeText("x")

        val result = bundler().bundle(BundleRequest(packageDir = pkg, target = "macos"))

        assertTrue(result.isFailure)
        assertTrue(
            result.exceptionOrNull()?.message.orEmpty().contains("site-packages"),
            result.exceptionOrNull()?.message,
        )
    }

    @Test
    fun bundle_wheelContainsMetadataWheelAndRecord() {
        val pkg = packageDir(name = "core", version = "0.1.0")
        writeDestdir(pkg, "core/__init__.py", target = "aarch64-apple-darwin")

        val result = bundler().bundle(BundleRequest(packageDir = pkg, target = "macos")).getOrThrow()
        val entries = zipEntryNames(result.artifactFile!!)

        assertTrue(entries.contains("core/__init__.py"), entries.toString())
        assertTrue(entries.contains("core-0.1.0.dist-info/METADATA"), entries.toString())
        assertTrue(entries.contains("core-0.1.0.dist-info/WHEEL"), entries.toString())
        assertTrue(entries.contains("core-0.1.0.dist-info/RECORD"), entries.toString())
    }

    @Test
    fun bundle_manifestIsWrittenToFilesystemAndMatchesArchiveContent() {
        val pkg = packageDir(name = "core", version = "0.1.0")
        writeDestdir(pkg, "core/__init__.py", target = "aarch64-apple-darwin")

        val result = bundler().bundle(BundleRequest(packageDir = pkg, target = "macos")).getOrThrow()
        val manifestFile = result.manifestFile

        assertTrue(manifestFile.isFile, "manifest not written: $manifestFile")

        val entries = zipEntryNames(result.artifactFile!!)
        val recordEntry = entries.firstOrNull { it.endsWith(".dist-info/RECORD") }
        assertNotNull(recordEntry, "RECORD not found in archive")

        val archiveContent = zipEntryText(result.artifactFile!!, recordEntry!!)
        assertEquals(archiveContent, manifestFile.readText())
    }

    @Test
    fun bundle_metadataDeclaresNameAndVersion() {
        val pkg = packageDir(name = "core", version = "0.1.0")
        writeDestdir(pkg, "core/__init__.py", target = "aarch64-apple-darwin")

        val result = bundler().bundle(BundleRequest(packageDir = pkg, target = "macos")).getOrThrow()
        val metadata = zipEntryText(result.artifactFile!!, "core-0.1.0.dist-info/METADATA")

        assertTrue(metadata.contains("Name: core"), metadata)
        assertTrue(metadata.contains("Version: 0.1.0"), metadata)
        assertTrue(metadata.lines().first().startsWith("Metadata-Version:"), metadata)
    }

    @Test
    fun bundle_wheelFileDeclaresTagAndRootIsPurelib() {
        val pkg = packageDir(name = "core", version = "0.1.0")
        writeDestdir(pkg, "core/__init__.py", target = "aarch64-apple-darwin")
        writeDestdir(pkg, "core/_ext.cpython-313-darwin.so")

        val result = bundler().bundle(BundleRequest(packageDir = pkg, target = "macos")).getOrThrow()
        val wheel = zipEntryText(result.artifactFile!!, "core-0.1.0.dist-info/WHEEL")

        assertTrue(wheel.contains("Wheel-Version:"), wheel)
        assertTrue(wheel.contains("Tag: cp313-cp313-macosx_11_0_arm64"), wheel)
        assertTrue(wheel.contains("Root-Is-Purelib: false"), wheel)
    }

    @Test
    fun bundle_rootIsPurelibTrueWhenNoNativeExtensionPresent() {
        val pkg = packageDir(name = "core", version = "0.1.0")
        writeDestdir(pkg, "core/__init__.py", target = "aarch64-apple-darwin")

        val result = bundler().bundle(BundleRequest(packageDir = pkg, target = "macos")).getOrThrow()
        val wheel = zipEntryText(result.artifactFile!!, "core-0.1.0.dist-info/WHEEL")

        assertTrue(wheel.contains("Root-Is-Purelib: true"), wheel)
    }

    @Test
    fun bundle_recordListsFilesWithSha256HashesAndSizeButNotItself() {
        val pkg = packageDir(name = "core", version = "0.1.0")
        val content = "VERSION = '0.1.0'\n".toByteArray()
        writeDestdir(pkg, "core/__init__.py", content)

        val result = bundler().bundle(BundleRequest(packageDir = pkg, target = "macos")).getOrThrow()
        val record = zipEntryText(result.artifactFile!!, "core-0.1.0.dist-info/RECORD")
        val lines = record.trim().lines()

        val initLine = lines.first { it.startsWith("core/__init__.py,") }
        val parts = initLine.split(",")
        assertEquals(3, parts.size, initLine)
        assertTrue(parts[1].startsWith("sha256="), initLine)
        assertEquals(content.size.toString(), parts[2], initLine)

        val recordLine = lines.first { it.startsWith("core-0.1.0.dist-info/RECORD,") }
        assertEquals("core-0.1.0.dist-info/RECORD,,", recordLine)
    }

    @Test
    fun bundle_excludesPycacheAndDotfilesButKeepsNativeExtensions() {
        val pkg = packageDir()
        writeDestdir(pkg, "core/__init__.py", target = "aarch64-apple-darwin")
        writeDestdir(pkg, "core/_ext.cpython-313-darwin.so")
        writeDestdir(pkg, "core/__pycache__/__init__.cpython-313.pyc")
        writeDestdir(pkg, "core/.DS_Store")

        val result = bundler().bundle(BundleRequest(packageDir = pkg, target = "macos")).getOrThrow()
        val entries = zipEntryNames(result.artifactFile!!)

        assertTrue(entries.contains("core/_ext.cpython-313-darwin.so"), entries.toString())
        assertFalse(entries.any { it.contains("__pycache__") }, entries.toString())
        assertFalse(entries.any { it.contains(".DS_Store") }, entries.toString())
    }

    @Test
    fun bundle_isDeterministicAcrossRuns() {
        val pkg = packageDir()
        writeDestdir(pkg, "core/__init__.py", target = "aarch64-apple-darwin")
        writeDestdir(pkg, "core/b.py")
        writeDestdir(pkg, "core/a.py")

        val first = bundler().bundle(BundleRequest(packageDir = pkg, target = "macos", overwrite = true)).getOrThrow()
        val firstBytes = first.artifactFile!!.readBytes()
        val second = bundler().bundle(BundleRequest(packageDir = pkg, target = "macos", overwrite = true)).getOrThrow()
        val secondBytes = second.artifactFile!!.readBytes()

        assertTrue(firstBytes.contentEquals(secondBytes), "wheel bytes must be reproducible across runs")
    }

    @Test
    fun bundle_refusesToOverwriteExistingOutputUnlessAsked() {
        val pkg = packageDir()
        writeDestdir(pkg, "core/__init__.py", target = "aarch64-apple-darwin")
        bundler().bundle(BundleRequest(packageDir = pkg, target = "macos")).getOrThrow()

        val second = bundler().bundle(BundleRequest(packageDir = pkg, target = "macos"))

        assertTrue(second.isFailure)
        assertTrue(second.exceptionOrNull()?.message.orEmpty().contains("--overwrite"))
    }

    @Test
    fun bundle_rejectsBuildLevelsThatAreNotImplementedYet() {
        val pkg = packageDir()
        writeDestdir(pkg, "core/__init__.py", target = "aarch64-apple-darwin")

        listOf("bytecode", "native", "mixed").forEach { level ->
            val result = bundler().bundle(BundleRequest(packageDir = pkg, target = "macos", buildLevel = level))
            assertTrue(result.isFailure, "build level '$level' should not silently succeed")
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains(level))
        }
    }

    @Test
    fun bundle_rejectsUnknownTarget() {
        val pkg = packageDir()
        writeDestdir(pkg, "core/__init__.py", target = "aarch64-apple-darwin")

        val result = bundler().bundle(BundleRequest(packageDir = pkg, target = "definitely-not-a-target"))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("definitely-not-a-target"))
    }

    /**
     * Platform tag per target family. Standards-based where a standard exists (PEP 425/600/656 for
     * Windows/macOS/manylinux/musllinux); PEP 730/738 for iOS/Android (accepted PEPs, not our
     * invention); a documented, cited convention for wasm (Pyodide's own pre-PEP-783 tag scheme,
     * matching this project's `wasm32-pyodide2024` target spelling exactly). See `SingleWheelBundler`
     * KDoc for the citations and the floor-version assumptions spelled out per family.
     */
    @Test
    fun bundle_platformTagPerFamily() {
        val cases =
            mapOf(
                "x86_64-pc-windows-msvc" to "win_amd64",
                "aarch64-pc-windows-msvc" to "win_arm64",
                "i686-pc-windows-msvc" to "win32",
                "x86_64-apple-darwin" to "macosx_10_9_x86_64",
                "aarch64-apple-darwin" to "macosx_11_0_arm64",
                "x86_64-unknown-linux-gnu" to "linux_x86_64",
                "aarch64-unknown-linux-gnu" to "linux_aarch64",
                "riscv64-unknown-linux" to "linux_riscv64",
                "x86_64-unknown-linux-musl" to "musllinux_1_2_x86_64",
                "aarch64-unknown-linux-musl" to "musllinux_1_2_aarch64",
                "x86_64-manylinux2014" to "manylinux_2_17_x86_64",
                "x86_64-manylinux_2_28" to "manylinux_2_28_x86_64",
                "aarch64-manylinux_2_17" to "manylinux_2_17_aarch64",
                "aarch64-linux-android" to "android_21_arm64_v8a",
                "x86_64-linux-android" to "android_21_x86_64",
                "arm64-apple-ios" to "ios_13_0_arm64_iphoneos",
                "arm64-apple-ios-simulator" to "ios_13_0_arm64_iphonesimulator",
                "x86_64-apple-ios-simulator" to "ios_13_0_x86_64_iphonesimulator",
                "wasm32-pyodide2024" to "pyodide_2024_0_wasm32",
            )

        cases.forEach { (target, expectedTag) ->
            val pkg = packageDir(name = "tagcheck-${target.hashCode()}")
            
            // Normalize target just like the bundler does internally
            val normalizedTarget = org.thisisthepy.python.multiplatform.packpack.utils.Platforms.normalizeTarget(target) ?: "aarch64-apple-darwin"
            writeDestdir(pkg, "core/__init__.py", target = normalizedTarget)

            val result =
                bundler()
                    .bundle(BundleRequest(packageDir = pkg, target = target))
                    .also { assertTrue(it.isSuccess, "target=$target: ${it.exceptionOrNull()?.stackTraceToString()}") }
                    .getOrThrow()

            val artifactFile = result.artifactFile!!
            assertTrue(
                artifactFile.name.endsWith("-$expectedTag.whl"),
                "target=$target expected tag '$expectedTag' in filename '${artifactFile.name}'",
            )
        }
    }

    /**
     * `BundleRequest.minSdk` is `toolchain`'s per-variant Android min SDK/API level
     * (`f60bc3b` in `toolchain`), which had nowhere to go before this field existed. The Android PEP
     * 738 wheel tag (`android_<api-level>_<abi>`) is where it now lands: previously the api-level
     * segment was hardcoded to `21` because `Platforms.kt`'s `TARGET_ALIASES` collapses
     * `android_21_*`/`android_24_*` onto one canonical target, so no finer distinction survived the
     * target string. This is the actual, behavior-changing consumer of the field.
     */
    @Test
    fun bundle_androidPlatformTagUsesDeclaredMinSdkWhenProvided() {
        val pkg = packageDir(name = "minsdk-declared")
        writeDestdir(pkg, "core/__init__.py", target = "aarch64-linux-android")

        val result =
            bundler()
                .bundle(BundleRequest(packageDir = pkg, target = "aarch64-linux-android", minSdk = 24))
                .also { assertTrue(it.isSuccess, it.exceptionOrNull()?.stackTraceToString()) }
                .getOrThrow()

        assertTrue(
            result.artifactFile!!.name.endsWith("-android_24_arm64_v8a.whl"),
            "expected declared minSdk 24 in tag, got '${result.artifactFile!!.name}'",
        )
    }

    @Test
    fun bundle_androidPlatformTagFallsBackToThePep738FloorWhenMinSdkIsNotDeclared() {
        val pkg = packageDir(name = "minsdk-undeclared")
        writeDestdir(pkg, "core/__init__.py", target = "aarch64-linux-android")

        val result =
            bundler()
                .bundle(BundleRequest(packageDir = pkg, target = "aarch64-linux-android"))
                .getOrThrow()

        assertTrue(result.artifactFile!!.name.endsWith("-android_21_arm64_v8a.whl"))
    }

    @Test
    fun bundle_rejectsMinSdkBelowThePep738Floor() {
        val pkg = packageDir()
        writeDestdir(pkg, "core/__init__.py", target = "aarch64-linux-android")

        val result =
            bundler().bundle(BundleRequest(packageDir = pkg, target = "aarch64-linux-android", minSdk = 16))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("21"), result.exceptionOrNull()?.message)
    }

    @Test
    fun bundle_rejectsMinSdkDeclaredForANonAndroidTarget() {
        val pkg = packageDir()
        writeDestdir(pkg, "core/__init__.py", target = "aarch64-apple-darwin")

        val result = bundler().bundle(BundleRequest(packageDir = pkg, target = "macos", minSdk = 24))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("macos"), result.exceptionOrNull()?.message)
    }
}
