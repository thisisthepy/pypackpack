package org.thisisthepy.python.multiplatform.packpack.dependency.backend

import org.thisisthepy.python.multiplatform.packpack.dependency.backend.external.UV
import org.thisisthepy.python.multiplatform.packpack.util.CommandResult
import org.thisisthepy.python.multiplatform.packpack.util.Downloader
import org.thisisthepy.python.multiplatform.packpack.util.DownloadSpec
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import java.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * UV implementation of backend interface
 */
class UVInterface : BaseInterface {
    private val uv = UV()
    
    /**
     * Initialize UV backend
     */
    override fun initialize() {
        // UV initialization is handled by the UV class
    }
    
    /**
     * Check if UV is installed
     */
    override suspend fun isToolInstalled(): Boolean {
        return uv.isInstalled()
    }
    
    /**
     * Install UV
     */
    override suspend fun installTool(): CommandResult {
        return try {
            val success = uv.ensureInstalled()
            if (success) {
                CommandResult(true, "UV installed successfully", "")
            } else {
                CommandResult(false, "", "Failed to install UV")
            }
        } catch (e: Exception) {
            CommandResult(false, "", "Failed to install UV: ${e.message}")
        }
    }
    
    /**
     * Create virtual environment
     */
    override suspend fun createVirtualEnvironment(path: String, pythonVersion: String?, extraArgs: Map<String, String>?): CommandResult {
        if (!isToolInstalled() && !installTool().success) {
            return CommandResult(false, "", "UV is not installed")
        }
        
        val command = mutableListOf("venv", path)
        
        // Use provided version or default to 3.13
        val versionToUse = pythonVersion ?: "3.13"
        command.add("--python")
        command.add(versionToUse)
        
        val (exitCode, output) = uv.executeCommand(command)
        return if (exitCode == 0) {
            CommandResult(true, output, "")
        } else {
            CommandResult(false, "", output)
        }
    }
    
    /**
     * Install dependencies
     */
    override suspend fun installDependencies(venvPath: String, dependencies: List<String>, extraArgs: Map<String, String>?): CommandResult {
        if (!isToolInstalled() && !installTool().success) {
            return CommandResult(false, "", "UV is not installed")
        }
        
        val command = mutableListOf("pip", "install")
        command.addAll(dependencies)
        
        // Add extra arguments
        if (extraArgs != null) {
            if (extraArgs.containsKey("extra-index-url")) {
                command.add("--extra-index-url")
                command.add(extraArgs["extra-index-url"]!!)
            }
        }
        
        return executeInVenv(venvPath, command)
    }
    
    /**
     * Uninstall dependencies
     */
    override suspend fun uninstallDependencies(venvPath: String, dependencies: List<String>, extraArgs: Map<String, String>?): CommandResult {
        if (!isToolInstalled() && !installTool().success) {
            return CommandResult(false, "", "UV is not installed")
        }
        
        val command = mutableListOf("pip", "uninstall", "--yes")
        command.addAll(dependencies)
        
        return executeInVenv(venvPath, command)
    }
    
    /**
     * Synchronize dependencies
     */
    override suspend fun syncDependencies(venvPath: String, extraArgs: Map<String, String>?): CommandResult {
        if (!isToolInstalled() && !installTool().success) {
            return CommandResult(false, "", "UV is not installed")
        }
        
        val command = mutableListOf("pip", "sync")
        
        return executeInVenv(venvPath, command)
    }
    
    /**
     * Show dependency tree
     */
    override suspend fun showDependencyTree(venvPath: String, extraArgs: Map<String, String>?): CommandResult {
        if (!isToolInstalled() && !installTool().success) {
            return CommandResult(false, "", "UV is not installed")
        }
        
        val command = mutableListOf("pip", "list", "--tree")
        
        return executeInVenv(venvPath, command)
    }
    
    /**
     * List available Python versions
     */
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
    
    /**
     * Find a specific Python version
     */
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
    
    /**
     * Install a specific Python version
     */
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
    
    /**
     * Uninstall a specific Python version
     */
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
    
