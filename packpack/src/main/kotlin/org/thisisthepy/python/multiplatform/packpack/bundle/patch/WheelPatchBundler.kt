package org.thisisthepy.python.multiplatform.packpack.bundle.patch

import org.thisisthepy.python.multiplatform.packpack.bundle.BundleRequest
import org.thisisthepy.python.multiplatform.packpack.bundle.BundleResult
import org.thisisthepy.python.multiplatform.packpack.bundle.BundleType
import org.thisisthepy.python.multiplatform.packpack.bundle.BundlerInterface
import org.thisisthepy.python.multiplatform.packpack.utils.Platforms
import org.thisisthepy.python.multiplatform.packpack.utils.toml.TomlEditor
import org.thisisthepy.python.multiplatform.packpack.utils.toml.TomlValue
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Bundle type `patch`: a `.whl.patch` archive containing only the changes relative to the existing
 * primary bundle.
 *
 * ## What the SPEC actually says
 *
 * `docs/SPEC.md` states exactly one sentence about this bundle type -- *"`patch`: Produces a patch
 * archive containing only the changes relative to the existing primary bundle"* -- plus the same
 * output path convention every other bundle type uses.
 *
 * ## What "the existing primary bundle" is -- the part the SPEC does not (yet) say
 *
 * `docs/SPEC.md`'s own "Package deployment" section marks real patch *tracking* -- "a git-like
 * concept for patch uploads ... tracking which primary version a patch is based on and its patch
 * number" -- as `Not yet implemented (target)`, along with server-side change upload and a
 * management page. None of that exists yet, and building it here would be inventing a versioning
 * ledger this task was never asked for.
 *
 * What *does* already exist is the `single` bundle type's own conventional output path:
 * `<package>/build/packpack/single/<buildType>/<buildLevel>` holds exactly one `.whl` once
 * `pypackpack build` + `bundle single` have run (`SingleWheelBundler.prepareOutputDir` already
 * enforces "at most one wheel here unless `--overwrite`"). `WheelPatchBundler` treats whatever wheel
 * is sitting there, for the same `buildType`/`buildLevel`, as "the existing primary bundle" and diffs
 * the package's *current* `dist/site-packages` (the same Meson-destdir payload `SingleWheelBundler`
 * itself reads) against it. There is no ledger beyond that one file: the caller is responsible for
 * keeping the right baseline wheel in place before invoking `patch` -- the same kind of caller
 * responsibility `SingleWheelBundler` already places on `pypackpack build` having run first, and
 * `FatWheelBundler` places on a workspace dependency having been built first.
 *
 * If that directory holds no wheel (or more than one -- refuses to guess, same as
 * `SingleWheelBundler`'s destdir lookup), `bundle` fails naming the missing/ambiguous `single`
 * bundle rather than silently treating "no baseline" as "everything is new".
 *
 * ## What the patch archive contains
 *
 * Compares the current payload against the baseline wheel's entries (excluding the baseline's own
 * `*.dist-info/` metadata -- that is regenerated per build and is not "package content" to diff) by
 * relative path and SHA-256:
 *
 * - **Added** (in current, not in baseline) and **modified** (in both, different hash): the file's
 *   full new bytes are written into the patch archive at its normal path -- a content patch, not a
 *   binary delta. A byte-level delta format is real complexity this class does not invent; "ship the
 *   new bytes for what changed" is the simplest thing that is still "only the changes", matching the
 *   SPEC sentence literally.
 * - **Removed** (in baseline, not in current): recorded in the manifest as a deletion; the archive
 *   cannot represent "this path used to exist and no longer does" structurally (a zip has no
 *   tombstone entry), so a text manifest carries it instead.
 * - **Unchanged**: not written at all -- this is the actual "only the changes" payload reduction a
 *   patch bundle exists for.
 *
 * The manifest (`<name>-<newVersion>.dist-info/PATCH-MANIFEST`) is this class's own format, written
 * by hand like `ResourceBundler`'s JSON manifest, recording `Base-Version`/`New-Version` plus one row
 * per change (`A`/`M path,sha256=...,size` or `D path`), sorted by path within each of those three
 * groups for determinism. There is no PEP-defined "wheel patch" format to conform to -- SPEC itself
 * says this whole area is a design still to be worked out -- so this is a deliberately simple,
 * documented, testable choice rather than a standard.
 *
 * [BundleResult.fileCount] counts added+modified files only (the payload actually shipped in the
 * archive), not removed paths (those are absence, not content) and not the manifest itself.
 *
 * Determinism, `instant`-only build level, and target/minSdk validation mirror `SingleWheelBundler`
 * for the same reasons documented there.
 */
