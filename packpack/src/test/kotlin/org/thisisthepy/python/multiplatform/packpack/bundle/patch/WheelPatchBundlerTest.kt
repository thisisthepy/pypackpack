package org.thisisthepy.python.multiplatform.packpack.bundle.patch

import org.junit.jupiter.api.io.TempDir
import org.thisisthepy.python.multiplatform.packpack.bundle.BundleRequest
import org.thisisthepy.python.multiplatform.packpack.bundle.BundleType
import org.thisisthepy.python.multiplatform.packpack.bundle.BundlerInterface
import org.thisisthepy.python.multiplatform.packpack.bundle.single.SingleWheelBundler
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Specification tests for bundle type `patch`.
 *
 * Spec source: `docs/SPEC.md` -> "Bundle Type" -> "`patch`: Produces a patch archive containing only
 * the changes relative to the existing primary bundle", plus the output-path convention
 * `<package>/build/packpack/<bundle type>/<build type>/<build level>` from the same document.
 *
 * The SPEC does not say what "the existing primary bundle" *is* -- `docs/SPEC.md`'s own "Package
 * deployment" section marks the whole versioned-patch-tracking design ("a git-like concept for patch
 * uploads ... tracking which primary version a patch is based on and its patch number") as
 * `Not yet implemented (target)`. `WheelPatchBundler` scopes itself to the one piece that is already
 * real: the `single` bundle type's own conventional output path
 * (`<package>/build/packpack/single/<buildType>/<buildLevel>`) already holds a wheel once
 * `pypackpack build` + `bundle single` have run for this package; whatever is sitting there when
 * `patch` runs *is* "the existing primary bundle" this class diffs against. There is no version
 * ledger beyond that single file -- the caller is responsible for keeping the right baseline wheel in
 * place before invoking `patch` (this is the same kind of caller responsibility `SingleWheelBundler`
 * already places on `pypackpack build` having run first).
 */
class WheelPatchBundlerTest {
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

    private fun writeDestdir(
        pkg: File,
        relativePath: String,
        content: ByteArray = "x".toByteArray(),
        prefix: String = "usr/local/lib/python3.13",
    ) {
        val file = File(File(pkg, "dist/$prefix/site-packages"), relativePath)
        file.parentFile.mkdirs()
        file.writeBytes(content)
    }

    /** Builds and installs a `single` bundle as the pre-existing "primary" baseline, then clears `dist/`. */
    private fun writeBaselineSingleBundle(
        pkg: File,
        target: String = "macos",
    ) {
        SingleWheelBundler().bundle(BundleRequest(packageDir = pkg, target = target)).getOrThrow()
    }

    private fun bundler() = BundlerInterface.create(BundleType.PATCH)

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
    fun create_returnsWheelPatchBundlerForPatchType() {
        assertTrue(BundlerInterface.create(BundleType.PATCH) is WheelPatchBundler)
    }

    @Test
    fun bundle_failsWhenNoPrimarySingleBundleExists() {
        val pkg = packageDir()
        writeDestdir(pkg, "core/__init__.py")
        // No prior `single` bundle at build/packpack/single/... -- nothing to diff against.

        val result = bundler().bundle(BundleRequest(packageDir = pkg, target = "macos"))

        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(message.contains("single"), message)
    }

    @Test
    fun bundle_writesPatchArchiveUnderConventionalOutputDirectory() {
        val pkg = packageDir()
        writeDestdir(pkg, "core/__init__.py", "v1\n".toByteArray())
        writeBaselineSingleBundle(pkg)
        pkg.resolve("dist").deleteRecursively()
        writeDestdir(pkg, "core/__init__.py", "v2\n".toByteArray())

        val result =
            bundler()
                .bundle(BundleRequest(packageDir = pkg, target = "macos"))
                .also { assertTrue(it.isSuccess, it.exceptionOrNull()?.stackTraceToString()) }
                .getOrThrow()

        assertEquals(
            File(pkg, "build/packpack/patch/debug/instant").canonicalFile,
            result.outputDir.canonicalFile,
        )
        assertTrue(result.artifactFile!!.name.endsWith(".whl.patch"), result.artifactFile!!.name)
        assertEquals(BundleType.PATCH, result.bundleType)
    }

    @Test
    fun bundle_containsOnlyAddedAndModifiedFilesNotUnchangedOnes() {
        val pkg = packageDir()
        writeDestdir(pkg, "core/__init__.py", "unchanged\n".toByteArray())
        writeDestdir(pkg, "core/old.py", "will change\n".toByteArray())
        writeBaselineSingleBundle(pkg)
        pkg.resolve("dist").deleteRecursively()

        writeDestdir(pkg, "core/__init__.py", "unchanged\n".toByteArray()) // same content
        writeDestdir(pkg, "core/old.py", "changed!\n".toByteArray()) // modified
        writeDestdir(pkg, "core/new.py", "brand new\n".toByteArray()) // added

        val result = bundler().bundle(BundleRequest(packageDir = pkg, target = "macos")).getOrThrow()
        val entries = zipEntryNames(result.artifactFile!!)

        assertFalse(entries.contains("core/__init__.py"), entries.toString())
        assertTrue(entries.contains("core/old.py"), entries.toString())
        assertTrue(entries.contains("core/new.py"), entries.toString())
        assertEquals(2, result.fileCount)
    }

