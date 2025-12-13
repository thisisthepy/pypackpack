package org.thisisthepy.python.multiplatform.packpack.dependency.backend

import org.thisisthepy.python.multiplatform.packpack.config.PackPackConfig
import org.thisisthepy.python.multiplatform.packpack.dependency.backend.external.UV
import org.thisisthepy.python.multiplatform.packpack.util.CommandResult
import java.io.File
import java.util.*

/** UV implementation of backend interface */
class UVInterface : BaseInterface {
    private val uv = UV()
    private val pyprojectFile = "pyproject.toml"

    /** Initialize UV backend */
    override fun initialize() {
        // UV initialization is handled by the UV class
    }

    /**
     * Find project root directory
     * @return Project root directory or null if not found
     */
    private fun findProjectRoot(): File? {
        var dir = File(System.getProperty("user.dir"))
        while (dir.parentFile != null) {
            if (File(dir, pyprojectFile).exists()) {
                return dir
            }
            dir = dir.parentFile
        }
        return null
    }

    /** Check if UV is installed */
    override suspend fun isToolInstalled(): Boolean = uv.isInstalled()

    /** Install UV */
    override suspend fun installTool(): CommandResult =
        try {
            val success = uv.ensureInstalled()
            if (success) {
                CommandResult(true, "UV installed successfully", "")
            } else {
                CommandResult(false, "", "Failed to install UV")
            }
        } catch (e: Exception) {
            CommandResult(false, "", "Failed to install UV: ${e.message}")
        }

    /** Create virtual environment */
    override suspend fun createVirtualEnvironment(
        path: String,
        pythonVersion: String?,
        extraArgs: Map<String, String>?,
    ): CommandResult {
        if (!isToolInstalled() && !installTool().success) {
            return CommandResult(false, "", "UV is not installed")
        }

        val command = mutableListOf("venv", path)

        // Use provided version or default to 3.12
        val versionToUse = pythonVersion ?: PackPackConfig.defaultPythonVersion
        command.add("--python")
        command.add(versionToUse)

        val (exitCode, output) = uv.executeCommand(command)
        return if (exitCode == 0) {
            CommandResult(true, output, "")
        } else {
            CommandResult(false, "", output)
        }
    }

    /** Add dependencies */
    override suspend fun addDependencies(
        venvPath: String,
        dependencies: List<String>,
        extraArgs: Map<String, String>?,
    ): CommandResult {
        if (!isToolInstalled() && !installTool().success) {
            return CommandResult(false, "", "UV is not installed")
        }

        val venvDir = File(venvPath)
        val projectRoot = venvDir.parentFile
        val pyprojectFile = File(projectRoot, "pyproject.toml")

        if (pyprojectFile.exists()) {
            // Use 'uv add' for project management
            val command = mutableListOf("add")
            command.addAll(dependencies)

            // Add extra arguments
            extraArgs?.forEach { (key, value) ->
                command.add("--$key")
                if (value.isNotEmpty()) {
                    command.add(value)
                }
            }

            // Execute in project root
            val (exitCode, output) = uv.executeCommand(command, projectRoot)
            return if (exitCode == 0) {
                CommandResult(true, output, "")
            } else {
                CommandResult(false, "", output)
            }
        } else {
            return CommandResult(false, "", "pyproject.toml not found in ${projectRoot.path}.")
        }
    }

    /** Uninstall dependencies */
    override suspend fun removeDependencies(
        venvPath: String,
        dependencies: List<String>,
        extraArgs: Map<String, String>?,
    ): CommandResult {
        if (!isToolInstalled() && !installTool().success) {
            return CommandResult(false, "", "UV is not installed")
        }

        val projectRoot =
            findProjectRoot()
                ?: run {
                    return CommandResult(false, "", "Project root not found. Please initialize a project first.")
                }

        val command = mutableListOf("remove")
        command.addAll(dependencies)

        // Add extra arguments
        extraArgs?.forEach { (key, value) ->
            command.add("--$key")
            if (value.isNotEmpty()) {
                command.add(value)
            }
        }

        // Execute in project root
        val (exitCode, output) = uv.executeCommand(command, projectRoot)
        return if (exitCode == 0) {
            CommandResult(true, output, "")
        } else {
            CommandResult(false, "", output)
        }
    }

    /** Synchronize dependencies */
    override suspend fun syncDependencies(
        venvPath: String,
        extraArgs: Map<String, String>?,
    ): CommandResult {
        if (!isToolInstalled() && !installTool().success) {
            return CommandResult(false, "", "UV is not installed")
        }

        val venvDir = File(venvPath)
        // Assuming venv is inside project root or we can derive project root
        // For DevEnv: projectRoot/.venv -> projectRoot
        // For CrossEnv: projectRoot/package/build/crossenv/platform -> package
        // We need to find the directory containing pyproject.toml

        var projectRoot = venvDir.parentFile
        while (projectRoot != null && !File(projectRoot, "pyproject.toml").exists()) {
            projectRoot = projectRoot.parentFile
        }

        if (projectRoot == null) {
            // Fallback to venv parent if pyproject.toml not found (might be error case)
            projectRoot = venvDir.parentFile
        }

        val command = mutableListOf("sync")

        // Add extra arguments
        extraArgs?.forEach { (key, value) ->
            command.add("--$key")
            if (value.isNotEmpty()) {
                command.add(value)
            }
        }

        // Execute uv sync in project root
        val (exitCode, output) = uv.executeCommand(command, projectRoot)
        return if (exitCode == 0) {
            CommandResult(true, output, "")
        } else {
            CommandResult(false, "", output)
        }
    }

