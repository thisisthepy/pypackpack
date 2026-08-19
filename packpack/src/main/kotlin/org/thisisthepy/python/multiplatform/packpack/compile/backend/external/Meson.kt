package org.thisisthepy.python.multiplatform.packpack.compile.backend.external

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.thisisthepy.python.multiplatform.packpack.dependency.backend.UVBackend
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
    ): Result<String> =
        runCatching {
            if (!isMesonInstalled()) {
                installMeson().getOrThrow()
            }
            val projectDir = workingDir ?: File(System.getProperty("user.dir"))
            makeMesonBuild(projectDir.absolutePath, overwrite).getOrThrow()
            executeCommand(listOf("setup", buildDir) + options.orEmpty(), projectDir).getOrThrow()
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

    internal fun makeMesonBuild(
        projectDir: String = System.getProperty("user.dir"),
        overwrite: Boolean = false,
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

            val content = buildMesonBuildContent(project, pyproject)
            mesonBuild.writeText(content)
            content
        }

    private fun buildMesonBuildContent(
        project: File,
        pyproject: File,
    ): String {
        val editor = TomlEditor(pyproject.readText())
        val projectName = (editor.getValue("project", "name") as? TomlValue.String)?.value ?: project.name
        val version = (editor.getValue("project", "version") as? TomlValue.String)?.value ?: "0.0.0"
        val packages = findPythonPackages(project)

        require(packages.isNotEmpty()) {
            "No Python package found under src/main, src, or ${project.absolutePath}"
        }

        val installSources = collectInstallSources(project, packages)
        val extensionModules = collectExtensionModules(project, packages)

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
            appendLine("  meson_version: '>=1.0.0',")
            appendLine(")")
            appendLine()
            appendLine("python = import('python')")
            appendLine("py = python.find_installation()")

            extensionModules.forEach { extension ->
                appendLine()
                appendLine("py.extension_module(")
                appendLine("  ${extension.name.toMesonString()},")
                appendLine("  files(${extension.path.toMesonString()}),")
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

    private fun findPythonPackages(project: File): List<File> =
        listOf(
            File(project, "src/main"),
            File(project, "src"),
            project,
        ).firstNotNullOfOrNull { root ->
            root
                .takeIf { it.isDirectory }
                ?.listFiles()
                ?.filter { it.isDirectory && File(it, "__init__.py").isFile }
                ?.sortedBy { it.name }
                ?.takeIf { it.isNotEmpty() }
        }.orEmpty()

    private fun collectInstallSources(
        project: File,
        packages: List<File>,
    ): List<InstallSourceGroup> =
        packages
            .flatMap { packageDir ->
                packageDir
                    .walkTopDown()
                    .filter { it.isFile && it.isPythonInstallSource() }
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
    ): List<ExtensionModule> =
        packages
            .flatMap { packageDir ->
                packageDir
                    .walkTopDown()
                    .filter { it.isFile }
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

    private fun File.isPythonInstallSource(): Boolean = extension == "py" || extension == "pyi" || name == "py.typed"

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