class WheelPatchBundler : BundlerInterface {
    private companion object {
        const val SUPPORTED_BUILD_LEVEL = "instant"
        const val SITE_PACKAGES_DIR_NAME = "site-packages"
        const val PYTHON_TAG = "cp313"
        const val ABI_TAG = "cp313"
        const val PATCH_MANIFEST_FILE_NAME = "PATCH-MANIFEST"
        const val PATCH_FORMAT_VERSION = "1.0"
        const val ANDROID_MIN_SDK_FLOOR = 21
        const val DETERMINISTIC_ZIP_TIME_MS = 315532800000L
        const val DIST_INFO_MARKER = ".dist-info/"

        val EXCLUDED_DIRECTORY_NAMES = setOf("__pycache__")
        val EXCLUDED_EXTENSIONS = setOf("pyc", "pyo")
    }

    private data class BaselineEntry(val sha256: String, val size: Long)

    override fun bundle(request: BundleRequest): Result<BundleResult> =
        runCatching {
            val packageDir = request.packageDir.canonicalFile
            require(packageDir.isDirectory) { "Package directory not found: ${packageDir.absolutePath}" }

            val pyproject = File(packageDir, "pyproject.toml")
            require(pyproject.isFile) { "pyproject.toml not found in ${packageDir.absolutePath}" }

            require(request.buildLevel == SUPPORTED_BUILD_LEVEL) {
                "Build level '${request.buildLevel}' is not implemented for bundle type " +
                    "'${BundleType.PATCH.id}'. Only '$SUPPORTED_BUILD_LEVEL' is available today."
            }
            require(request.buildType.isNotBlank()) { "Build type cannot be blank" }

            val canonicalTarget =
                Platforms.normalizeTarget(request.target)
                    ?: throw IllegalArgumentException(Platforms.unsupportedTargetMessage(request.target))
            val descriptor = Platforms.describeTarget(canonicalTarget)
            Platforms.requireValidMinSdk(request.minSdk, descriptor.family)
            require(request.minSdk == null || request.minSdk >= ANDROID_MIN_SDK_FLOOR) {
                "Android wheel tag (PEP 738) requires minSdk >= $ANDROID_MIN_SDK_FLOOR (its own floor), " +
                    "was ${request.minSdk}."
            }
            val editor = TomlEditor(pyproject.readText())
            val packageName = (editor.getValue("project", "name") as? TomlValue.String)?.value ?: packageDir.name
            val newVersion = (editor.getValue("project", "version") as? TomlValue.String)?.value ?: "0.0.0"
            val escapedName = packageName.wheelEscaped()

            val baselineWheel = locateBaselineSingleWheel(packageDir, request, packageName)
            val (baselineName, baseVersion) = parseWheelNameAndVersion(baselineWheel)
            require(baselineName == escapedName) {
                "Baseline wheel '${baselineWheel.name}' under ${baselineWheel.parentFile.absolutePath} was built " +
                    "for package '$baselineName', but the current package is '$escapedName'. Refusing to diff " +
                    "against a baseline for a different package."
            }

            val sitePackages = locateSitePackages(packageDir, packageName, canonicalTarget, request.buildType, request.buildLevel)
            val currentPayload = collectPayload(sitePackages)
            require(currentPayload.isNotEmpty()) {
                "No installed files found under ${sitePackages.absolutePath}. Run `pypackpack build $packageName` first."
            }

            val baselineEntries = readBaselineEntries(baselineWheel)

            val added = currentPayload.keys - baselineEntries.keys
            val removed = baselineEntries.keys - currentPayload.keys
            val modified =
                (currentPayload.keys intersect baselineEntries.keys).filter { path ->
                    sha256UrlSafe(currentPayload.getValue(path).readBytes()) != baselineEntries.getValue(path).sha256
                }
            val changedPaths = (added + modified).sorted()

            val platformTag = platformTag(canonicalTarget, descriptor.family, request.minSdk)
            val distInfoDir = "$escapedName-$newVersion.dist-info"
            val artifactFileName =
                "$escapedName-${baseVersion}_to_$newVersion-$PYTHON_TAG-$ABI_TAG-$platformTag.whl.patch"

            val outputDir = resolveOutputDir(request, packageDir)
            prepareOutputDir(outputDir, request.overwrite)
            val artifactFile = File(outputDir, artifactFileName)

            writePatchArchive(
                artifactFile = artifactFile,
                added = added.sorted(),
                modified = modified.sorted(),
                currentPayload = currentPayload,
                removed = removed.sorted(),
                distInfoDir = distInfoDir,
                packageName = packageName,
                baseVersion = baseVersion,
                newVersion = newVersion,
            )

            BundleResult(
                bundleType = BundleType.PATCH,
                outputDir = outputDir,
                manifestFile = File(outputDir, "$distInfoDir/$PATCH_MANIFEST_FILE_NAME"),
                fileCount = changedPaths.size,
                artifactFile = artifactFile,
            )
        }

