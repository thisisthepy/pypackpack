package org.thisisthepy.python.multiplatform.packpack.dependency.backend

import org.thisisthepy.python.multiplatform.packpack.config.PackPackConfig
import org.thisisthepy.python.multiplatform.packpack.dependency.backend.external.UV
import java.io.File

/** UV implementation of backend interface */
class UVInterface : BaseInterface {
    private val uv = UV()
    private val pyprojectFile = "pyproject.toml"

    /** Initialize UV backend */
    override fun initialize() {
        // UV initialization is handled by the UV class
    }

    /** Get backend tool version */
    override suspend fun getVersion(): Result<String> =
        runCatching {
            if (!isToolInstalled()) {
                installTool().getOrThrow()
            }
            val (exitCode, output) = uv.executeCommand(listOf("--version"))
            if (exitCode == 0) {
                output
            } else {
                throw Exception(output)
            }
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
    override suspend fun installTool(): Result<String> =
        runCatching {
            val success = uv.ensureInstalled()
            if (success) {
                "UV installed successfully"
            } else {
                throw Exception("Failed to install UV")
            }
        }

    /** Create virtual environment */
    override suspend fun createVirtualEnvironment(
        path: String,
        pythonVersion: String?,
        extraArgs: Map<String, String>?,
    ): Result<String> =
        runCatching {
            if (!isToolInstalled()) {
                installTool().getOrThrow()
            }

            val command = mutableListOf("venv", path)

            // Use provided version or default to 3.12
            val versionToUse = pythonVersion ?: PackPackConfig.defaultPythonVersion
            command.add("--python")
            command.add(versionToUse)

            val (exitCode, output) = uv.executeCommand(command)
            if (exitCode == 0) {
                output
            } else {
                throw Exception(output)
            }
        }

    /** Add dependencies */
    override suspend fun addDependencies(
        packageName: String?,
        dependencies: List<String>,
        extraArgs: Map<String, String>?,
    ): Result<String> =
        runCatching {
            if (!isToolInstalled()) {
                installTool().getOrThrow()
            }

            val projectRoot =
                findProjectRoot()
                    ?: throw Exception("Project root not found. Please initialize a project first.")

            val targetDir =
                if (packageName.isNullOrEmpty()) {
                    projectRoot
                } else {
                    File(projectRoot, packageName)
                }

            if (!targetDir.exists()) {
                throw Exception("Target directory not found: ${targetDir.path}")
            }

            val pyprojectFile = File(projectRoot, "pyproject.toml")

            if (!pyprojectFile.exists()) {
                throw Exception("pyproject.toml not found in ${projectRoot.path}.")
            }

            val command = mutableListOf("add")
            command.addAll(dependencies)

            // Add extra arguments
            extraArgs?.forEach { (key, value) ->
                command.add("--$key")
                if (value.isNotEmpty()) {
                    command.add(value)
                }
            }

            if (!packageName.isNullOrEmpty()) {
                // To prevent UV from creating a virtual environment, you must add the --no-sync option.
                command.add("--no-sync")
            }

            val (exitCode, output) = uv.executeCommand(command, targetDir)
            if (exitCode == 0) {
                output
            } else {
                throw Exception(output)
            }
        }

    /** Uninstall dependencies */
    override suspend fun removeDependencies(
        packageName: String?,
        dependencies: List<String>,
        extraArgs: Map<String, String>?,
    ): Result<String> =
        runCatching {
            if (!isToolInstalled()) {
                installTool().getOrThrow()
            }

            val projectRoot =
                findProjectRoot()
                    ?: throw Exception("Project root not found. Please initialize a project first.")

            val targetDir =
                if (packageName.isNullOrEmpty()) {
                    projectRoot
                } else {
                    File(projectRoot, packageName)
                }

            if (!targetDir.exists()) {
                throw Exception("Target directory not found: ${targetDir.path}")
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

            if (!packageName.isNullOrEmpty()) {
                // To prevent UV from creating a virtual environment, you must add the --no-sync option.
                command.add("--no-sync")
            }

            // Execute in targetDir
            val (exitCode, output) = uv.executeCommand(command, targetDir)
            if (exitCode == 0) {
                output
            } else {
                throw Exception(output)
            }
        }

    /** Synchronize dependencies */
    override suspend fun syncDependencies(
        venvPath: String,
        extraArgs: Map<String, String>?,
    ): Result<String> =
        runCatching {
            if (!isToolInstalled()) {
                installTool().getOrThrow()
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
            if (exitCode == 0) {
                output
            } else {
                throw Exception(output)
            }
        }

    /** Show dependency tree */
    override suspend fun showDependencyTree(
        packageName: String?,
        extraArgs: Map<String, String>?,
    ): Result<String> =
        runCatching {
            if (!isToolInstalled()) {
                installTool().getOrThrow()
            }

            val projectRoot =
                findProjectRoot()
                    ?: throw Exception("Project root not found.")

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
            if (exitCode == 0) {
                output
            } else {
                throw Exception(output)
            }
        }

    /** Lock dependencies */
    override suspend fun lockDependencies(projectRoot: String): Result<String> =
        runCatching {
            if (!isToolInstalled()) {
                installTool().getOrThrow()
            }

            val command = mutableListOf("lock")

            // Execute uv lock in project root
            val (exitCode, output) = uv.executeCommand(command, File(projectRoot))
            if (exitCode == 0) {
                output
            } else {
                throw Exception(output)
            }
        }

    /** List available Python versions */
    override suspend fun listPythonVersions(): Result<String> =
        runCatching {
            if (!isToolInstalled()) {
                installTool().getOrThrow()
            }

            val command = listOf("python", "list")

            val (exitCode, output) = uv.executeCommand(command)
            if (exitCode == 0) {
                output
            } else {
                throw Exception(output)
            }
        }

    /** Find a specific Python version */
    override suspend fun findPythonVersion(pythonVersion: String): Result<String> =
        runCatching {
            if (!isToolInstalled()) {
                installTool().getOrThrow()
            }

            val command = listOf("python", "find", pythonVersion)

            val (exitCode, output) = uv.executeCommand(command)
            if (exitCode == 0) {
                output
            } else {
                throw Exception(output)
            }
        }

    /** Install a specific Python version */
    override suspend fun installPythonVersion(pythonVersion: String): Result<String> =
        runCatching {
            if (!isToolInstalled()) {
                installTool().getOrThrow()
            }

            val command = listOf("python", "install", pythonVersion)

            val (exitCode, output) = uv.executeCommand(command)
            if (exitCode == 0) {
                output
            } else {
                throw Exception(output)
            }
        }

    /** Uninstall a specific Python version */
    override suspend fun uninstallPythonVersion(pythonVersion: String): Result<String> =
        runCatching {
            if (!isToolInstalled()) {
                installTool().getOrThrow()
            }

            val command = listOf("python", "uninstall", pythonVersion)

            val (exitCode, output) = uv.executeCommand(command)
            if (exitCode == 0) {
                output
            } else {
                throw Exception(output)
            }
        }

    /** ExecuteCommand to use UV class */
    suspend fun executeCommand(command: List<String>): Result<String> =
        runCatching {
            val (exitCode, output) = uv.executeCommand(command)
            if (exitCode == 0) {
                output
            } else {
                throw Exception(output)
            }
        }
}
