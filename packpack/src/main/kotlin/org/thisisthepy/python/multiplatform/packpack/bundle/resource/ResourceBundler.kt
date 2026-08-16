package org.thisisthepy.python.multiplatform.packpack.bundle.resource

import org.thisisthepy.python.multiplatform.packpack.bundle.BundlerInterface
import org.thisisthepy.python.multiplatform.packpack.bundle.BundleRequest
import org.thisisthepy.python.multiplatform.packpack.bundle.BundleResult
import org.thisisthepy.python.multiplatform.packpack.bundle.BundleType
import org.thisisthepy.python.multiplatform.packpack.utils.Platforms
import org.thisisthepy.python.multiplatform.packpack.utils.toml.TomlEditor
import org.thisisthepy.python.multiplatform.packpack.utils.toml.TomlValue
import java.io.File
import java.security.MessageDigest

/**
 * Bundle type `resource`: the handoff format from ppp to `python-multiplatform` and `toolchain`.
 *
 * ## What the SPEC actually says
 *
 * `docs/SPEC.md` states exactly one sentence about this bundle type -- *"`resource`: Produces a
 * resource layout consumable by python-multiplatform/toolchain"* -- plus two things stated
 * elsewhere in the same document that are treated as binding here:
 *
 * - the output path convention `<package>/build/packpack/<bundle type>/<build type>/<build level>`,
 *   from SPEC's "User directory structure";
 * - the package source layout `<package>/src/{main,android,windows,...}`, from the same section,
 *   where `main` is platform-common and the sibling directories are platform-specific.
 *
 * ## What is assumed, because the SPEC does not say
 *
 * Everything below is this implementation's choice. It is written down here, and pinned by
 * `ResourceBundlerTest`, so that changing it is a deliberate edit rather than a silent drift.
 *
 * 1. **Payload root is `python/`.** The bundle directory holds a `python/` subdirectory intended to
 *    be placed on `sys.path` verbatim (staged into Android assets, an iOS resource directory, or a
 *    desktop resource folder). Keeping the payload one level down leaves room for sibling metadata
 *    without it colliding with a Python top-level package.
 * 2. **Metadata is a single `resource-manifest.json`** beside `python/`. JSON, not TOML, because
 *    the consumers are Gradle/Kotlin, not `uv`.
 * 3. **Platform overlay semantics: `src/main` first, then `src/<family>` on top**, where `<family>`
 *    is `Platforms.getPlatformFamily(target)` -- whose own KDoc already says it is "used for
 *    `package/src/<dir>` layout". A file present in both wins from the platform directory. Other
 *    families are not copied at all.
 * 4. **Source root fallback `src/main` -> `src` -> package root**, mirroring
 *    `Meson.findPythonPackages`: the first of the three that holds a directory with an
 *    `__init__.py` wins, and only those Python package directories are bundled -- which is also
 *    what keeps `pyproject.toml` and `meson.build` out of the payload in the fallback case.
 * 5. **All files are carried, not just `.py`.** "Resource layout" is read literally: data files
 *    next to the modules are part of what an app needs. Excluded are build/editor artifacts only:
 *    `__pycache__/`, `*.pyc`, `*.pyo`, `*.pyd`, `.DS_Store`, and any dot-entry. `build/` and
 *    `dist/` are excluded as well, which only matters for the package-root fallback case.
 * 6. **Only build level `instant` is implemented.** SPEC's `bytecode`, `native` and `mixed` levels
 *    need the compile stage's output, which `build` does not yet hand to `bundle`; asking for them
 *    fails loudly rather than silently producing an `instant` bundle under the wrong path.
 * 7. **Determinism.** The manifest's file list is sorted by path and no timestamp is recorded, so
 *    two runs over identical input produce byte-identical manifests and the manifest can serve as a
 *    cache key for the consumer.
 *
 * The bundle is a *directory*, not an archive. Compressing it is `toolchain`'s business (it already
 * has a `Zip` task) and leaving it uncompressed keeps incremental staging cheap.
 */
class ResourceBundler : BundlerInterface {
    private companion object {
        const val MANIFEST_FILE_NAME = "resource-manifest.json"
        const val PYTHON_ROOT = "python"
        const val FORMAT_VERSION = 1
        const val SUPPORTED_BUILD_LEVEL = "instant"

        val EXCLUDED_FILE_NAMES = setOf("Thumbs.db")
        val EXCLUDED_EXTENSIONS = setOf("pyc", "pyo", "pyd")
        val EXCLUDED_DIRECTORY_NAMES = setOf("__pycache__", "build", "dist", "node_modules")
    }

