package org.thisisthepy.python.multiplatform.packpack.dependency.backend

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Backend type enum
 */
enum class BackendType {
    UV,
}

/**
 * Base interface for dependency management backend
 * Factory pattern for creating backend instances
 */
interface BackendInterface {
    /**
     * Initialize backend
     */
    fun initialize()

    /**
     * Get backend tool version
     * @return Result containing version information
     */
    suspend fun getVersion(): Result<String>

    /**
     * Check if dependency management tool is installed
     * @return True if installed, false otherwise
     */
    suspend fun isToolInstalled(): Boolean

    /**
     * Install dependency management tool
     * @return Result of installation
     */
    suspend fun installTool(): Result<String>

    /**
     * Create virtual environment
     * @param path Path to create virtual environment
     * @param pythonVersion Python version (optional)
     * @param extraArgs Extra arguments (optional)
     * @return Result of virtual environment creation
     */
    suspend fun createVirtualEnvironment(
        path: String,
        pythonVersion: String?,
        extraArgs: Map<String, String>? = null,
        workingDir: File? = null,
    ): Result<String>

    /**
     * Initialize a new project
     * @param path Project path (optional)
     * @param targets List of target platforms (optional)
     * @param extraArgs Extra arguments (optional)
     */
    suspend fun initProject(
        path: String?,
        extraArgs: Map<String, String>? = null,
        workingDir: File? = null,
    ): Result<String>

    /**
     * Add dependencies
     * @param dependencies List of dependencies to add
     * @param extraArgs Extra arguments (optional)
     * @return Result of dependency addition
     */
    suspend fun addDependencies(
        packageName: String?,
        dependencies: List<String>,
        extraArgs: Map<String, String>? = null,
        workingDir: File? = null,
    ): Result<String>

    /**
     * Remove dependencies
     * @param venvPath Virtual environment path
     * @param dependencies List of dependencies to remove
     * @param extraArgs Extra arguments (optional)
     * @return Result of dependency removal
     */
    suspend fun removeDependencies(
        packageName: String?,
        dependencies: List<String>,
        extraArgs: Map<String, String>? = null,
        workingDir: File? = null,
    ): Result<String>

    /**
     * Synchronize dependencies
     * @param venvPath Virtual environment path
     * @param extraArgs Extra arguments (optional)
     * @return Result of dependency synchronization
     */
    suspend fun syncDependencies(
        venvPath: String,
        extraArgs: Map<String, String>? = null,
        workingDir: File? = null,
    ): Result<String>

    /**
     * Install a project's dependencies for a specific target platform into a plain directory
     * (not a runnable venv -- a cross target's wheels generally cannot execute on the host, so
     * this only unpacks wheels rather than creating an activatable environment).
     *
     * @param targetDir Directory to install into (created by the backend if it does not exist)
     * @param pythonPlatform `--python-platform` value (a [org.thisisthepy.python.multiplatform.packpack.utils.Platforms] canonical target triple or alias)
     * @param extraArgs Extra arguments (optional)
     * @param workingDir Directory containing the `pyproject.toml` whose dependencies are installed
     * @param requirements When non-null, install exactly these requirement specifiers (PEP 508, markers and extras allowed)
     *   instead of reading `pyproject.toml`; `workingDir` then needs no `pyproject.toml`. An empty list is a successful no-op.
     * @return Result of the install
     */
    suspend fun installDependenciesToTarget(
        targetDir: String,
        pythonPlatform: String,
        extraArgs: Map<String, String>? = null,
        workingDir: File? = null,
        requirements: List<String>? = null,
    ): Result<String>

    /**
     * Show dependency tree
     * @param venvPath Virtual environment path
     * @param extraArgs Extra arguments (optional)
     * @return Result containing dependency tree
     */
    suspend fun showDependencyTree(
        packageName: String?,
        extraArgs: Map<String, String>? = null,
        workingDir: File? = null,
    ): Result<String>

    /**
     * Lock dependencies (generate lock file)
     * @param projectRoot Project root directory
     * @return Result of lock operation
     */
    suspend fun lockDependencies(projectRoot: String): Result<String>

    /**
     * List available Python versions
     * @return Result containing Python versions
     */
    fun listPython(): Result<String>

    /**
     * Find a specific Python version
     * @param pythonVersion Python version
     * @return Result of Python version search
     */
    fun findPython(pythonVersion: String): Result<String>

    /**
     * Install a specific Python version
     * @param pythonVersion Python version
     * @param targetPlatform Target platform (alias or canonical triple); `null` means the host
     * @param installDir Directory to install the interpreter tree into. When given, the tree replaces
     * whatever is there and nothing else is written: no project directory (so `user.dir` is never
     * consulted) and no registry entry. When `null`, the project-relative placement applies
     * (`docs/SPEC.md`, "Installing a ppp Python distribution").
     * @return Result of Python version installation
     */
    suspend fun installPython(
        pythonVersion: String,
        targetPlatform: String?,
        installDir: File? = null,
    ): Result<String>

    /**
     * Uninstall a specific Python version
     * @param pythonVersion Python version
     * @return Result of Python version uninstallation
     */
    fun uninstallPython(pythonVersion: String): Result<String>

    /**
     * Helper method to execute command
     * @param command Command to execute
     * @return Result of command execution
     */
    private suspend fun executeCommand(command: List<String>): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val process =
                    ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .start()

                val output = process.inputStream.bufferedReader().use { it.readText() }
                val exitCode = process.waitFor()

                if (exitCode == 0) {
                    output
                } else {
                    throw Exception(output)
                }
            }
        }

    companion object {
        /**
         * Create backend instance based on the given type
         * @param type Backend type
         * @return Backend instance
         */
        fun create(type: BackendType): BackendInterface =
            when (type) {
                BackendType.UV -> UVBackend()
            }
    }
}