    @Test
    fun bundle_manifestListsRemovedFiles() {
        val pkg = packageDir()
        writeDestdir(pkg, "core/__init__.py")
        writeDestdir(pkg, "core/removed.py", "gone soon\n".toByteArray())
        writeBaselineSingleBundle(pkg)
        pkg.resolve("dist").deleteRecursively()

        writeDestdir(pkg, "core/__init__.py") // unchanged, removed.py not rewritten -> removed

        val result = bundler().bundle(BundleRequest(packageDir = pkg, target = "macos")).getOrThrow()
        val entries = zipEntryNames(result.artifactFile!!)
        val manifestEntry = entries.first { it.endsWith("PATCH-MANIFEST") }
        val manifest = zipEntryText(result.artifactFile!!, manifestEntry)

        assertFalse(entries.contains("core/removed.py"), entries.toString())
        assertTrue(manifest.contains("core/removed.py"), manifest)
        assertTrue(manifest.lines().any { it.startsWith("D ") && it.contains("core/removed.py") }, manifest)
    }

    @Test
    fun bundle_manifestIsWrittenToFilesystemAndMatchesArchiveContent() {
        val pkg = packageDir()
        writeDestdir(pkg, "core/__init__.py")
        writeBaselineSingleBundle(pkg)
        pkg.resolve("dist").deleteRecursively()

        writeDestdir(pkg, "core/__init__.py")
        writeDestdir(pkg, "core/new.py")

        val result = bundler().bundle(BundleRequest(packageDir = pkg, target = "macos")).getOrThrow()
        val manifestFile = result.manifestFile

        assertTrue(manifestFile.isFile, "manifest not written: $manifestFile")

        val entries = zipEntryNames(result.artifactFile!!)
        val manifestEntry = entries.firstOrNull { it.endsWith("PATCH-MANIFEST") }
        assertNotNull(manifestEntry, "PATCH-MANIFEST not found in archive")

        val archiveContent = zipEntryText(result.artifactFile!!, manifestEntry!!)
        assertEquals(archiveContent, manifestFile.readText())
    }

    @Test
    fun bundle_manifestRecordsBaseAndNewVersion() {
        val pkg = packageDir(name = "core", version = "0.1.0")
        writeDestdir(pkg, "core/__init__.py")
        writeBaselineSingleBundle(pkg)
        pkg.resolve("dist").deleteRecursively()

        File(pkg, "pyproject.toml").writeText(
            """
            [project]
            name = "core"
            version = "0.2.0"
            """.trimIndent(),
        )
        writeDestdir(pkg, "core/__init__.py", "v2\n".toByteArray())

        val result = bundler().bundle(BundleRequest(packageDir = pkg, target = "macos")).getOrThrow()
        val entries = zipEntryNames(result.artifactFile!!)
        val manifestEntry = entries.first { it.endsWith("PATCH-MANIFEST") }
        val manifest = zipEntryText(result.artifactFile!!, manifestEntry)

        assertTrue(manifest.contains("Base-Version: 0.1.0"), manifest)
        assertTrue(manifest.contains("New-Version: 0.2.0"), manifest)
        assertTrue(result.artifactFile!!.name.contains("0.1.0_to_0.2.0"), result.artifactFile!!.name)
    }

    @Test
    fun bundle_succeedsWithEmptyPatchWhenNothingChanged() {
        val pkg = packageDir()
        writeDestdir(pkg, "core/__init__.py", "same\n".toByteArray())
        writeBaselineSingleBundle(pkg)
        pkg.resolve("dist").deleteRecursively()

        writeDestdir(pkg, "core/__init__.py", "same\n".toByteArray())

        val result = bundler().bundle(BundleRequest(packageDir = pkg, target = "macos")).getOrThrow()

        assertEquals(0, result.fileCount)
        assertTrue(result.artifactFile!!.isFile)
    }

    @Test
    fun bundle_isDeterministicAcrossRuns() {
        val pkg = packageDir()
        writeDestdir(pkg, "core/__init__.py", "v1\n".toByteArray())
        writeBaselineSingleBundle(pkg)
        pkg.resolve("dist").deleteRecursively()
        writeDestdir(pkg, "core/__init__.py", "v2\n".toByteArray())
        writeDestdir(pkg, "core/new.py")

        val first = bundler().bundle(BundleRequest(packageDir = pkg, target = "macos", overwrite = true)).getOrThrow()
        val firstBytes = first.artifactFile!!.readBytes()
        val second = bundler().bundle(BundleRequest(packageDir = pkg, target = "macos", overwrite = true)).getOrThrow()
        val secondBytes = second.artifactFile!!.readBytes()

        assertTrue(firstBytes.contentEquals(secondBytes), "patch bytes must be reproducible across runs")
    }

    @Test
    fun bundle_rejectsBuildLevelsThatAreNotImplementedYet() {
        val pkg = packageDir()
        writeDestdir(pkg, "core/__init__.py")
        writeBaselineSingleBundle(pkg)
        pkg.resolve("dist").deleteRecursively()
        writeDestdir(pkg, "core/__init__.py")

        listOf("bytecode", "native", "mixed").forEach { level ->
            val result = bundler().bundle(BundleRequest(packageDir = pkg, target = "macos", buildLevel = level))
            assertTrue(result.isFailure, "build level '$level' should not silently succeed")
        }
    }

    @Test
    fun bundle_rejectsUnknownTarget() {
        val pkg = packageDir()
        writeDestdir(pkg, "core/__init__.py")
        writeBaselineSingleBundle(pkg)
        pkg.resolve("dist").deleteRecursively()
        writeDestdir(pkg, "core/__init__.py")

        val result = bundler().bundle(BundleRequest(packageDir = pkg, target = "definitely-not-a-target"))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("definitely-not-a-target"))
    }
}
