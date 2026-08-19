package org.thisisthepy.python.multiplatform.packpack.bundle.single

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
import java.util.zip.ZipOutputStream

/**
 * Bundle type `single`: a `.whl` archive that includes only the current package (no dependencies).
 *
 * ## What the SPEC actually says
 *
 * `docs/SPEC.md` states exactly one sentence about this bundle type -- *"`single`: Produces a
 * wheel-like archive that includes only the current package"* -- plus, elsewhere in the same document,
 * two things treated as binding here:
 *
 * - the output path convention `<package>/build/packpack/<bundle type>/<build type>/<build level>`;
 * - the compile-stage fact that `pypackpack build` installs via Meson with
 *   `meson install --destdir=<package>/dist` (`compile/backend/DefaultBackend.kt`,
 *   `compile/backend/external/Meson.kt` -- read, not modified, for this class), and that this destdir
 *   path is currently hardcoded regardless of the requested build type/level/target (a documented
 *   compile-stage limitation this class inherits rather than works around).
 *
 * ## Everything below is this implementation's choice, following the precedent `ResourceBundler` set
 * for the one-sentence `resource` type: written down here and pinned by `SingleWheelBundlerTest`, so
 * that changing it is a deliberate edit rather than a silent drift.
 *
 * ### 1. Locating the installed package inside Meson's destdir
 *
 * `meson install --destdir=X` does **not** write package files directly under `X`. It writes them
 * under `X/<absolute install prefix, leading slash stripped>/<python's sysconfig platlib/purelib path>`
 * -- e.g. `X/usr/local/lib/python3.13/site-packages/...` on a default POSIX host, verified by actually
 * running `meson setup && meson compile && meson install --destdir=...` against a throwaway
 * C-extension project on this machine. The prefix segment (`usr/local` here) depends on the host/
 * toolchain and is not something this bundler controls or should hardcode; the leaf directory name,
 * however, is stable -- CPython's own `sysconfig` schemes (`posix_prefix`, `nt`, and every scheme
 * derived from them, including the custom scheme a `python-multiplatform`-built cross interpreter would
 * plausibly use) name it `site-packages` on every platform this project targets. So the algorithm is:
 * walk `<package>/dist` looking for a directory named exactly `site-packages`, and use its contents as
 * the wheel payload root. Zero matches means "nothing was ever installed here" (points the caller at
 * `pypackpack build`); more than one match refuses to guess rather than silently picking one.
 *
 * ### 2. Wheel filename / tag scheme (PEP 427 filename, PEP 425/600/656/730/738 platform tags)
 *
 * The python tag and ABI tag are hardcoded to `cp313`/`cp313`: this codebase's Python distribution is
 * pinned to 3.13 everywhere else (`docs/SPEC.md` -> "Additional project Python-related features" ->
 * *"Only Python 3.13 is supported due to python-multiplatform limitations"*), and Meson's
 * `py.extension_module()` calls here are never given `limited_api`, so the produced `.so`/`.pyd` links
 * against the full (non-limited) C API -- `abi3` would be a false promise.
 *
 * The platform tag is derived from [Platforms.describeTarget] per family, deliberately **not** reusing
 * [Platforms.TargetDescriptor.markerMachine]: that field folds `aarch64` -> `arm64` unconditionally for
 * PEP 508 dependency markers, which happens to match macOS/iOS's own `platform.machine()` spelling but
 * is *wrong* for Linux/Android wheel tags, where the ecosystem convention keeps `aarch64` (manylinux/
 * musllinux) or uses the Android NDK ABI spelling `arm64_v8a` (PEP 738) -- neither of which is `arm64`.
 * This class computes its own per-family arch spelling instead of risking that cross-contamination.
 *
 * Standards used, by family:
 *
 * - **Windows** (PEP 425): `win_amd64` / `win_arm64` / `win32` (the last does not carry an arch
 *   suffix -- that is the standard's own spelling for 32-bit x86, not an omission here).
 * - **macOS** (PEP 425): `macosx_<major>_<minor>_<arch>`. Deployment-target floor is assumed --
 *   `SUPPORTED_TARGETS` carries no macOS version -- and set to `11.0` for `arm64` / `10.9` for
 *   `x86_64`, matching both the floors `cibuildwheel` defaults to and what python.org's own installers
 *   have used; not a number invented from nothing.
 * - **manylinux** (PEP 600): `SUPPORTED_TARGETS` already spells the glibc version explicitly
 *   (`x86_64-manylinux_2_28` etc.) except for the one legacy alias `manylinux2014`, which PEP 600 itself
 *   defines as equivalent to `manylinux_2_17` -- that single mapping is hardcoded, nothing else is
 *   inferred.
 * - **generic Linux** (glibc, not manylinux-qualified) (PEP 425): `linux_<arch>` -- the same tag
 *   `pip wheel`/`build` produce locally absent `auditwheel` repair. PyPI itself refuses to *index* bare
 *   `linux_*` wheels, but this project does not publish through PyPI's index for these, so that
 *   restriction does not apply.
 * - **musl Linux, not manylinux-qualified** (PEP 656): `musllinux_<major>_<minor>_<arch>`. `1.2` is
 *   assumed as the floor -- `SUPPORTED_TARGETS`' `*-unknown-linux-musl` entries carry no musl version,
 *   and musl's own stable-ABI guarantee (cited in PEP 656) makes an old floor safe to assume rather than
 *   measure per build.
 * - **Android** (PEP 738, accepted): `android_<api-level>_<abi>`, e.g. `android_21_arm64_v8a`. The
 *   api-level segment is [BundleRequest.minSdk] when the caller declares one (validated against
 *   [ANDROID_MIN_SDK_FLOOR] by `bundle`), and falls back to `21` -- PEP 738's own minimum supported
 *   Android version -- otherwise. `Platforms.kt`'s `TARGET_ALIASES` still collapses `android_21_*` and
 *   `android_24_*` onto the same canonical target, so the target string alone still carries no api
 *   level; `minSdk` is the field that recovers the distinction the target string cannot.
 * - **iOS** (PEP 730, accepted): `ios_<major>_<minor>_<arch>_<iphoneos|iphonesimulator>`, e.g.
 *   `ios_13_0_arm64_iphoneos`. `13.0` is assumed as the floor -- it is literally PEP 730's own worked
 *   example tag, and `SUPPORTED_TARGETS` carries no iOS version of its own.
 * - **wasm** (`wasm32-pyodide2024`, matching Pyodide's own pre-PEP-783 tag spelling exactly):
 *   `pyodide_<year>_<patch>_wasm32`. `SUPPORTED_TARGETS`' target string encodes the year but not a
 *   patch number, so patch `0` is assumed.
 *
 * ### 3. What goes in the archive
 *
 * Everything under the located `site-packages` directory, *including* compiled extensions
 * (`.so`/`.pyd`/`.dylib`) -- unlike `ResourceBundler`, which deliberately excludes them because it
 * ships source for an `instant`-level resource layout. Excluded: `__pycache__/`, `*.pyc`, `*.pyo`,
 * `.DS_Store`, and any dot-entry -- build/editor artifacts, not payload.
 *
 * `Root-Is-Purelib` follows `setuptools`' own heuristic: `false` when the payload contains any
 * `.so`/`.pyd`/`.dylib`, `true` otherwise.
 *
 * `RECORD`/`WHEEL`/`METADATA` follow the Python Packaging Authority's "Binary distribution format" and
 * "Recording installed packages" specifications: `RECORD` rows are
 * `path,sha256=<urlsafe-base64-nopad-digest>,<size>`, the `RECORD` file's own row has empty hash/size
 * fields, and `METADATA` states `Metadata-Version: 2.1` with the minimum `Name`/`Version` fields (no
 * `Summary`, license, or dependency metadata -- `SPEC.md` documents dependencies for `single` as
 * excluded by definition, and nothing today feeds richer project metadata into `bundle`).
 *
 * ### 4. Determinism
 *
 * Entries are written in sorted path order with a fixed timestamp (`1980-01-01T00:00:00Z`, the DOS/ZIP
 * epoch floor -- the same "no real timestamp" convention reproducible-build tooling uses when
 * `SOURCE_DATE_EPOCH` is not otherwise pinned), mirroring `ResourceBundler`'s determinism goal so two
 * runs over identical input produce byte-identical archives.
 *
 * Only build level `instant` is implemented, for the same reason `ResourceBundler` restricts it: the
 * compile stage does not yet select between build levels (`docs/SPEC.md` -> "Build Level"), so `native`/
 * `bytecode`/`mixed` would either silently fall back to whatever Meson happened to produce or fail --
 * this class fails loudly instead.
 */