    // --- Baseline lookup ----------------------------------------------------------------------------

    private fun locateBaselineSingleWheel(
        packageDir: File,
        request: BundleRequest,
        packageName: String,
    ): File {
        val singleOutputDir =
            File(packageDir, "build/packpack/${BundleType.SINGLE.id}/${request.buildType}/${request.buildLevel}")
        require(singleOutputDir.isDirectory) {
            "No existing 'single' bundle found at ${singleOutputDir.absolutePath}. A 'patch' bundle needs a " +
                "primary 'single' bundle to diff against -- run `pypackpack build $packageName` then bundle " +
                "type 'single' first."
        }
        val wheels = singleOutputDir.listFiles { file -> file.isFile && file.extension == "whl" }.orEmpty().toList()
        require(wheels.isNotEmpty()) {
            "No existing 'single' bundle (.whl) found at ${singleOutputDir.absolutePath}. A 'patch' bundle needs " +
                "a primary 'single' bundle to diff against -- run `pypackpack build $packageName` then bundle " +
                "type 'single' first."
        }
        require(wheels.size == 1) {
            "Multiple wheels found at ${singleOutputDir.absolutePath}: ${wheels.joinToString(", ") { it.name }}. " +
                "Refusing to guess which one is the primary bundle to diff against."
        }
        return wheels.single()
    }

    /** Splits a wheel filename `<escapedName>-<version>-<pyTag>-<abiTag>-<platformTag>.whl` into name/version. */
    private fun parseWheelNameAndVersion(wheel: File): Pair<String, String> {
        val stem = wheel.name.removeSuffix(".whl")
        val parts = stem.split('-')
        require(parts.size >= 2) { "Cannot parse wheel filename: ${wheel.name}" }
        return parts[0] to parts[1]
    }

    private fun readBaselineEntries(wheel: File): Map<String, BaselineEntry> =
        ZipFile(wheel).use { zip ->
            zip
                .entries()
                .asSequence()
                .filter { !it.isDirectory && DIST_INFO_MARKER !in it.name }
                .associate { entry ->
                    val bytes = zip.getInputStream(entry).readBytes()
                    entry.name to BaselineEntry(sha256UrlSafe(bytes), bytes.size.toLong())
                }
        }

    // --- Payload location & collection (mirrors SingleWheelBundler; see class KDoc) ---------------

