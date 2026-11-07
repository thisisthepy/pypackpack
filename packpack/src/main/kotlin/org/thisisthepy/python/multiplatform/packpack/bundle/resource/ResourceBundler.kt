package org.thisisthepy.python.multiplatform.packpack.bundle.resource

import org.thisisthepy.python.multiplatform.packpack.bundle.BundleRequest
import org.thisisthepy.python.multiplatform.packpack.bundle.BundleResult
import org.thisisthepy.python.multiplatform.packpack.bundle.BundleType
import org.thisisthepy.python.multiplatform.packpack.bundle.BundlerInterface
import org.thisisthepy.python.multiplatform.packpack.bundle.GilCheck
import org.thisisthepy.python.multiplatform.packpack.compile.extension.CompiledExtension
import org.thisisthepy.python.multiplatform.packpack.compile.extension.ExtensionCompileRequest
import org.thisisthepy.python.multiplatform.packpack.compile.extension.ExtensionCompileResult
import org.thisisthepy.python.multiplatform.packpack.compile.extension.ExtensionCompilerInterface
import org.thisisthepy.python.multiplatform.packpack.compile.extension.NotNativeModule
import org.thisisthepy.python.multiplatform.packpack.utils.Platforms
import org.thisisthepy.python.multiplatform.packpack.utils.deleteWithoutFollowingLinks
import org.thisisthepy.python.multiplatform.packpack.utils.findVenvInterpreter
import org.thisisthepy.python.multiplatform.packpack.utils.toml.TomlEditor
import org.thisisthepy.python.multiplatform.packpack.utils.toml.TomlValue
import java.io.File
import java.security.MessageDigest

/**
 * Bundle type `resource`: the handoff format from ppp to `python-multiplatform` and `toolchain`.
 *
 * ## What the SPEC actually says
 *
 * `docs/SPEC.md` states: *"`resource`: Produces a resource layout consumable by
 * python-multiplatform/toolchain"*, plus two things stated elsewhere in the same document that are
 * treated as binding here:
 *
 * - the output path convention `<package>/build/packpack/<bundle type>/<build type>/<build level>`,
 *   from SPEC's "User directory structure";
 * - the package source layout `<package>/src/{main,android,windows,...}`, from the same section,
 *   where `main` is platform-common and the sibling directories are platform-specific.
 *
 * ## Build levels (`instant`, `bytecode`, `mixed`, `native`)
 *
 * - `instant`: copies the payload as-is.
 * - `bytecode`: compiles every `.py` in the payload to a sibling `.pyc` (`compileall -b`) using the
 *   interpreter in the nearest `<project>/.venv`; `debug` keeps `.py` beside `.pyc`, `release`
 *   keeps only `.pyc`.
 * - `mixed` (`docs/design/compile-slot.md` §4): bytecode for the developer's modules and pure-Python
 *   library modules, plus prebuilt extensions from `libDirs`. Never calls the compile slot.
 * - `native` (`docs/design/compile-slot.md` §4, #20): builds `BundleRequest.nativeModules` through
 *   [ExtensionCompilerInterface], removes their `.py` sources from `python/`, places the built
 *   extensions under `python/`, compiles the remaining `.py` modules to bytecode, warns in
 *   [BundleResult.warnings] about every developer module (excluding `__init__.py`) that has no C,
 *   and records `pythonAbi`, `extensionSuffix`, `nativeModules` and `tools` in `resource-manifest.json`.
 */
