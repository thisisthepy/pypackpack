package org.thisisthepy.python.multiplatform.packpack.bundle.fat

import org.thisisthepy.python.multiplatform.packpack.bundle.BundleRequest
import org.thisisthepy.python.multiplatform.packpack.bundle.BundleResult
import org.thisisthepy.python.multiplatform.packpack.bundle.BundleType
import org.thisisthepy.python.multiplatform.packpack.bundle.BundlerInterface
import org.thisisthepy.python.multiplatform.packpack.utils.Platforms
import org.thisisthepy.python.multiplatform.packpack.utils.readWorkspaceMembers
import org.thisisthepy.python.multiplatform.packpack.utils.toml.TomlEditor
import org.thisisthepy.python.multiplatform.packpack.utils.toml.TomlValue
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Bundle type `fat`: a `.whl` archive that includes the current package together with its
 * dependencies.
 *
 * ## What the SPEC actually says
 *
 * `docs/SPEC.md` states exactly one sentence about this bundle type -- *"`fat`: Produces a
 * wheel-like archive that includes the current package together with its dependencies"* -- plus the
 * same output path convention (`<package>/build/packpack/<bundle type>/<build type>/<build level>`)
 * every other bundle type uses.
 *
 * ## Where "dependencies" comes from -- the part the SPEC does not say
 *
 * This codebase has no mechanism to vendor a *third-party* (PyPI) dependency into a directory that a
 * bundler could then copy from. Concretely, none of these exist:
 *
 * - A per-target virtual environment for CrossEnv dependencies. `docs/SPEC.md` -> "Per-package
 *   target dependency management" -> "Not yet implemented (target)" names this explicitly:
 *   "Creating and maintaining a dedicated venv per target for CrossEnv dependencies". `CrossEnv.kt`
 *   only ever calls `uv add`/`uv remove`/`uv sync`/`uv tree` against the *shared* `pyproject.toml` --
 *   `syncDependencies` runs `uv sync --package <name>` (no `--target <dir>`) and then only verifies
 *   resolvability via `uv tree`; it never installs anything to a directory bundle code could read.
 * - Any call to `uv pip install --target` or an equivalent "unpack this wheel into a directory" step
 *   anywhere in `dependency/backend` (grepped; the only `site-packages`/`crossenv`/`.venv` hits in
 *   `packpack/src/main` are `SingleWheelBundler`'s own Meson-destdir lookup, `ResourceBundler`'s
 *   `<project>/.venv` bytecode-compiler lookup, and `DevEnv`'s `.venv` for the *dev* environment --
 *   none of which is a per-target dependency install).
 *
 * Inventing that subsystem here (e.g. quietly shelling out to `uv pip install --target`) would be
 * new, unspecified, untested infrastructure smuggled into "implement one bundler" -- exactly what
 * this task was told not to do. So `FatWheelBundler` scopes "dependencies" to what this codebase can
 * already, truthfully answer: **workspace-local** Python dependencies. A dependency listed in the
 * package's `pyproject.toml` `project.dependencies` is vendored when (and only when) its declared
 * name matches another package in the same workspace (`tool.uv.workspace.members`, read via
 * [readWorkspaceMembers], matched against each member's own declared `project.name` --
 * `CrossEnv.resolvePackageSpec` already does very similar member matching for `pypackpack <package>`
 * commands, this is the read-only analogue for `bundle`). That matched sibling package must already
 * have been built (`pypackpack build <sibling>`), exactly like this package itself.
 *
 * A dependency name that does **not** match a workspace member is a PyPI dependency this class
 * cannot vendor -- `bundle` fails, naming every such dependency and pointing at the missing
 * subsystem above, rather than silently shipping a "fat" wheel that is missing part of what it
 * claims to include.
 *
 * ## Everything else
 *
 * Once the payload (own package files plus each vendored sibling's files, merged by relative path)
 * is assembled, this class is deliberately identical to [SingleWheelBundler][org.thisisthepy.python.multiplatform.packpack.bundle.single.SingleWheelBundler]:
 * same destdir-lookup algorithm (per package), same `cp313`/`cp313` tag pinning, same per-family
 * platform tag scheme (PEP 425/600/656/730/738), same METADATA/WHEEL/RECORD shape, same determinism
 * (sorted entries, fixed 1980-01-01 zip timestamp), same `instant`-only build level restriction (for
 * the same reason: the compile stage does not yet select build levels). That logic is duplicated
 * here rather than shared, following this codebase's existing precedent of every bundler owning its
 * own archive-writing code (`ResourceBundler` and `SingleWheelBundler` already duplicate rather than
 * share their own SHA-256/exclusion/determinism logic) -- sharing it would mean carving out a new
 * internal API surface as a side effect of this task, which is out of scope here.
 *
 * A vendored sibling package contributes its `dist/site-packages` contents directly (same exclusions
 * as the primary package: `__pycache__/`, `*.pyc`/`*.pyo`, dot-entries) with **no dist-info of its
 * own** -- it is flattened into this wheel's `site-packages`, not nested as an independently
 * installed distribution, which is what "fat"/vendored bundling means as opposed to a dependency
 * resolver's normal multi-distribution install. A file path collision between the primary package
 * and a vendored dependency (or between two vendored dependencies) fails loudly rather than letting
 * one silently overwrite the other.
 */
class FatWheelBundler : BundlerInterface {
    private companion object {
        const val SUPPORTED_BUILD_LEVEL = "instant"
        const val SITE_PACKAGES_DIR_NAME = "site-packages"
        const val PYTHON_TAG = "cp313"
        const val ABI_TAG = "cp313"
        const val METADATA_VERSION = "2.1"
        const val WHEEL_VERSION = "1.0"
        const val GENERATOR = "pypackpack 0.1.0"
        const val ANDROID_MIN_SDK_FLOOR = 21
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
                    "'${BundleType.FAT.id}'. Only '$SUPPORTED_BUILD_LEVEL' is available today."
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

            val ownPayload = collectPayload(locateSitePackages(packageDir, packageName))
            val dependencyPayload = collectDependencyPayload(packageDir, editor)

            val payload = mergePayloads(ownPayload, dependencyPayload)
            require(payload.isNotEmpty()) {
                "No installed files found for package '$packageName'. Run `pypackpack build $packageName` first."
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

            BundleResult(
                bundleType = BundleType.FAT,
                outputDir = outputDir,
                manifestFile = File(outputDir, "$distInfoDir/RECORD"),
                fileCount = payload.size,
                artifactFile = artifactFile,
            )
        }

    // --- Dependency resolution (workspace-local only; see class KDoc) -----------------------------

    /**
     * Reads `project.dependencies`, matches each declared name against a workspace member's own
     * `project.name`, and collects that member's already-built payload. Fails naming every
     * dependency that is not a workspace member (cannot be vendored -- see class KDoc) and every
     * matched member that has not been built yet.
     */
    private fun collectDependencyPayload(
        packageDir: File,
        editor: TomlEditor,
    ): Map<String, File> {
        val dependencyNames = editor.getArray("project", "dependencies").map { extractDependencyName(it) }
        if (dependencyNames.isEmpty()) return emptyMap()

        val workspaceRoot = findWorkspaceRootOrNull(packageDir)
        val members =
            workspaceRoot?.let { root ->
                readWorkspaceMembers(root).mapNotNull { relative ->
                    val memberDir = File(root, relative).canonicalFile
                    val memberPyproject = File(memberDir, "pyproject.toml")
                    if (!memberPyproject.isFile) return@mapNotNull null
                    val memberName =
                        (TomlEditor(memberPyproject.readText()).getValue("project", "name") as? TomlValue.String)
                            ?.value ?: memberDir.name
                    memberName to memberDir
                }
            }.orEmpty()

        val unresolved = mutableListOf<String>()
        val payload = sortedMapOf<String, File>()

        for (depName in dependencyNames) {
            val match = members.firstOrNull { (memberName, _) -> memberName.normalizedForMatch() == depName.normalizedForMatch() }
            if (match == null) {
                unresolved += depName
                continue
            }
            val (memberName, memberDir) = match
            val memberPayload = collectPayload(locateSitePackages(memberDir, memberName))
            require(memberPayload.isNotEmpty()) {
                "Dependency '$memberName' (workspace member at ${memberDir.absolutePath}) has no compiled " +
                    "output. Run `pypackpack build $memberName` first."
            }
            for ((path, file) in memberPayload) {
                require(!payload.containsKey(path)) {
                    "File path collision while vendoring dependency '$memberName': '$path' is already provided " +
                        "by another source in this fat bundle."
                }
                payload[path] = file
            }
        }

        require(unresolved.isEmpty()) {
            "Cannot bundle type 'fat': ${unresolved.joinToString(", ")} " +
                (if (unresolved.size == 1) "is" else "are") +
                " not workspace-local package(s), and this codebase has no mechanism to vendor a " +
                "third-party dependency yet (no per-target dependency install/venv exists -- see " +
                "`docs/SPEC.md`'s 'Per-package target dependency management' -> 'Not yet implemented " +
                "(target)'). Only dependencies that are also workspace members can be vendored into a " +
                "'fat' bundle today."
        }

        return payload
    }

    private fun findWorkspaceRootOrNull(startDir: File): File? {
        var current: File? = startDir.canonicalFile
        while (current != null) {
            if (readWorkspaceMembers(current).isNotEmpty()) return current
            current = current.parentFile
        }
        return null
    }

    private fun mergePayloads(
        own: Map<String, File>,
        dependencies: Map<String, File>,
    ): Map<String, File> {
        val merged = sortedMapOf<String, File>()
        merged.putAll(own)
        for ((path, file) in dependencies) {
            require(!merged.containsKey(path)) {
                "File path collision while vendoring dependencies: '$path' is provided by both the package " +
                    "itself and a vendored dependency."
            }
            merged[path] = file
        }
        return merged
    }

    private fun extractDependencyName(spec: String): String {
        val requirement = spec.substringBefore(';').trim()
        val match =
            Regex("^[A-Za-z0-9_.-]+")
                .find(requirement)
                ?: throw IllegalArgumentException("Invalid dependency spec: $spec")
        return match.value
    }

    /** pip/PEP 503 normalization: case-insensitive, `-`/`_`/`.` runs are equivalent. */
    private fun String.normalizedForMatch(): String = lowercase().replace(Regex("[-_.]+"), "-")

    // --- Payload location & collection (mirrors SingleWheelBundler; see class KDoc) ---------------

    private fun locateSitePackages(
        packageDir: File,
        packageName: String,
    ): File {
        val destdir = File(packageDir, "dist")
        require(destdir.isDirectory) {
            "No compiled output found under ${destdir.absolutePath}. Run `pypackpack build $packageName` first."
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
                "build/packpack/${BundleType.FAT.id}/${request.buildType}/${request.buildLevel}",
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

    // --- Wheel archive writing (identical to SingleWheelBundler) ----------------------------------

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
            writeDeterministicEntry(zip, "$distInfoDir/RECORD", record.toByteArray(Charsets.UTF_8))
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