    private fun locateSitePackages(
        packageDir: File,
        packageName: String,
        canonicalTarget: String,
        buildType: String,
        buildLevel: String,
    ): File {
        val destdir = File(packageDir, "dist/${canonicalTarget}/${buildType}/${buildLevel}")
        require(destdir.isDirectory) {
            "No compiled output found under ${destdir.absolutePath}. Run `pypackpack build $packageName --type $buildType --level $buildLevel --target $canonicalTarget` first."
        }
        val matches =
            destdir
                .walkTopDown()
                .filter { it.isDirectory && it.name == SITE_PACKAGES_DIR_NAME }
                .toList()

        require(matches.isNotEmpty()) {
            "No '$SITE_PACKAGES_DIR_NAME' directory found under ${destdir.absolutePath}. " +
                "Expected a Meson destdir install; run `pypackpack build $packageName` first."
        }
        require(matches.size == 1) {
            "Multiple '$SITE_PACKAGES_DIR_NAME' directories found under ${destdir.absolutePath}: " +
                matches.joinToString(", ") { it.absolutePath } + ". Refusing to guess which one to bundle."
        }
        return matches.single()
    }

    private fun collectPayload(sitePackages: File): Map<String, File> =
        sitePackages
            .walkTopDown()
            .onEnter { directory -> !directory.isExcludedDirectory() }
            .filter { it.isFile && !it.isExcludedFile() }
            .associateBy { file ->
                sitePackages
                    .toPath()
                    .relativize(file.toPath())
                    .toString()
                    .replace(File.separatorChar, '/')
            }.toSortedMap()

    private fun File.isExcludedDirectory(): Boolean = name in EXCLUDED_DIRECTORY_NAMES || name.startsWith(".")

    private fun File.isExcludedFile(): Boolean = extension.lowercase() in EXCLUDED_EXTENSIONS || name.startsWith(".")

    private fun String.wheelEscaped(): String = replace(Regex("[-_.]+"), "_").lowercase()

    private fun resolveOutputDir(
        request: BundleRequest,
        packageDir: File,
    ): File {
        val conventional =
            File(
                packageDir,
                "build/packpack/${BundleType.PATCH.id}/${request.buildType}/${request.buildLevel}",
            )
        val target = request.outputDir ?: conventional
        target.mkdirs()
        return target.canonicalFile
    }

    private fun prepareOutputDir(
        outputDir: File,
        overwrite: Boolean,
    ) {
        val existingPatch = outputDir.listFiles()?.any { it.name.endsWith(".whl.patch") } ?: false
        if (existingPatch) {
            require(overwrite) {
                "Bundle output already exists: ${outputDir.absolutePath}. Use --overwrite to replace it."
            }
            outputDir.listFiles()?.forEach { it.deleteRecursively() }
        }
        check(outputDir.mkdirs() || outputDir.isDirectory) {
            "Failed to create bundle output: ${outputDir.absolutePath}"
        }
    }

    // --- Platform tag computation (identical to SingleWheelBundler) -------------------------------

    private fun rawArch(canonicalTarget: String): String = canonicalTarget.substringBefore('-')

    private fun platformTag(
        canonicalTarget: String,
        family: String,
        minSdk: Int?,
    ): String =
        when (family) {
            "windows" -> windowsPlatformTag(canonicalTarget)
            "macos" -> macosPlatformTag(canonicalTarget)
            "ios" -> iosPlatformTag(canonicalTarget)
            "android" -> androidPlatformTag(canonicalTarget, minSdk)
            "wasm" -> wasmPlatformTag(canonicalTarget)
            "linux" -> linuxPlatformTag(canonicalTarget)
            else -> throw IllegalArgumentException("No wheel platform tag scheme for target family '$family' ($canonicalTarget)")
        }

    private fun windowsPlatformTag(canonicalTarget: String): String =
        when (rawArch(canonicalTarget)) {
            "x86_64" -> "win_amd64"
            "aarch64" -> "win_arm64"
            "i686" -> "win32"
            else -> throw IllegalArgumentException("Unsupported Windows arch in target: $canonicalTarget")
        }

    private fun macosPlatformTag(canonicalTarget: String): String =
        when (val arch = rawArch(canonicalTarget)) {
            "aarch64" -> "macosx_11_0_arm64"
            "x86_64" -> "macosx_10_9_x86_64"
            else -> throw IllegalArgumentException("Unsupported macOS arch in target: $canonicalTarget ($arch)")
        }

