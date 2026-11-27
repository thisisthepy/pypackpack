package org.thisisthepy.python.multiplatform.packpack.compile.backend.external

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.thisisthepy.python.multiplatform.packpack.dependency.backend.UVBackend
import org.thisisthepy.python.multiplatform.packpack.utils.findVenvInterpreter
import org.thisisthepy.python.multiplatform.packpack.utils.toml.TomlEditor
import org.thisisthepy.python.multiplatform.packpack.utils.toml.TomlValue
import java.io.File

/**
 * Meson build system wrapper (adapter pattern for Clang, MSVC, NDK, XCode)
 */
open class Meson(
    private val uv: UVBackend = UVBackend(),
) {
    open suspend fun installMeson(): Result<String> {
        uv.executeCommand(listOf("tool", "install", "meson"))
        uv.executeCommand(listOf("tool", "install", "ninja"))
        return Result.success("Meson and Ninja installed successfully")
    } // Now meson installed default uv tool's path(~/.local/share/uv/tools)

    /**
     * Probes whether `meson` and `ninja` are runnable on PATH, either because they were
     * installed system-wide or because a prior [installMeson] call put them there (`uv tool
     * install` places its shims under `~/.local/share/uv/tools`, which is expected to already be
     * on PATH once installed once). [setup] uses this to decide whether it needs to call
     * [installMeson] first.
     */
    open suspend fun isMesonInstalled(): Boolean {
        if (isExecutableAvailable("meson") && isExecutableAvailable("ninja")) {
            return true
        }
        val binPath = uv.executeCommand(listOf("tool", "dir", "--bin")).getOrNull()?.trim()
        if (binPath != null) {
            val isWindows = System.getProperty("os.name").lowercase().contains("windows")
            val mesonExe = if (isWindows) "$binPath${File.separator}meson.exe" else "$binPath${File.separator}meson"
            val ninjaExe = if (isWindows) "$binPath${File.separator}ninja.exe" else "$binPath${File.separator}ninja"
            return File(mesonExe).exists() && File(ninjaExe).exists()
        }
        return false
    }

    private fun isExecutableAvailable(command: String): Boolean =
        try {
            val process =
                ProcessBuilder(command, "--version")
                    .redirectErrorStream(true)
                    .start()
            process.inputStream.bufferedReader().readText()
            process.waitFor() == 0
        } catch (e: Exception) {
            false
        }

    open suspend fun setup(
        buildDir: String,
        options: List<String>?,
        workingDir: File? = null,
        overwrite: Boolean = false,
        cross: MesonCross? = null,
    ): Result<String> =
        runCatching {
            if (!isMesonInstalled()) {
                installMeson().getOrThrow()
            }
            val projectDir = workingDir ?: File(System.getProperty("user.dir"))
            makeMesonBuild(projectDir.absolutePath, overwrite, cross).getOrThrow()
            val machineFiles =
                if (cross != null) {
                    val crossFile = File(projectDir, "build/packpack/meson-cross-${cross.target.canonicalTarget}.ini")
                    crossFile.parentFile.mkdirs()
                    crossFile.writeText(cross.crossFileText())
                    listOf("--cross-file", crossFile.path)
                } else {
                    pythonNativeFile(projectDir)?.let { listOf("--native-file", it.path) }.orEmpty()
                }
            executeCommand(listOf("setup", buildDir) + machineFiles + options.orEmpty(), projectDir).getOrThrow()
        }

    open suspend fun compile(
        buildDir: String,
        options: List<String>?,
        workingDir: File? = null,
    ): Result<String> =
        runCatching {
            executeCommand(listOf("compile", "-C", buildDir) + options.orEmpty(), workingDir).getOrThrow()
        }

    open suspend fun install(
        buildDir: String,
        options: List<String>?,
        workingDir: File? = null,
        destdir: String? = null,
    ): Result<String> =
        runCatching {
            val defaultDestdir = workingDir?.let { "$it/dist" } ?: "dist"
            val actualDestdir = destdir ?: defaultDestdir
            executeCommand(listOf("install", "-C", buildDir, "--destdir=$actualDestdir") + options.orEmpty(), workingDir).getOrThrow()
        }

    /**
     * A Meson native file naming the project's `.venv` interpreter as `python`, which is what
     * `py.find_installation()` then builds against (its headers, `EXT_SUFFIX`, free-threading).
     * Without it Meson uses the interpreter it runs on itself (uv's tool Python). `null` when the
     * project has no `.venv`; Meson's own interpreter is used then.
     */
    private fun pythonNativeFile(projectDir: File): File? {
        val interpreter = findVenvInterpreter(projectDir) ?: return null
        val nativeFile = File(projectDir, "build/packpack/meson-python.ini")
        nativeFile.parentFile.mkdirs()
        nativeFile.writeText("[binaries]\npython = ${interpreter.canonicalPath.toMesonString()}\n")
        return nativeFile
    }

    internal fun makeMesonBuild(
        projectDir: String = System.getProperty("user.dir"),
        overwrite: Boolean = false,
        cross: MesonCross? = null,
    ): Result<String> =
        runCatching {
            val project = File(projectDir).absoluteFile
            require(project.isDirectory) { "Project directory not found: ${project.absolutePath}" }

            val pyproject = File(project, "pyproject.toml")
            require(pyproject.isFile) { "pyproject.toml not found in ${project.absolutePath}" }

            val mesonBuild = File(project, "meson.build")
            require(overwrite || !mesonBuild.exists()) {
                "meson.build already exists: ${mesonBuild.absolutePath}. Use --overwrite to regenerate it."
            }

            val content = buildMesonBuildContent(project, pyproject, cross)
            mesonBuild.writeText(content)
            content
        }

    private fun buildMesonBuildContent(
        project: File,
        pyproject: File,
        cross: MesonCross? = null,
    ): String {
        val editor = TomlEditor(pyproject.readText())
        val projectName = (editor.getValue("project", "name") as? TomlValue.String)?.value ?: project.name
        val version = (editor.getValue("project", "version") as? TomlValue.String)?.value ?: "0.0.0"
        val packages = findPythonPackages(project)

        require(packages.isNotEmpty()) {
            "No Python package found under [tool.ppp].python-dir, src/main, src, python, <package>/python, or ${project.absolutePath}"
        }

        val explicitCExtensions = resolveExplicitCExtensions(project, editor)
        val extensionModules = collectExtensionModules(project, packages, explicitCExtensions)
        val extensionPaths = extensionModules.map { it.path }.toSet()
        val installSources = collectInstallSources(project, packages, extensionPaths)
        val limitedApiVersion = resolveLimitedApiVersion(editor)

        return buildString {
            appendLine("project(")
            appendLine("  ${projectName.toMesonString()},")
            extensionModules
                .map { it.language }
                .distinct()
                .sortedWith(compareBy { if (it == "c") 0 else 1 })
                .forEach { language ->
                    appendLine("  ${language.toMesonString()},")
            }
            appendLine("  version: ${version.toMesonString()},")
            // 1.3.0 is the first Meson with py.extension_module(limited_api:).
            appendLine("  meson_version: '>=1.3.0',")
            appendLine(")")
            if (cross != null) {
                appendCrossBody(cross, extensionModules, installSources)
                return@buildString
            }
            appendLine()
            appendLine("python = import('python')")
            appendLine("py = python.find_installation()")

            extensionModules.forEach { extension ->
                appendLine()
                appendLine("py.extension_module(")
                appendLine("  ${extension.name.toMesonString()},")
                appendLine("  files(${extension.path.toMesonString()}),")
                // abi3t by default (SPEC, Scope decisions): Py_LIMITED_API on a free-threaded 3.15
                // interpreter selects PEP 803's abi3t. Opt out with -Dpython.allow_limited_api=false.
                appendLine("  limited_api: ${limitedApiVersion.toMesonString()},")
                appendLine("  install: true,")
                appendLine("  subdir: ${extension.subdir.toMesonString()},")
                appendLine(")")
            }

            installSources.forEach { group ->
                appendLine()
                appendLine("py.install_sources(")
                appendLine("  files(")
                group.paths.forEach { path ->
                    appendLine("    ${path.toMesonString()},")
                }
                appendLine("  ),")
                appendLine("  subdir: ${group.subdir.toMesonString()},")
                appendLine(")")
            }
        }
    }

    /**
     * The cross build's body (#155): no `import('python')` (Meson cannot run the target interpreter), so each
     * extension is a `shared_module` against [MesonCross.target]'s headers with its `EXT_SUFFIX`, and the
     * sources go into `lib/python3/site-packages/<subdir>` like `py.install_sources` would.
     */
    private fun StringBuilder.appendCrossBody(
        cross: MesonCross,
        extensionModules: List<ExtensionModule>,
        installSources: List<InstallSourceGroup>,
    ) {
        fun installDir(subdir: String) = ("lib/python3/site-packages/" + subdir).trimEnd('/').toMesonString()
        appendLine()
        appendLine("target_python_include = include_directories(${cross.target.includeDir.absolutePath.toMesonString()})")
        val suffix = cross.target.extensionSuffix.removePrefix(".")
        extensionModules.forEach { extension ->
            appendLine()
            appendLine("shared_module(")
            appendLine("  ${extension.name.toMesonString()},")
            appendLine("  files(${extension.path.toMesonString()}),")
            appendLine("  include_directories: target_python_include,")
            appendLine("  name_prefix: '',")
            appendLine("  name_suffix: ${suffix.toMesonString()},")
            appendLine("  link_args: [${cross.target.linkArgs.joinToString(", ") { it.toMesonString() }}],")
            appendLine("  install: true,")
            appendLine("  install_dir: ${installDir(extension.subdir)},")
            appendLine(")")
        }
        installSources.forEach { group ->
            appendLine()
            appendLine("install_data(")
            appendLine("  files(")
            group.paths.forEach { path -> appendLine("    ${path.toMesonString()},") }
            appendLine("  ),")
            appendLine("  install_dir: ${installDir(group.subdir)},")
            appendLine(")")
        }
    }

    private fun findPythonPackages(project: File): List<File> =
        resolvePythonSourceRoot(project)?.let(::pythonPackageDirs).orEmpty()

    private fun resolveExplicitCExtensions(
        project: File,
        editor: TomlEditor,
    ): Set<String>? {
        val value = editor.getValue("tool.ppp", "c-extensions") ?: return null
        val array =
            value as? TomlValue.Array
                ?: throw IllegalArgumentException("[tool.ppp].c-extensions must be an array of file paths.")
        val projectCanonical = project.canonicalFile
        return array.items.map { rawPath ->
            val normalized = rawPath.replace('\\', '/').trimStart('/')
            val file = File(projectCanonical, normalized).canonicalFile
            require(file.toPath().startsWith(projectCanonical.toPath())) {
                "[tool.ppp].c-extensions: '$rawPath' is outside the package."
            }
            require(file.isFile) {
                "[tool.ppp].c-extensions: '$rawPath' not found."
            }
            require(file.extensionLanguage() != null) {
                "[tool.ppp].c-extensions: '$rawPath' is not a C/C++ source file."
            }
            projectCanonical.relativePath(file)
        }.toSet()
    }

    private fun collectInstallSources(
        project: File,
        packages: List<File>,
        extensionPaths: Set<String>,
    ): List<InstallSourceGroup> =
        packages
            .flatMap { packageDir ->
                packageDir
                    .walkTopDown()
                    // A crate directory is the Cargo backend's (#92): none of its files are Meson's.
                    .onEnter { dir -> dir == packageDir || (!dir.isExcludedDirectory() && !File(dir, "Cargo.toml").isFile) }
                    .filter { it.isFile && !it.isExcludedFile() && project.relativePath(it) !in extensionPaths }
                    .map { source ->
                        packageDir.installSubdir(source.parentFile) to project.relativePath(source)
                    }
            }.groupBy(
                keySelector = { it.first },
                valueTransform = { it.second },
            ).map { (subdir, paths) ->
                InstallSourceGroup(subdir, paths.sorted())
            }.sortedBy { it.subdir }

    private fun collectExtensionModules(
        project: File,
        packages: List<File>,
        explicitCExtensions: Set<String>?,
    ): List<ExtensionModule> =
        packages
            .flatMap { packageDir ->
                packageDir
                    .walkTopDown()
                    // A crate directory is the Cargo backend's (#92): none of its files are Meson's.
                    .onEnter { dir -> dir == packageDir || (!dir.isExcludedDirectory() && !File(dir, "Cargo.toml").isFile) }
                    .filter { source ->
                        source.isFile &&
                            !source.isExcludedFile() &&
                            if (explicitCExtensions != null) {
                                project.relativePath(source) in explicitCExtensions
                            } else {
                                source.isCExtensionModuleSource()
                            }
                    }
                    .mapNotNull { source ->
                        val language = source.extensionLanguage() ?: return@mapNotNull null
                        ExtensionModule(
                            name = source.nameWithoutExtension,
                            path = project.relativePath(source),
                            subdir = packageDir.installSubdir(source.parentFile),
                            language = language,
                        )
                    }
            }.sortedWith(compareBy<ExtensionModule> { it.subdir }.thenBy { it.name })

    private fun File.installSubdir(directory: File): String {
        val relative =
            toPath()
                .relativize(directory.toPath())
                .toString()
                .replace(File.separatorChar, '/')

        return if (relative.isBlank()) name else "$name/$relative"
    }

    private fun File.relativePath(child: File): String =
        toPath()
            .relativize(child.toPath())
            .toString()
            .replace(File.separatorChar, '/')

    private fun File.isExcludedFile(): Boolean =
        name.startsWith(".") ||
            name == "Thumbs.db" ||
            name == "meson.build" ||
            extension.lowercase() in EXCLUDED_EXTENSIONS

    private fun File.isCExtensionModuleSource(): Boolean {
        if (extensionLanguage() == null) return false
        if (nameWithoutExtension.startsWith("_")) return true
        val text = runCatching { readText() }.getOrNull() ?: return false
        return "PyInit_" in text || "PyModExport_" in text || "Python.h" in text
    }

    private fun File.extensionLanguage(): String? =
        when (extension.lowercase()) {
            "c" -> "c"
            "cc", "cpp", "cxx" -> "cpp"
            else -> null
        }

    private fun String.toMesonString(): String = "'${replace("\\", "\\\\").replace("'", "\\'")}'"

    private data class InstallSourceGroup(
        val subdir: String,
        val paths: List<String>,
    )

    private data class ExtensionModule(
        val name: String,
        val path: String,
        val subdir: String,
        val language: String,
    )

    companion object {
        private const val DEFAULT_LIMITED_API_VERSION = "3.15"
        private val REQUIRES_PYTHON_FLOOR = Regex("""(?:>=|==)\s*(3\.\d+)""")
        private val RUST_ABI_VERSION = Regex("""^abi3t?-py3(\d+)$""")
        private val EXCLUDED_DIRECTORY_NAMES = setOf("__pycache__", "build", "dist", "target", "node_modules")
        private val EXCLUDED_EXTENSIONS = setOf("pyc", "pyo")

        /**
         * Derives Meson's `py.extension_module(limited_api:)` version from `pyproject.toml`:
         * 1. `[project].requires-python` `>=3.<minor>` or `==3.<minor>` floor, when present;
         * 2. `[tool.ppp.rust]` `abi` (`abi3-py3<minor>` / `abi3t-py3<minor>`), either package-level or
         *    on a crate inline table;
         * 3. `"3.15"` (the ecosystem default, PEP 803 `abi3t`).
         */
        internal fun resolveLimitedApiVersion(editor: TomlEditor): String {
            val requiresPython = (editor.getValue("project", "requires-python") as? TomlValue.String)?.value
            if (!requiresPython.isNullOrBlank()) {
                REQUIRES_PYTHON_FLOOR.find(requiresPython)?.let { return it.groupValues[1] }
            }

            val rustAbi =
                (editor.getValue("tool.ppp.rust", "abi") as? TomlValue.String)?.value
                    ?: editor.getTableKeys("tool.ppp.rust")
                        .asSequence()
                        .mapNotNull { key ->
                            (editor.getValue("tool.ppp.rust", key) as? TomlValue.InlineTable)
                                ?.pairs
                                ?.get("abi")
                                ?.let { it as? TomlValue.String }
                                ?.value
                        }.firstOrNull()
            if (!rustAbi.isNullOrBlank()) {
                RUST_ABI_VERSION.matchEntire(rustAbi.trim())?.let { return "3.${it.groupValues[1]}" }
            }

            return DEFAULT_LIMITED_API_VERSION
        }

        internal fun File.isExcludedDirectory(): Boolean =
            name in EXCLUDED_DIRECTORY_NAMES || name.startsWith(".")

        internal fun pythonPackageDirs(root: File): List<File> =
            root
                .takeIf { it.isDirectory }
                ?.listFiles()
                .orEmpty()
                .filter { it.isDirectory && !it.isExcludedDirectory() && File(it, "__init__.py").isFile }
                .sortedBy { it.name }

        internal fun resolvePythonSourceRoot(project: File): File? {
            val pyproject = File(project, "pyproject.toml")
            val editor = pyproject.takeIf { it.isFile }?.let { TomlEditor(it.readText()) }
            val configuredDir =
                (editor?.getValue("tool.ppp", "python-dir") as? TomlValue.String)
                    ?.value
                    ?.takeIf { it.isNotBlank() }
            val projectName =
                (editor?.getValue("project", "name") as? TomlValue.String)
                    ?.value
                    ?.takeIf { it.isNotBlank() }

            val candidates =
                buildList {
                    if (configuredDir != null) {
                        add(File(project, configuredDir))
                    }
                    add(File(project, "src/main"))
                    add(File(project, "src"))
                    add(File(project, "python"))
                    if (projectName != null) {
                        add(File(project, "$projectName/python"))
                    }
                    add(File(project, "${project.name}/python"))
                    add(project)
                }.distinctBy { it.canonicalPath }

            return candidates.firstOrNull { candidate ->
                pythonPackageDirs(candidate).isNotEmpty()
            }
        }

        internal fun resolveMesonExecutable(
            binPath: String?,
            isMesonInstalled: Boolean,
            isWindows: Boolean,
            fileExists: (String) -> Boolean
        ): String {
            if (isMesonInstalled) return "meson"
            if (binPath == null) {
                throw IllegalStateException("Meson is not installed and 'uv' is not available. Please install meson or uv to proceed.")
            }
            val exeName = if (isWindows) "meson.exe" else "meson"
            val exePath = "$binPath${File.separator}$exeName"
            if (!fileExists(exePath)) {
                throw IllegalStateException("Meson is not installed and 'uv tool dir' ($binPath) does not contain $exeName. Please install meson.")
            }
            return exePath
        }
    }

    open suspend fun executeCommand(
        command: List<String>,
        workingDir: File? = null,
    ): Result<String> =
        runCatching {
            withContext(Dispatchers.IO) {
                val isInstalled = isMesonInstalled()
                val binPath = if (!isInstalled) uv.executeCommand(listOf("tool", "dir", "--bin")).getOrNull()?.trim() else null
                val isWindows = System.getProperty("os.name").lowercase().contains("windows")
                
                val mesonExecutable = resolveMesonExecutable(
                    binPath = binPath,
                    isMesonInstalled = isInstalled,
                    isWindows = isWindows,
                    fileExists = { File(it).exists() }
                )

                val fullCommand = listOf(mesonExecutable) + command
                val processBuilder = ProcessBuilder(fullCommand).redirectErrorStream(true)

                if (binPath != null) {
                    val env = processBuilder.environment()
                    val pathVar = if (isWindows) "Path" else "PATH"
                    env[pathVar] = "$binPath${File.pathSeparator}${env[pathVar] ?: ""}"
                }

                if (workingDir != null) {
                    processBuilder.directory(workingDir)
                }

                val process = processBuilder.start()
                val output = process.inputStream.bufferedReader().readText()
                val exitCode = process.waitFor()

                if (exitCode == 0) {
                    output
                } else {
                    throw Exception("Command failed with exit code $exitCode:\n$output")
                }
            }
        }
}