    override fun bundle(request: BundleRequest): Result<BundleResult> =
        runCatching {
            val packageDir = request.packageDir.canonicalFile
            require(packageDir.isDirectory) { "Package directory not found: ${packageDir.absolutePath}" }

            val pyproject = File(packageDir, "pyproject.toml")
            require(pyproject.isFile) { "pyproject.toml not found in ${packageDir.absolutePath}" }

            require(request.buildLevel == SUPPORTED_BUILD_LEVEL) {
                "Build level '${request.buildLevel}' is not implemented for bundle type " +
                    "'${BundleType.RESOURCE.id}'. Only '$SUPPORTED_BUILD_LEVEL' is available today."
            }
            require(request.buildType.isNotBlank()) { "Build type cannot be blank" }

            val descriptor =
                Platforms.normalizeTarget(request.target)?.let { Platforms.describeTarget(it) }
                    ?: throw IllegalArgumentException(Platforms.unsupportedTargetMessage(request.target))

            val editor = TomlEditor(pyproject.readText())
            val packageName = (editor.getValue("project", "name") as? TomlValue.String)?.value ?: packageDir.name
            val version = (editor.getValue("project", "version") as? TomlValue.String)?.value ?: "0.0.0"

            val payload = collectPayload(packageDir, descriptor.family)
            require(payload.isNotEmpty()) {
                "No bundleable source found for package '$packageName' under " +
                    "${packageDir.absolutePath}/src/main, /src, or the package root."
            }

            val outputDir = resolveOutputDir(request, packageDir)
            prepareOutputDir(outputDir, request.overwrite)

            val entries = writePayload(outputDir, payload)
            val manifestFile = File(outputDir, MANIFEST_FILE_NAME)
            manifestFile.writeText(
                renderManifest(
                    packageName = packageName,
                    version = version,
                    descriptor = descriptor,
                    request = request,
                    entries = entries,
                ),
            )

            BundleResult(
                bundleType = BundleType.RESOURCE,
                outputDir = outputDir,
                manifestFile = manifestFile,
                fileCount = entries.size,
            )
        }

    private fun resolveOutputDir(
        request: BundleRequest,
        packageDir: File,
    ): File {
        val conventional =
            File(
                packageDir,
                "build/packpack/${BundleType.RESOURCE.id}/${request.buildType}/${request.buildLevel}",
            )
        val target = request.outputDir ?: conventional
        target.parentFile?.mkdirs()
        return target.canonicalFile
    }

    /**
     * Resolves the payload as `path relative to the python root` -> `source file`, applying the
     * platform overlay described in this class's KDoc (assumption 3).
     */
    private fun collectPayload(
        packageDir: File,
        family: String,
    ): Map<String, File> {
        val payload = sortedMapOf<String, File>()

        resolveCommonSourceRoot(packageDir)?.let { root ->
            findPythonPackages(root).forEach { pythonPackage -> payload.putAll(collectFrom(root, pythonPackage)) }
        }

        // The platform overlay is a partial tree by nature -- an `android/core/api.py` that shadows
        // one common module needs no `__init__.py` of its own -- so it is taken wholesale rather
        // than filtered through `findPythonPackages`.
        val platformRoot = File(packageDir, "src/$family")
        if (platformRoot.isDirectory) {
            payload.putAll(collectFrom(platformRoot, platformRoot))
        }

        return payload
    }

    /**
     * `src/main` -> `src` -> package root (assumption 4). Null when none of them holds a Python
     * package. "Holds a Python package" is the same test `Meson.findPythonPackages` uses, which is
     * also what keeps `pyproject.toml`/`meson.build` out of the payload in the package-root
     * fallback case.
     */
    private fun resolveCommonSourceRoot(packageDir: File): File? =
        listOf(
            File(packageDir, "src/main"),
            File(packageDir, "src"),
            packageDir,
        ).firstOrNull { root -> root.isDirectory && findPythonPackages(root).isNotEmpty() }

    private fun findPythonPackages(root: File): List<File> =
        root
            .listFiles()
            .orEmpty()
            .filter { it.isDirectory && !it.isExcludedDirectory() && File(it, "__init__.py").isFile }
            .sortedBy { it.name }