    private fun iosPlatformTag(canonicalTarget: String): String {
        val arch = rawArch(canonicalTarget)
        val sdk = if (canonicalTarget.contains("simulator")) "iphonesimulator" else "iphoneos"
        return "ios_13_0_${arch}_$sdk"
    }

    private fun androidPlatformTag(
        canonicalTarget: String,
        minSdk: Int?,
    ): String {
        val abi =
            when (val arch = rawArch(canonicalTarget)) {
                "aarch64" -> "arm64_v8a"
                "x86_64" -> "x86_64"
                else -> throw IllegalArgumentException("Unsupported Android arch in target: $canonicalTarget ($arch)")
            }
        val apiLevel = minSdk ?: ANDROID_MIN_SDK_FLOOR
        return "android_${apiLevel}_$abi"
    }

    private fun wasmPlatformTag(canonicalTarget: String): String {
        val suffix = canonicalTarget.substringAfter('-')
        val year =
            Regex("pyodide(\\d+)").find(suffix)?.groupValues?.get(1)
                ?: throw IllegalArgumentException("Unsupported wasm target (expected 'pyodide<year>'): $canonicalTarget")
        return "pyodide_${year}_0_${rawArch(canonicalTarget)}"
    }

    private fun linuxPlatformTag(canonicalTarget: String): String {
        val arch = rawArch(canonicalTarget)
        val suffix = canonicalTarget.substringAfter('-')
        return when {
            suffix == "manylinux2014" -> "manylinux_2_17_$arch"
            suffix.startsWith("manylinux_") -> "manylinux_${suffix.removePrefix("manylinux_")}_$arch"
            suffix.contains("musl") -> "musllinux_1_2_$arch"
            else -> "linux_$arch"
        }
    }

    // --- Patch archive writing ----------------------------------------------------------------------

    private fun writePatchArchive(
        artifactFile: File,
        added: List<String>,
        modified: List<String>,
        currentPayload: Map<String, File>,
        removed: List<String>,
        distInfoDir: String,
        packageName: String,
        baseVersion: String,
        newVersion: String,
    ) {
        ZipOutputStream(artifactFile.outputStream().buffered()).use { zip ->
            (added + modified).sorted().forEach { path ->
                writeDeterministicEntry(zip, path, currentPayload.getValue(path).readBytes())
            }

            val manifest = renderManifest(packageName, baseVersion, newVersion, added, modified, currentPayload, removed)
            val manifestBytes = manifest.toByteArray(Charsets.UTF_8)
            writeDeterministicEntry(zip, "$distInfoDir/$PATCH_MANIFEST_FILE_NAME", manifestBytes)
            
            val manifestFile = File(artifactFile.parentFile, "$distInfoDir/$PATCH_MANIFEST_FILE_NAME")
            manifestFile.parentFile.mkdirs()
            manifestFile.writeBytes(manifestBytes)
        }
    }

    private fun renderManifest(
        packageName: String,
        baseVersion: String,
        newVersion: String,
        added: List<String>,
        modified: List<String>,
        currentPayload: Map<String, File>,
        removed: List<String>,
    ): String =
        buildString {
            appendLine("Patch-Format-Version: $PATCH_FORMAT_VERSION")
            appendLine("Name: $packageName")
            appendLine("Base-Version: $baseVersion")
            appendLine("New-Version: $newVersion")
            added.forEach { path ->
                val bytes = currentPayload.getValue(path).readBytes()
                appendLine("A $path,${sha256UrlSafe(bytes)},${bytes.size}")
            }
            modified.forEach { path ->
                val bytes = currentPayload.getValue(path).readBytes()
                appendLine("M $path,${sha256UrlSafe(bytes)},${bytes.size}")
            }
            removed.forEach { path -> appendLine("D $path") }
        }

    private fun writeDeterministicEntry(
        zip: ZipOutputStream,
        name: String,
        bytes: ByteArray,
    ) {
        val entry = ZipEntry(name)
        entry.time = DETERMINISTIC_ZIP_TIME_MS
        entry.method = ZipEntry.DEFLATED
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun sha256UrlSafe(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return "sha256=" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }
}