class SingleWheelBundler : BundlerInterface {
    private companion object {
        const val SUPPORTED_BUILD_LEVEL = "instant"
        const val SITE_PACKAGES_DIR_NAME = "site-packages"
        const val PYTHON_TAG = "cp313"
        const val ABI_TAG = "cp313"
        const val METADATA_VERSION = "2.1"
        const val WHEEL_VERSION = "1.0"
        const val GENERATOR = "pypackpack 0.1.0"

        // PEP 738's own minimum supported Android version; see class KDoc §2. `androidPlatformTag`
        // falls back to this when `BundleRequest.minSdk` is undeclared, and rejects any declared
        // value below it (checked in `bundle`, not here, so the failure names the actual request).
        const val ANDROID_MIN_SDK_FLOOR = 21

        // 1980-01-01T00:00:00Z -- the DOS/ZIP timestamp epoch floor; see class KDoc "Determinism".
        const val DETERMINISTIC_ZIP_TIME_MS = 315532800000L

        val EXCLUDED_DIRECTORY_NAMES = setOf("__pycache__")
        val EXCLUDED_EXTENSIONS = setOf("pyc", "pyo")
        val NATIVE_EXTENSION_EXTENSIONS = setOf("so", "pyd", "dylib")
    }

    override fun bundle(request: BundleRequest): Result<BundleResult> =
        runCatching {
            val packageDir = request.packageDir.canonicalFile
            require(packageDir.isDirectory) { "Package directory not found: ${packageDir.absolutePath}" }

            val pyproject = File(packageDir, "pyproject.toml")
            require(pyproject.isFile) { "pyproject.toml not found in ${packageDir.absolutePath}" }

            require(request.buildLevel == SUPPORTED_BUILD_LEVEL) {
                "Build level '${request.buildLevel}' is not implemented for bundle type " +
                    "'${BundleType.SINGLE.id}'. Only '$SUPPORTED_BUILD_LEVEL' is available today."
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
            val version = (editor.getValue("project", "version") as? TomlValue.String)?.value ?: "0.0.0"

            val destdir = File(packageDir, "dist/${canonicalTarget}/${request.buildType}/${request.buildLevel}")
            require(destdir.isDirectory) {
                "No compiled output found under ${destdir.absolutePath}. Run `pypackpack build $packageName --type ${request.buildType} --level ${request.buildLevel} --target ${canonicalTarget}` first."
            }
            val sitePackages = findSitePackages(destdir)

            val payload = collectPayload(sitePackages)
            require(payload.isNotEmpty()) {
                "No installed files found under ${sitePackages.absolutePath}. Run `pypackpack build $packageName` first."
            }

            val escapedName = packageName.wheelEscaped()
            val platformTag = platformTag(canonicalTarget, descriptor.family, request.minSdk)
            val distInfoDir = "$escapedName-$version.dist-info"
            val wheelFileName = "$escapedName-$version-$PYTHON_TAG-$ABI_TAG-$platformTag.whl"
            val rootIsPurelib = payload.keys.none { it.substringAfterLast('.', "") in NATIVE_EXTENSION_EXTENSIONS }

            val outputDir = resolveOutputDir(request, packageDir)
            prepareOutputDir(outputDir, request.overwrite)
            val artifactFile = File(outputDir, wheelFileName)

            writeWheel(
                artifactFile = artifactFile,
                payload = payload,
                distInfoDir = distInfoDir,
                packageName = packageName,
                version = version,
                platformTag = platformTag,
                rootIsPurelib = rootIsPurelib,
            )

            val manifestFile = File(outputDir, "$distInfoDir/RECORD")

            BundleResult(
                bundleType = BundleType.SINGLE,
                outputDir = outputDir,
                manifestFile = manifestFile,
                fileCount = payload.size,
                artifactFile = artifactFile,
            )
        }

    private fun resolveOutputDir(
        request: BundleRequest,
        packageDir: File,
    ): File {
        val conventional =
            File(
                packageDir,
                "build/packpack/${BundleType.SINGLE.id}/${request.buildType}/${request.buildLevel}",
            )
        val target = request.outputDir ?: conventional
        target.mkdirs()
        return target.canonicalFile
    }

    private fun prepareOutputDir(
        outputDir: File,
        overwrite: Boolean,
    ) {
        val existingWheel = outputDir.listFiles()?.any { it.extension == "whl" } ?: false
        if (existingWheel) {
            require(overwrite) {
                "Bundle output already exists: ${outputDir.absolutePath}. Use --overwrite to replace it."
            }
            outputDir.listFiles()?.forEach { it.deleteRecursively() }
        }
        check(outputDir.mkdirs() || outputDir.isDirectory) {
            "Failed to create bundle output: ${outputDir.absolutePath}"
        }
    }

    /**
     * Finds the `site-packages` directory inside a Meson destdir; see class KDoc §1. Fails rather than
     * guessing when there is not exactly one.
     */
    private fun findSitePackages(destdir: File): File {
        val matches =
            destdir
                .walkTopDown()
                .filter { it.isDirectory && it.name == SITE_PACKAGES_DIR_NAME }
                .toList()

        require(matches.isNotEmpty()) {
            "No '$SITE_PACKAGES_DIR_NAME' directory found under ${destdir.absolutePath}. " +
                "Expected a Meson destdir install; run `pypackpack build` first."
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

    // --- Platform tag computation (class KDoc §2) -------------------------------------------------

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
            suffix == "manylinux2014" -> "manylinux_2_17_$arch" // PEP 600: manylinux2014 == glibc 2.17
            suffix.startsWith("manylinux_") -> "manylinux_${suffix.removePrefix("manylinux_")}_$arch"
            suffix.contains("musl") -> "musllinux_1_2_$arch" // floor assumption; see class KDoc §2
            else -> "linux_$arch"
        }
    }

    // --- Wheel archive writing ---------------------------------------------------------------------

    private fun writeWheel(
        artifactFile: File,
        payload: Map<String, File>,
        distInfoDir: String,
        packageName: String,
        version: String,
        platformTag: String,
        rootIsPurelib: Boolean,
    ) {
        val metadata = renderMetadata(packageName, version)
        val wheelFile = renderWheelFile(platformTag, rootIsPurelib)

        val recordRows = mutableListOf<Triple<String, String, Long>>()

        ZipOutputStream(artifactFile.outputStream().buffered()).use { zip ->
            payload.toSortedMap().forEach { (relativePath, source) ->
                val bytes = source.readBytes()
                writeDeterministicEntry(zip, relativePath, bytes)
                recordRows += Triple(relativePath, sha256UrlSafe(bytes), bytes.size.toLong())
            }

            val metadataBytes = metadata.toByteArray(Charsets.UTF_8)
            writeDeterministicEntry(zip, "$distInfoDir/METADATA", metadataBytes)
            recordRows += Triple("$distInfoDir/METADATA", sha256UrlSafe(metadataBytes), metadataBytes.size.toLong())

            val wheelBytes = wheelFile.toByteArray(Charsets.UTF_8)
            writeDeterministicEntry(zip, "$distInfoDir/WHEEL", wheelBytes)
            recordRows += Triple("$distInfoDir/WHEEL", sha256UrlSafe(wheelBytes), wheelBytes.size.toLong())

            val record = renderRecord(recordRows.sortedBy { it.first }, "$distInfoDir/RECORD")
            val recordBytes = record.toByteArray(Charsets.UTF_8)
            writeDeterministicEntry(zip, "$distInfoDir/RECORD", recordBytes)
            
            val manifestFile = File(artifactFile.parentFile, "$distInfoDir/RECORD")
            manifestFile.parentFile.mkdirs()
            manifestFile.writeBytes(recordBytes)
        }
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

    private fun renderMetadata(
        packageName: String,
        version: String,
    ): String =
        buildString {
            appendLine("Metadata-Version: $METADATA_VERSION")
            appendLine("Name: $packageName")
            appendLine("Version: $version")
        }

    private fun renderWheelFile(
        platformTag: String,
        rootIsPurelib: Boolean,
    ): String =
        buildString {
            appendLine("Wheel-Version: $WHEEL_VERSION")
            appendLine("Generator: $GENERATOR")
            appendLine("Root-Is-Purelib: ${if (rootIsPurelib) "true" else "false"}")
            appendLine("Tag: $PYTHON_TAG-$ABI_TAG-$platformTag")
        }

    private fun renderRecord(
        rows: List<Triple<String, String, Long>>,
        recordSelfPath: String,
    ): String =
        buildString {
            rows.forEach { (path, hash, size) -> appendLine("$path,$hash,$size") }
            appendLine("$recordSelfPath,,")
        }
}