    /**
     * Generate or update lock file for the project
     */
    override suspend fun generateLockFile(projectPath: String, extraArgs: Map<String, String>?): CommandResult {
        if (!isToolInstalled() && !installTool().success) {
            return CommandResult(false, "", "UV is not installed")
        }
        
        val command = mutableListOf("lock")
        
        // Add extra arguments
        if (extraArgs != null) {
            if (extraArgs.containsKey("upgrade")) {
                command.add("--upgrade")
            }
            if (extraArgs.containsKey("check")) {
                command.add("--check")
            }
            if (extraArgs.containsKey("frozen")) {
                command.add("--frozen")
            }
            if (extraArgs.containsKey("script") && extraArgs["script"] != null) {
                command.add("--script")
                command.add(extraArgs["script"]!!)
            }
        }
        
        return executeCommandInDirectory(command, projectPath)
    }
    
    /**
     * Upgrade all packages in lock file to latest versions
     */
    override suspend fun upgradeLockFile(projectPath: String, extraArgs: Map<String, String>?): CommandResult {
        if (!isToolInstalled() && !installTool().success) {
            return CommandResult(false, "", "UV is not installed")
        }
        
        val command = mutableListOf("lock", "--upgrade")
        
        // Add extra arguments
        if (extraArgs != null) {
            if (extraArgs.containsKey("platform")) {
                command.add("--platform")
                command.add(extraArgs["platform"]!!)
            }
        }
        
        return executeCommandInDirectory(command, projectPath)
    }
    
    /**
     * Upgrade specific package in lock file
     */
    override suspend fun upgradePackageInLock(projectPath: String, packageName: String, version: String?, extraArgs: Map<String, String>?): CommandResult {
        if (!isToolInstalled() && !installTool().success) {
            return CommandResult(false, "", "UV is not installed")
        }
        
        val command = mutableListOf("lock", "--upgrade-package")
        
        if (version != null) {
            command.add("$packageName==$version")
        } else {
            command.add(packageName)
        }
        
        // Add extra arguments
        if (extraArgs != null) {
            if (extraArgs.containsKey("platform")) {
                command.add("--platform")
                command.add(extraArgs["platform"]!!)
            }
        }
        
        return executeCommandInDirectory(command, projectPath)
    }
    
    /**
     * Validate if lock file is up-to-date
     */
    override suspend fun validateLockFile(projectPath: String, extraArgs: Map<String, String>?): CommandResult {
        if (!isToolInstalled() && !installTool().success) {
            return CommandResult(false, "", "UV is not installed")
        }
        
        val command = mutableListOf("lock", "--check")
        
        return executeCommandInDirectory(command, projectPath)
    }
    
    /**
     * Export lock file to different format
     */
    override suspend fun exportLockFile(projectPath: String, format: String, outputPath: String?, extraArgs: Map<String, String>?): CommandResult {
        if (!isToolInstalled() && !installTool().success) {
            return CommandResult(false, "", "UV is not installed")
        }
        
        val command = mutableListOf("export")
        
        when (format.lowercase()) {
            "requirements-txt", "requirements" -> {
                command.add("--format")
                command.add("requirements-txt")
            }
            "pylock-toml", "pylock" -> {
                // Default export format for pylock.toml
            }
            else -> {
                return CommandResult(false, "", "Unsupported export format: $format. Supported formats: requirements-txt, pylock-toml")
            }
        }
        
        if (outputPath != null) {
            command.add("-o")
            command.add(outputPath)
        }
        
        // Add extra arguments
        if (extraArgs != null) {
            if (extraArgs.containsKey("no-hashes")) {
                command.add("--no-hashes")
            }
            if (extraArgs.containsKey("no-header")) {
                command.add("--no-header")
            }
            if (extraArgs.containsKey("no-emit-package")) {
                command.add("--no-emit-package")
                command.add(extraArgs["no-emit-package"]!!)
            }
        }
        
        return executeCommandInDirectory(command, projectPath)
    }
    
    /**
     * Helper method to execute command in a specific directory
     */
    private suspend fun executeCommandInDirectory(command: List<String>, directory: String): CommandResult {
        val (exitCode, output) = uv.executeCommand(command, File(directory))
        return if (exitCode == 0) {
            CommandResult(true, output, "")
        } else {
            CommandResult(false, "", output)
        }
    }