class ResourceBundler(
    private val extensionCompiler: () -> ExtensionCompilerInterface = ExtensionCompilerInterface::create,
) : BundlerInterface {
    private companion object {
        const val MANIFEST_FILE_NAME = "resource-manifest.json"
        const val PYTHON_ROOT = "python"
        const val FORMAT_VERSION = 1
        const val BUILD_LEVEL_INSTANT = "instant"
        const val BUILD_LEVEL_BYTECODE = "bytecode"
        const val BUILD_LEVEL_MIXED = "mixed"
        const val BUILD_LEVEL_NATIVE = "native"
        val SUPPORTED_BUILD_LEVELS =
            setOf(
                BUILD_LEVEL_INSTANT,
                BUILD_LEVEL_BYTECODE,
                BUILD_LEVEL_MIXED,
                BUILD_LEVEL_NATIVE,
            )

        val EXCLUDED_FILE_NAMES = setOf("Thumbs.db")
        val EXCLUDED_EXTENSIONS = setOf("pyc", "pyo", "pyd")
        val EXCLUDED_DIRECTORY_NAMES = setOf("__pycache__", "build", "dist", "node_modules")
    }

    private enum class PayloadOrigin {
        DEVELOPER,
        META,
        LIB,
    }

    private data class PayloadFile(
        val file: File,
        val origin: PayloadOrigin,
    )

    override fun bundle(request: BundleRequest): Result<BundleResult> =
        runCatching {
            val packageDir = request.packageDir.canonicalFile
            require(packageDir.isDirectory) { "Package directory not found: ${packageDir.absolutePath}" }

            val pyproject = File(packageDir, "pyproject.toml")
            require(pyproject.isFile) { "pyproject.toml not found in ${packageDir.absolutePath}" }

            require(request.buildLevel in SUPPORTED_BUILD_LEVELS) {
                "Build level '${request.buildLevel}' is not implemented for bundle type " +
                    "'${BundleType.RESOURCE.id}'. Only ${SUPPORTED_BUILD_LEVELS.joinToString(", ") { "'$it'" }} " +
                    "are available today."
            }
            require(request.buildType.isNotBlank()) { "Build type cannot be blank" }

            val descriptor =
                Platforms.normalizeTarget(request.target)?.let { Platforms.describeTarget(it) }
                    ?: throw IllegalArgumentException(Platforms.unsupportedTargetMessage(request.target))
            Platforms.requireValidMinSdk(request.minSdk, descriptor.family)

            val editor = TomlEditor(pyproject.readText())
            val packageName = (editor.getValue("project", "name") as? TomlValue.String)?.value ?: packageDir.name
            val version = (editor.getValue("project", "version") as? TomlValue.String)?.value ?: "0.0.0"

            val payload = collectPayload(packageDir, descriptor.family, request.metaDirs, request.libDirs)
            require(payload.isNotEmpty()) {
                "No bundleable source found for package '$packageName' under " +
                    "${packageDir.absolutePath}/src/main, /src, or the package root."
            }

            val compiler: ExtensionCompilerInterface?
            if (request.buildLevel == BUILD_LEVEL_NATIVE) {
                val abi = requireNotNull(request.pythonAbi) { "Build level 'native' needs BundleRequest.pythonAbi." }
                val includeDir = requireNotNull(request.cpythonIncludeDir) { "Build level 'native' needs BundleRequest.cpythonIncludeDir." }
                requireNotNull(request.extensionSuffix) { "Build level 'native' needs BundleRequest.extensionSuffix." }
                compiler = extensionCompiler()
                compiler.checkSupport(
                    target = descriptor.canonicalTarget,
                    pythonAbi = abi,
                    includeDir = includeDir,
                    libDir = request.cpythonLibDir,
                    libraryName = request.cpythonLibraryName,
                ).getOrThrow()
            } else {
                compiler = null
            }

            val outputDir = resolveOutputDir(request, packageDir)
            prepareOutputDir(outputDir, request.overwrite)

            writePayload(outputDir, payload)
            val pythonRoot = File(outputDir, PYTHON_ROOT)

            val nativeResult: ExtensionCompileResult?
            val nativeWarnings = mutableListOf<String>()
            if (request.buildLevel == BUILD_LEVEL_NATIVE) {
                val abi = request.pythonAbi!!
                val includeDir = request.cpythonIncludeDir!!
                val suffix = request.extensionSuffix!!
                val staging = File(packageDir, "build/packpack/compile/${descriptor.canonicalTarget}/${request.buildType}")
                val out = File(staging, "out").apply { deleteWithoutFollowingLinks() }
                val compiled =
                    if (request.nativeModules.isNotEmpty()) {
                        compiler!!.compile(
                            ExtensionCompileRequest(
                                modules = request.nativeModules,
                                target = descriptor.canonicalTarget,
                                pythonAbi = abi,
                                buildType = request.buildType,
                                workingDir = packageDir,
                                buildDir = File(staging, "work"),
                                outputDir = out,
                                includeDir = includeDir,
                                extensionSuffix = suffix,
                                libDir = request.cpythonLibDir,
                                libraryName = request.cpythonLibraryName,
                                minSdk = request.minSdk,
                                options = request.nativeOptions,
                            ),
                        ).getOrThrow()
                    } else {
                        null
                    }
                if (compiled != null) {
                    removeReplacedSources(pythonRoot, compiled.modules)
                    out.copyRecursively(pythonRoot, overwrite = true)
                }
                val compiledNames = compiled?.modules?.map { it.moduleName }?.toSet().orEmpty()
                val developerPyModules =
                    payload
                        .filter { (relPath, entry) -> entry.origin == PayloadOrigin.DEVELOPER && relPath.endsWith(".py") }
                        .keys
                        .map { relPath -> relPath.removeSuffix(".py").replace('/', '.') }
                        .filter { mod -> mod.substringAfterLast('.') != "__init__" && mod !in compiledNames }
                        .sorted()
                val reasonsByModule = request.notNativeModules.associateBy { it.module }
                developerPyModules.forEach { mod ->
                    nativeWarnings += formatNativeWarning(mod, reasonsByModule[mod])
                }
                nativeResult = compiled
            } else {
                nativeResult = null
            }

            val entries =
                if (request.buildLevel == BUILD_LEVEL_INSTANT) {
                    scanEntries(pythonRoot)
                } else {
                    compileToBytecode(packageDir, pythonRoot, keepSource = request.buildType != "release")
                }

            val manifestFile = File(outputDir, MANIFEST_FILE_NAME)
            manifestFile.writeText(
                renderManifest(
                    packageName = packageName,
                    version = version,
                    descriptor = descriptor,
                    request = request,
                    entries = entries,
                    nativeResult = nativeResult,
                ),
            )

            val allPaths = entries.map { it.path.removePrefix("$PYTHON_ROOT/") }
            BundleResult(
                bundleType = BundleType.RESOURCE,
                outputDir = outputDir,
                manifestFile = manifestFile,
                fileCount = entries.size,
                warnings = nativeWarnings + gilWarnings(request, descriptor.canonicalTarget, packageDir, pythonRoot, allPaths),
            )
        }

    private fun removeReplacedSources(
        pythonRoot: File,
        compiled: List<CompiledExtension>,
    ) {
        compiled.forEach { ext ->
            val pyPath = ext.moduleName.replace('.', '/') + ".py"
            File(pythonRoot, pyPath).delete()
        }
    }

    private fun formatNativeWarning(
        module: String,
        reason: NotNativeModule?,
    ): String =
        when (reason?.kind) {
            NotNativeModule.Kind.NOT_MARKED ->
                "$module ships as bytecode: not marked for compilation (add @compiled)."
            NotNativeModule.Kind.NOTHING_COMPILED ->
                if (reason.items.isEmpty()) {
                    "$module ships as bytecode: nothing in the module could be compiled."
                } else {
                    reason.items.joinToString("\n") { item ->
                        val loc = if (item.line != null) "${item.file}:${item.line}" else item.file
                        "$loc: ${item.name}: ${item.message} ($module ships as bytecode)"
                    }
                }
            null ->
                "$module ships as bytecode: no C was supplied for it."
        }

    /**
     * [GilCheck]'s warnings: the tag check on every target; on the host, unless
     * [BundleRequest.gilImportProbe] is off, the import probe with the project's free-threaded `.venv`
     * interpreter. Without such an interpreter the probe cannot run, and that is a warning too.
     */
    private fun gilWarnings(
        request: BundleRequest,
        canonicalTarget: String,
        packageDir: File,
        pythonRoot: File,
        paths: Collection<String>,
    ): List<String> {
        val tagged = GilCheck.tagWarnings(paths)
        val modules = GilCheck.extensionModules(paths)
        if (!request.gilImportProbe || modules.isEmpty() || canonicalTarget != Platforms.detectHostTarget()) {
            return tagged
        }
        val interpreter = findVenvInterpreter(packageDir)?.takeIf { GilCheck.isFreeThreaded(it) }
            ?: return tagged +
                "${modules.size} extension(s) were not checked for the GIL: no free-threaded interpreter " +
                "under a '.venv' above ${packageDir.absolutePath} (`pypackpack python install 3.15t`)."
        return tagged + GilCheck.probeWarnings(pythonRoot, modules, interpreter)
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
     * Resolves the payload as `path relative to the python root` -> [PayloadFile], applying the
     * platform overlay and recording whether each file came from developer source, `metaDirs` or `libDirs`.
     */
    private fun collectPayload(
        packageDir: File,
        family: String,
        metaDirs: List<File>,
        libDirs: List<File>,
    ): Map<String, PayloadFile> {
        val payload = sortedMapOf<String, PayloadFile>()

        resolveCommonSourceRoot(packageDir)?.let { root ->
            findPythonPackages(root).forEach { pythonPackage ->
                collectFrom(root, pythonPackage).forEach { (rel, file) ->
                    payload[rel] = PayloadFile(file, PayloadOrigin.DEVELOPER)
                }
            }
        }

        val platformRoot = File(packageDir, "src/$family")
        if (platformRoot.isDirectory) {
            collectFrom(platformRoot, platformRoot).forEach { (rel, file) ->
                payload[rel] = PayloadFile(file, PayloadOrigin.DEVELOPER)
            }
        }

        metaDirs.forEach { dir ->
            if (dir.isDirectory) {
                collectFrom(dir, dir).forEach { (rel, file) ->
                    payload[rel] = PayloadFile(file, PayloadOrigin.META)
                }
            }
        }
        libDirs.forEach { dir ->
            if (dir.isDirectory) {
                collectFrom(dir, dir).forEach { (rel, file) ->
                    payload[rel] = PayloadFile(file, PayloadOrigin.LIB)
                }
            }
        }

        return payload
    }

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
            .onEnter { directory -> directory == subtree || (!directory.isExcludedDirectory() && !File(directory, "Cargo.toml").isFile) }
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
            check(outputDir.deleteWithoutFollowingLinks()) { "Failed to clear bundle output: ${outputDir.absolutePath}" }
        }
        check(outputDir.mkdirs() || outputDir.isDirectory) {
            "Failed to create bundle output: ${outputDir.absolutePath}"
        }
    }

    private fun writePayload(
        outputDir: File,
        payload: Map<String, PayloadFile>,
    ) {
        payload.forEach { (relativePath, item) ->
            val destination = File(outputDir, "$PYTHON_ROOT/$relativePath")
            destination.parentFile.mkdirs()
            item.file.copyTo(destination, overwrite = true)
        }
    }

    /**
     * Compiles remaining `.py` files under [pythonRoot] to sibling `.pyc` files (`compileall -b`)
     * using the interpreter [locateBytecodeCompiler] finds, then drops the `.py` sources when
     * [keepSource] is `false`. If no `.py` files remain (e.g. every module at `native` became an
     * extension), `compileall` is not needed.
     */
    private fun compileToBytecode(
        packageDir: File,
        pythonRoot: File,
        keepSource: Boolean,
    ): List<ManifestEntry> {
        val pyFiles = pythonRoot.walkTopDown().filter { it.isFile && it.extension == "py" }.toList()
        if (pyFiles.isEmpty()) {
            return scanEntries(pythonRoot)
        }
        val python = locateBytecodeCompiler(packageDir)
        val process =
            ProcessBuilder(python.absolutePath, "-m", "compileall", "-q", "-b", pythonRoot.absolutePath)
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        check(exit == 0) {
            "compileall failed (exit $exit) compiling ${pythonRoot.absolutePath} with ${python.absolutePath}:\n$output"
        }

        if (!keepSource) {
            pyFiles.forEach { it.delete() }
        }
        return scanEntries(pythonRoot)
    }

    private fun locateBytecodeCompiler(startDir: File): File =
        findVenvInterpreter(startDir) ?: throw IllegalStateException(
            "No Python interpreter found under a '.venv' above ${startDir.absolutePath}. " +
                "Run `pypackpack python install 3.15t` first to build the 'bytecode' level.",
        )

    private fun scanEntries(pythonRoot: File): List<ManifestEntry> =
        pythonRoot
            .walkTopDown()
            .filter { it.isFile }
            .map { file ->
                val relative =
                    pythonRoot
                        .toPath()
                        .relativize(file.toPath())
                        .toString()
                        .replace(File.separatorChar, '/')
                ManifestEntry(path = "$PYTHON_ROOT/$relative", size = file.length(), sha256 = file.sha256())
            }.sortedBy { it.path }
            .toList()

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

    private fun renderManifest(
        packageName: String,
        version: String,
        descriptor: Platforms.TargetDescriptor,
        request: BundleRequest,
        entries: List<ManifestEntry>,
        nativeResult: ExtensionCompileResult?,
    ): String =
        buildString {
            appendLine("{")
            appendLine("  \"formatVersion\": $FORMAT_VERSION,")
            appendLine("  \"bundleType\": ${BundleType.RESOURCE.id.jsonString()},")
            appendLine("  \"packageName\": ${packageName.jsonString()},")
            appendLine("  \"version\": ${version.jsonString()},")
            if (request.versionName != null) {
                appendLine("  \"versionName\": ${request.versionName.jsonString()},")
            }
            if (request.versionCode != null) {
                appendLine("  \"versionCode\": ${request.versionCode},")
            }
            appendLine("  \"target\": ${descriptor.canonicalTarget.jsonString()},")
            appendLine("  \"platformFamily\": ${descriptor.family.jsonString()},")
            if (request.minSdk != null) {
                appendLine("  \"minSdk\": ${request.minSdk},")
            }
            appendLine("  \"buildType\": ${request.buildType.jsonString()},")
            appendLine("  \"buildLevel\": ${request.buildLevel.jsonString()},")
            if (request.buildLevel == BUILD_LEVEL_NATIVE && request.pythonAbi != null && request.extensionSuffix != null) {
                val abiTag = "cp${request.pythonAbi.version.replace(".", "")}t"
                appendLine("  \"pythonAbi\": ${abiTag.jsonString()},")
                appendLine("  \"extensionSuffix\": ${request.extensionSuffix.jsonString()},")
                val nativeMods = nativeResult?.modules.orEmpty()
                if (nativeMods.isEmpty()) {
                    appendLine("  \"nativeModules\": [],")
                } else {
                    appendLine("  \"nativeModules\": [")
                    nativeMods.forEachIndexed { idx, mod ->
                        val sep = if (idx == nativeMods.lastIndex) "" else ","
                        appendLine("    { \"module\": ${mod.moduleName.jsonString()}, \"path\": ${"$PYTHON_ROOT/${mod.relativePath}".jsonString()} }$sep")
                    }
                    appendLine("  ],")
                }
                val tools = nativeResult?.toolVersions.orEmpty()
                if (tools.isNotEmpty()) {
                    val toolEntries = tools.entries.joinToString(", ") { (k, v) -> "${k.jsonString()}: ${v.jsonString()}" }
                    appendLine("  \"tools\": { $toolEntries },")
                }
            }
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
