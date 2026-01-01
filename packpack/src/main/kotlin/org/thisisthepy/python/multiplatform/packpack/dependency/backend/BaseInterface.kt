package org.thisisthepy.python.multiplatform.packpack.dependency.backend

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.thisisthepy.python.multiplatform.packpack.util.Downloader

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
interface BaseInterface {
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
    ): Result<String>

    /**
     * Initialize a new project
     * @param projectName Project name (optional)
     * @param pythonVersion Python version (optional)
     */
    suspend fun initProject(
        path: String?,
        extraArgs: Map<String, String>? = null,
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
    suspend fun listPythonVersions(): Result<String>

    /**
     * Find a specific Python version
     * @param pythonVersion Python version
     * @return Result of Python version search
     */
    suspend fun findPythonVersion(pythonVersion: String): Result<String>

    /**
     * Install a specific Python version
     * @param pythonVersion Python version
     * @return Result of Python version installation
     */
    suspend fun installPythonVersion(pythonVersion: String): Result<String>

    /**
     * Uninstall a specific Python version
     * @param pythonVersion Python version
     * @return Result of Python version uninstallation
     */
    suspend fun uninstallPythonVersion(pythonVersion: String): Result<String>

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
        fun create(type: BackendType): BaseInterface =
            when (type) {
                BackendType.UV -> UVInterface()
            }
    }
}