    /**
     * Synchronize dependencies from lock file
     */
    override suspend fun syncFromLockFile(projectPath: String, lockFilePath: String?, extraArgs: Map<String, String>?): CommandResult {
        if (!isToolInstalled() && !installTool().success) {
            return CommandResult(false, "", "UV is not installed")
        }
        
        val command = mutableListOf("pip", "sync")
        
        // Add lock file path if specified
        if (!lockFilePath.isNullOrEmpty()) {
            command.add(lockFilePath)
        }
        
        // Add other extra arguments (excluding platform-specific ones)
        extraArgs?.forEach { (key, value) ->
            if (key != "platform") {
                command.addAll(listOf("--$key", value))
            }
        }
        
        val (exitCode, output) = uv.executeCommand(command)
        return if (exitCode == 0) {
            CommandResult(true, output, "")
        } else {
            CommandResult(false, "", output)
        }
    }
    
    /**
     * Generate lock file export (simplified without platform-specific options)
     * @param projectPath Project path
     * @param outputPath Output path for the exported file
     * @param format Export format (default: "requirements-txt")
     * @return Result of lock file export
     */
    suspend fun exportLockFile(
        projectPath: String, 
        outputPath: String, 
        format: String = "requirements-txt"
    ): CommandResult {
        if (!isToolInstalled() && !installTool().success) {
            return CommandResult(false, "", "UV is not installed")
        }
        
        return try {
            val command = mutableListOf("export")
            
            // Add format specification
            when (format.lowercase()) {
                "requirements-txt", "requirements" -> {
                    command.addAll(listOf("--format", "requirements-txt"))
                }
                else -> {
                    return CommandResult(false, "", "Unsupported export format: $format. Supported formats: requirements-txt")
                }
            }
            
            // Add output path
            if (outputPath.isNotEmpty()) {
                command.addAll(listOf("-o", outputPath))
            }
            
            val result = executeCommandInDirectory(command, projectPath)
            if (result.success) {
                CommandResult(true, "Exported $format", result.output)
            } else {
                CommandResult(false, "", "Failed to export $format: ${result.error}")
            }
        } catch (e: Exception) {
            CommandResult(false, "", "Error exporting lock file: ${e.message}")
        }
    }
    
    /**
     * Convert platform name to UV environment marker
     */
    private fun getPlatformMarker(platform: String): String {
        return when {
            platform.startsWith("linux") -> "linux"
            platform.startsWith("windows") -> "win32"
            platform.startsWith("macos") -> "darwin"
            platform.startsWith("android") -> "linux"  // Android uses Linux kernel
            else -> platform
        }
    }
    

    
    /**
     * Sync dependencies from requirements file
     * @param venvPath Virtual environment path
     * @param requirementsPath Path to requirements file
     * @param extraArgs Extra arguments
     * @return Result of dependency synchronization
     */
    suspend fun syncFromRequirements(
        venvPath: String,
        requirementsPath: String,
        extraArgs: Map<String, String>? = null
    ): CommandResult {
        if (!isToolInstalled() && !installTool().success) {
            return CommandResult(false, "", "UV is not installed")
        }
        
        val command = mutableListOf("pip", "sync")
        
        // Add the requirements file path
        command.add(requirementsPath)
        
        // Add extra arguments (excluding platform-specific ones)
        extraArgs?.forEach { (key, value) ->
            if (key != "platform") {
                command.addAll(listOf("--$key", value))
            }
        }
        
        return executeInVenv(venvPath, command)
    }
    
    /**
     * Override executeCommand to use UV class
     */
    override suspend fun executeCommand(command: List<String>): CommandResult {
        val (exitCode, output) = uv.executeCommand(command)
        return if (exitCode == 0) {
            CommandResult(true, output, "")
        } else {
            CommandResult(false, "", output)
        }
    }
    
    /**
     * Override executeInVenv to use UV class with virtual environment
     */
    override suspend fun executeInVenv(venvPath: String, command: List<String>): CommandResult {
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
}