    private fun collectFrom(
        root: File,
        subtree: File,
    ): Map<String, File> =
        subtree
            .walkTopDown()
            .onEnter { directory -> directory == subtree || !directory.isExcludedDirectory() }
            .filter { it.isFile && !it.isExcludedFile() }
            .associateBy { file ->
                root
                    .toPath()
                    .relativize(file.toPath())
                    .toString()
                    .replace(File.separatorChar, '/')
            }

    private fun File.isExcludedDirectory(): Boolean = name in EXCLUDED_DIRECTORY_NAMES || name.startsWith(".")

    private fun File.isExcludedFile(): Boolean =
        name in EXCLUDED_FILE_NAMES ||
            extension.lowercase() in EXCLUDED_EXTENSIONS ||
            name.startsWith(".")

    private fun prepareOutputDir(
        outputDir: File,
        overwrite: Boolean,
    ) {
        if (outputDir.exists()) {
            require(overwrite) {
                "Bundle output already exists: ${outputDir.absolutePath}. Use --overwrite to replace it."
            }
            check(outputDir.deleteRecursively()) { "Failed to clear bundle output: ${outputDir.absolutePath}" }
        }
        check(outputDir.mkdirs() || outputDir.isDirectory) {
            "Failed to create bundle output: ${outputDir.absolutePath}"
        }
    }

    private fun writePayload(
        outputDir: File,
        payload: Map<String, File>,
    ): List<ManifestEntry> =
        payload.map { (relativePath, source) ->
            val destination = File(outputDir, "$PYTHON_ROOT/$relativePath")
            destination.parentFile.mkdirs()
            source.copyTo(destination, overwrite = true)
            ManifestEntry(
                path = "$PYTHON_ROOT/$relativePath",
                size = destination.length(),
                sha256 = destination.sha256(),
            )
        }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Renders the manifest by hand rather than pulling in `kotlinx-serialization-json`.
     *
     * The shape is small and fully known, and every extra runtime dependency added here also lands
     * in the published POM and in the GraalVM native image's closed world -- neither of which is
     * worth paying for one object literal. [jsonString] does the escaping; the structural test
     * `bundle_manifestIsValidJsonWithEscapedStrings` guards it.
     */
    private fun renderManifest(
        packageName: String,
        version: String,
        descriptor: Platforms.TargetDescriptor,
        request: BundleRequest,
        entries: List<ManifestEntry>,
    ): String =
        buildString {
            appendLine("{")
            appendLine("  \"formatVersion\": $FORMAT_VERSION,")
            appendLine("  \"bundleType\": ${BundleType.RESOURCE.id.jsonString()},")
            appendLine("  \"packageName\": ${packageName.jsonString()},")
            appendLine("  \"version\": ${version.jsonString()},")
            appendLine("  \"target\": ${descriptor.canonicalTarget.jsonString()},")
            appendLine("  \"platformFamily\": ${descriptor.family.jsonString()},")
            appendLine("  \"buildType\": ${request.buildType.jsonString()},")
            appendLine("  \"buildLevel\": ${request.buildLevel.jsonString()},")
            appendLine("  \"pythonRoot\": ${PYTHON_ROOT.jsonString()},")
            appendLine("  \"fileCount\": ${entries.size},")
            appendLine("  \"files\": [")
            entries.forEachIndexed { index, entry ->
                val separator = if (index == entries.lastIndex) "" else ","
                appendLine("    {")
                appendLine("      \"path\": ${entry.path.jsonString()},")
                appendLine("      \"size\": ${entry.size},")
                appendLine("      \"sha256\": ${entry.sha256.jsonString()}")
                appendLine("    }$separator")
            }
            appendLine("  ]")
            appendLine("}")
        }

    private fun String.jsonString(): String =
        buildString {
            append('"')
            this@jsonString.forEach { c ->
                when {
                    c == '"' -> append("\\\"")
                    c == '\\' -> append("\\\\")
                    c == '\n' -> append("\\n")
                    c == '\r' -> append("\\r")
                    c == '\t' -> append("\\t")
                    c < ' ' -> append("\\u%04x".format(c.code))
                    else -> append(c)
                }
            }
            append('"')
        }

    private data class ManifestEntry(
        val path: String,
        val size: Long,
        val sha256: String,
    )
}
