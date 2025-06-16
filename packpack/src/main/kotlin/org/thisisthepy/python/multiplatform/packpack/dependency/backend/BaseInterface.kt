package org.thisisthepy.python.multiplatform.packpack.dependency.backend

import org.thisisthepy.python.multiplatform.packpack.util.CommandResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.thisisthepy.python.multiplatform.packpack.util.Downloader

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
     * Check if dependency management tool is installed
     * @return True if installed, false otherwise
     */
    suspend fun isToolInstalled(): Boolean
    
    /**
     * Install dependency management tool
     * @return Result of installation
     */
    suspend fun installTool(): CommandResult
    
    /**
     * Create virtual environment
     * @param path Path to create virtual environment
     * @param pythonVersion Python version (optional)
     * @param extraArgs Extra arguments (optional)
     * @return Result of virtual environment creation
     */
    suspend fun createVirtualEnvironment(path: String, pythonVersion: String?, extraArgs: Map<String, String>? = null): CommandResult
    
    /**
     * Install dependencies
     * @param venvPath Virtual environment path
     * @param dependencies List of dependencies to install
     * @param extraArgs Extra arguments (optional)
     * @return Result of dependency installation
     */
    suspend fun installDependencies(venvPath: String, dependencies: List<String>, extraArgs: Map<String, String>? = null): CommandResult
    
    /**
     * Uninstall dependencies
     * @param venvPath Virtual environment path
     * @param dependencies List of dependencies to uninstall
     * @param extraArgs Extra arguments (optional)
     * @return Result of dependency uninstallation
     */
    suspend fun uninstallDependencies(venvPath: String, dependencies: List<String>, extraArgs: Map<String, String>? = null): CommandResult
    
    /**
     * Synchronize dependencies
     * @param venvPath Virtual environment path
     * @param extraArgs Extra arguments (optional)
     * @return Result of dependency synchronization
     */
    suspend fun syncDependencies(venvPath: String, extraArgs: Map<String, String>? = null): CommandResult
    
    /**
     * Show dependency tree
     * @param venvPath Virtual environment path
     * @param extraArgs Extra arguments (optional)
     * @return Result containing dependency tree
     */
    suspend fun showDependencyTree(venvPath: String, extraArgs: Map<String, String>? = null): CommandResult
    
    /**
     * List available Python versions
     * @return Result containing Python versions
     */
    suspend fun listPythonVersions(): CommandResult
    
    /**
     * Find a specific Python version
     * @param pythonVersion Python version
     * @return Result of Python version search
     */
    suspend fun findPythonVersion(pythonVersion: String): CommandResult
    
    /**
     * Install a specific Python version
     * @param pythonVersion Python version
     * @return Result of Python version installation
     */
    suspend fun installPythonVersion(pythonVersion: String): CommandResult
    
    /**
     * Uninstall a specific Python version
     * @param pythonVersion Python version
     * @return Result of Python version uninstallation
     */
    suspend fun uninstallPythonVersion(pythonVersion: String): CommandResult

    /**
     * Generate or update lock file for the project
     * @param projectPath Project path
     * @param extraArgs Extra arguments (optional)
     * @return Result of lock file generation
     */
    suspend fun generateLockFile(projectPath: String, extraArgs: Map<String, String>? = null): CommandResult
    
    /**
     * Upgrade all packages in lock file to latest versions
     * @param projectPath Project path
     * @param extraArgs Extra arguments (optional)
     * @return Result of lock file upgrade
     */
    suspend fun upgradeLockFile(projectPath: String, extraArgs: Map<String, String>? = null): CommandResult
    
    /**
     * Upgrade specific package in lock file
     * @param projectPath Project path
     * @param packageName Package name to upgrade
     * @param version Specific version (optional)
     * @param extraArgs Extra arguments (optional)
     * @return Result of package upgrade
     */
    suspend fun upgradePackageInLock(projectPath: String, packageName: String, version: String? = null, extraArgs: Map<String, String>? = null): CommandResult
    
    /**
     * Validate if lock file is up-to-date
     * @param projectPath Project path
     * @param extraArgs Extra arguments (optional)
     * @return Result of lock file validation
     */
    suspend fun validateLockFile(projectPath: String, extraArgs: Map<String, String>? = null): CommandResult
    
    /**
     * Export lock file to different format
     * @param projectPath Project path
     * @param format Export format (requirements-txt, pylock-toml)
     * @param outputPath Output file path (optional)
     * @param extraArgs Extra arguments (optional)
     * @return Result of lock file export
     */
    suspend fun exportLockFile(projectPath: String, format: String, outputPath: String? = null, extraArgs: Map<String, String>? = null): CommandResult
    
    /**
     * Synchronize environment from lock file
     * @param projectPath Project path
     * @param lockFilePath Lock file path (optional, defaults to project lock file)
     * @param extraArgs Extra arguments (optional)
     * @return Result of environment synchronization
     */
    suspend fun syncFromLockFile(projectPath: String, lockFilePath: String? = null, extraArgs: Map<String, String>? = null): CommandResult

    /**
     * Helper method to execute command in a virtual environment
     * @param venvPath Virtual environment path
     * @param command Command to execute
     * @return Result of command execution
     */
    suspend fun executeInVenv(venvPath: String, command: List<String>): CommandResult {
        val activateScript = if (isWindows()) {
            "Scripts\\activate.bat"
        } else {
            "bin/activate"
        }
        
        return if (isWindows()) {
            // Windows uses a different approach
            val cmd = mutableListOf(
                "cmd", "/c", 
                "call", "$venvPath\\$activateScript", "&&"
            )
            cmd.addAll(command)
            
            executeCommand(cmd)
        } else {
            // Unix-like systems
            val cmd = mutableListOf(
                "/bin/sh", "-c",
                "source $venvPath/$activateScript && ${command.joinToString(" ")}"
            )
            
            executeCommand(cmd)
        }
    }
    
    /**
     * Helper method to execute command
     * @param command Command to execute
     * @return Result of command execution
     */
    suspend fun executeCommand(command: List<String>): CommandResult {
        return withContext(Dispatchers.IO) {
            try {
                val process = ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start()
                
                val output = process.inputStream.bufferedReader().use { it.readText() }
                val exitCode = process.waitFor()
                
                if (exitCode == 0) {
                    CommandResult(true, output, "")
                } else {
                    CommandResult(false, "", output)
                }
            } catch (e: Exception) {
                CommandResult(false, "", e.message ?: "Unknown error")
            }
        }
    }
    
    /**
     * Check if running on Windows
     * @return True if on Windows, false otherwise
     */
    fun isWindows(): Boolean {
        return System.getProperty("os.name").lowercase().contains("win")
    }

    companion object {
        /**
         * Create backend instance based on the given type
         * @param type Backend type
         * @return Backend instance
         */
        fun create(type: String): BaseInterface {
            return when (type.lowercase()) {
                "uv" -> UVInterface()
                else -> UVInterface() // Default to UV
            }
        }
    }
}
