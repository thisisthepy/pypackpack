package org.thisisthepy.python.multiplatform.packpack.compile.backend.external

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.thisisthepy.python.multiplatform.packpack.dependency.backend.UVInterface
import org.thisisthepy.python.multiplatform.packpack.utils.toml.TomlEditor
import org.thisisthepy.python.multiplatform.packpack.utils.toml.TomlValue
import java.io.File

/**
 * Meson build system wrapper (adapter pattern for Clang, MSVC, NDK, XCode)
 */
class Meson(
    private val uv: UVInterface = UVInterface(),
) {
    suspend fun installMeson(): Result<String> {
        uv.executeCommand(listOf("tool", "install", "meson"))
        uv.executeCommand(listOf("tool", "install", "ninja"))
        return Result.success("Meson and Ninja installed successfully")
    } // Now meson installed default uv tool's path(~/.local/share/uv/tools)

    fun isMesonInstalled(): Boolean {
        val isSystemInstalled = true
        val isDownloadedInstalled = true
        return isSystemInstalled || isDownloadedInstalled
    } // Not Implemented

    suspend fun setup(
        buildDir: String,
        options: List<String>?,
        workingDir: File? = null,
        overwrite: Boolean = false,
    ): Result<String> =
        runCatching {
            val projectDir = workingDir ?: File(System.getProperty("user.dir"))
            makeMesonBuild(projectDir.absolutePath, overwrite).getOrThrow()
            executeCommand(listOf("setup", buildDir) + options.orEmpty(), projectDir).getOrThrow()
        }

    suspend fun compile(
        buildDir: String,
        options: List<String>?,
        workingDir: File? = null,
    ): Result<String> =
        runCatching {
            executeCommand(listOf("compile", "-C", buildDir) + options.orEmpty(), workingDir).getOrThrow()
        }

    suspend fun install(
        buildDir: String,
        options: List<String>?,
        workingDir: File? = null,
    ): Result<String> =
        runCatching {
            executeCommand(listOf("install", "-C", buildDir, "--destdir=$workingDir/dist") + options.orEmpty(), workingDir).getOrThrow()
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
            if (extensionModules.isNotEmpty()) {
                appendLine("  'c',")
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
                    .filter { it.isFile && it.extension == "c" }
                    .map { source ->
                        ExtensionModule(
                            name = source.nameWithoutExtension,
                            path = project.relativePath(source),
                            subdir = packageDir.installSubdir(source.parentFile),
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

    private fun String.toMesonString(): String = "'${replace("\\", "\\\\").replace("'", "\\'")}'"

    private data class InstallSourceGroup(
        val subdir: String,
        val paths: List<String>,
    )

    private data class ExtensionModule(
        val name: String,
        val path: String,
        val subdir: String,
    )

    suspend fun executeCommand(
        command: List<String>,
        workingDir: File? = null,
    ): Result<String> =
        runCatching {
            withContext(Dispatchers.IO) {
                val fullCommand = listOf("meson") + command
                val processBuilder = ProcessBuilder(fullCommand).redirectErrorStream(true)

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