    /** Show dependency tree */
    override suspend fun showDependencyTree(
        packageName: String?,
        extraArgs: Map<String, String>?,
    ): CommandResult {
        if (!isToolInstalled() && !installTool().success) {
            return CommandResult(false, "", "UV is not installed")
        }

        val projectRoot =
            findProjectRoot()
                ?: return CommandResult(false, "", "Project root not found.")

        val command = mutableListOf("tree")

        // Add extra arguments
        extraArgs?.forEach { (key, value) ->
            command.add("--$key")
            if (value.isNotEmpty()) {
                command.add(value)
            }
        }

        // Execute in packageName directory if provided, otherwise in project root
        val executionDir =
            if (packageName != null) {
                val packageDir = File(packageName)
                packageDir
            } else {
                projectRoot
            }

        val (exitCode, output) = uv.executeCommand(command, executionDir)
        return if (exitCode == 0) {
            CommandResult(true, output, "")
        } else {
            CommandResult(false, "", output)
        }
    }

    /** Lock dependencies */
    override suspend fun lockDependencies(projectRoot: String): CommandResult {
        if (!isToolInstalled() && !installTool().success) {
            return CommandResult(false, "", "UV is not installed")
        }

        val command = mutableListOf("lock")

        // Execute uv lock in project root
        val (exitCode, output) = uv.executeCommand(command, File(projectRoot))
        return if (exitCode == 0) {
            CommandResult(true, output, "")
        } else {
            CommandResult(false, "", output)
        }
    }

    /** List available Python versions */
    override suspend fun listPythonVersions(): CommandResult {
        if (!isToolInstalled() && !installTool().success) {
            return CommandResult(false, "", "UV is not installed")
        }

        val command = listOf("python", "list")

        val (exitCode, output) = uv.executeCommand(command)
        return if (exitCode == 0) {
            CommandResult(true, output, "")
        } else {
            CommandResult(false, "", output)
        }
    }

    /** Find a specific Python version */
    override suspend fun findPythonVersion(pythonVersion: String): CommandResult {
        if (!isToolInstalled() && !installTool().success) {
            return CommandResult(false, "", "UV is not installed")
        }

        val command = listOf("python", "find", pythonVersion)

        val (exitCode, output) = uv.executeCommand(command)
        return if (exitCode == 0) {
            CommandResult(true, output, "")
        } else {
            CommandResult(false, "", output)
        }
    }

    /** Install a specific Python version */
    override suspend fun installPythonVersion(pythonVersion: String): CommandResult {
        if (!isToolInstalled() && !installTool().success) {
            return CommandResult(false, "", "UV is not installed")
        }

        val command = listOf("python", "install", pythonVersion)

        val (exitCode, output) = uv.executeCommand(command)
        return if (exitCode == 0) {
            CommandResult(true, output, "")
        } else {
            CommandResult(false, "", output)
        }
    }

    /** Uninstall a specific Python version */
    override suspend fun uninstallPythonVersion(pythonVersion: String): CommandResult {
        if (!isToolInstalled() && !installTool().success) {
            return CommandResult(false, "", "UV is not installed")
        }

        val command = listOf("python", "uninstall", pythonVersion)

        val (exitCode, output) = uv.executeCommand(command)
        return if (exitCode == 0) {
            CommandResult(true, output, "")
        } else {
            CommandResult(false, "", output)
        }
    }

    /** Convert platform name to UV environment marker */
    private fun getPlatformMarker(platform: String): String =
        when {
            platform.startsWith("linux") -> "linux"

            platform.startsWith("windows") -> "win32"

            platform.startsWith("macos") -> "darwin"

            platform.startsWith("android") -> "linux"

            // Android uses Linux kernel
            else -> platform
        }

    /** Override executeCommand to use UV class */
    override suspend fun executeCommand(command: List<String>): CommandResult {
        val (exitCode, output) = uv.executeCommand(command)
        return if (exitCode == 0) {
            CommandResult(true, output, "")
        } else {
            CommandResult(false, "", output)
        }
    }

    /** Override executeInVenv to use UV class with virtual environment */
    override suspend fun executeInVenv(
        venvPath: String,
        command: List<String>,
    ): CommandResult {
        // For UV, we can use the virtual environment directly by setting the working directory
        // UV will automatically detect and use the virtual environment in the project directory
        val venvDir = File(venvPath)
        val (exitCode, output) = uv.executeCommand(command, venvDir)
        return if (exitCode == 0) {
            CommandResult(true, output, "")
        } else {
            CommandResult(false, "", output)
        }
    }

    /** Helper method to execute command in a specific directory */
    private suspend fun executeCommandInDirectory(
        command: List<String>,
        directory: String,
    ): CommandResult {
        val (exitCode, output) = uv.executeCommand(command, File(directory))
        return if (exitCode == 0) {
            CommandResult(true, output, "")
        } else {
            CommandResult(false, "", output)
        }
    }
}
