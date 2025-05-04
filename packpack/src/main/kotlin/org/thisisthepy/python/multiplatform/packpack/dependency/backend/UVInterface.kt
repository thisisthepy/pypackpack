package org.thisisthepy.python.multiplatform.packpack.dependency.backend

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
    private val uvCommand = if (System.getProperty("os.name").lowercase().contains("win")) "uv.exe" else "uv"
    private var uvPath: String? = null
    
    /**
     * Initialize UV backend
     */
    override fun initialize() {
        // Find UV in PATH
        val path = System.getenv("PATH")?.split(File.pathSeparator) ?: emptyList()
        
        for (dir in path) {
            val uvFile = File(dir, uvCommand)
            if (uvFile.exists() && uvFile.canExecute()) {
                uvPath = uvFile.absolutePath
                break
            }
        }
    }
    
    /**
     * Check if UV is installed
     */
    override suspend fun isToolInstalled(): Boolean {
        if (uvPath != null) {
            return true
        }
        
        // Try to find UV again
        initialize()
        
        if (uvPath != null) {
            return true
        }
        
        // Check if UV exists in user's home directory
        val userHome = System.getProperty("user.home")
        val uvInHome = if (isWindows()) {
            File("$userHome\\.uv\\bin\\$uvCommand")
        } else {
            File("$userHome/.uv/bin/$uvCommand")
        }
        
        if (uvInHome.exists() && uvInHome.canExecute()) {
            uvPath = uvInHome.absolutePath
            return true
        }
        
        return false
    }
    
    /**
     * Install UV
     */
    override suspend fun installTool(): CommandResult {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        
        val downloadSpec = when {
            os.contains("win") -> {
                if (arch.contains("amd64") || arch.contains("x86_64")) {
                    DownloadSpec("https://github.com/astral-sh/uv/releases/latest/download/uv-x86_64-pc-windows-msvc.zip", "uv.zip")
                } else {
                    return CommandResult(false, "", "Unsupported architecture: $arch")
                }
            }
            os.contains("mac") -> {
                if (arch.contains("aarch64") || arch.contains("arm")) {
                    DownloadSpec("https://github.com/astral-sh/uv/releases/latest/download/uv-aarch64-apple-darwin.tar.gz", "uv.tar.gz")
                } else {
                    DownloadSpec("https://github.com/astral-sh/uv/releases/latest/download/uv-x86_64-apple-darwin.tar.gz", "uv.tar.gz")
                }
            }
            os.contains("linux") -> {
                if (arch.contains("amd64") || arch.contains("x86_64")) {
                    DownloadSpec("https://github.com/astral-sh/uv/releases/latest/download/uv-x86_64-unknown-linux-gnu.tar.gz", "uv.tar.gz")
                } else if (arch.contains("aarch64") || arch.contains("arm64")) {
                    DownloadSpec("https://github.com/astral-sh/uv/releases/latest/download/uv-aarch64-unknown-linux-gnu.tar.gz", "uv.tar.gz")
                } else {
                    return CommandResult(false, "", "Unsupported architecture: $arch")
                }
            }
            else -> {
                return CommandResult(false, "", "Unsupported operating system: $os")
            }
        }
        
        // Download UV
        val downloader = Downloader()
        val downloadResult = downloader.download(downloadSpec)
        
        if (!downloadResult.success) {
            return CommandResult(false, "", "Failed to download UV: ${downloadResult.error}")
        }
        
        // Extract UV
        val userHome = System.getProperty("user.home")
        val uvDir = if (isWindows()) {
            File("$userHome\\.uv\\bin")
        } else {
            File("$userHome/.uv/bin")
        }
        
        if (!uvDir.exists() && !uvDir.mkdirs()) {
            return CommandResult(false, "", "Failed to create UV directory: ${uvDir.absolutePath}")
        }
        
        val uvExecutable = File(uvDir, uvCommand)
        
        return withContext(Dispatchers.IO) {
            try {
                when {
                    downloadSpec.fileName.endsWith(".zip") -> {
                        // Extract zip file
                        val zipFile = java.util.zip.ZipFile(downloadResult.filePath)
                        val entries = zipFile.entries()
                        
                        while (entries.hasMoreElements()) {
                            val entry = entries.nextElement()
                            if (!entry.isDirectory && entry.name.contains(uvCommand)) {
                                val input = zipFile.getInputStream(entry)
                                Files.copy(input, uvExecutable.toPath())
                                input.close()
                                break
                            }
                        }
                        
                        zipFile.close()
                    }
                    downloadSpec.fileName.endsWith(".tar.gz") -> {
                        // Extract tar.gz file
                        val command = if (isWindows()) {
                            listOf("tar", "-xzf", downloadResult.filePath, "-C", uvDir.absolutePath)
                        } else {
                            listOf("tar", "-xzf", downloadResult.filePath, "-C", uvDir.absolutePath, "--strip-components=1")
                        }
                        
                        val extractResult = executeCommand(command)
                        if (!extractResult.success) {
                            return@withContext CommandResult(false, "", "Failed to extract UV: ${extractResult.error}")
                        }
                    }
                }
                
                // Make executable on Unix-like systems
                if (!isWindows()) {
                    val permissions = HashSet<PosixFilePermission>()
                    permissions.add(PosixFilePermission.OWNER_READ)
                    permissions.add(PosixFilePermission.OWNER_WRITE)
                    permissions.add(PosixFilePermission.OWNER_EXECUTE)
                    permissions.add(PosixFilePermission.GROUP_READ)
                    permissions.add(PosixFilePermission.GROUP_EXECUTE)
                    permissions.add(PosixFilePermission.OTHERS_READ)
                    permissions.add(PosixFilePermission.OTHERS_EXECUTE)
                    
                    Files.setPosixFilePermissions(uvExecutable.toPath(), permissions)
                }
                
                uvPath = uvExecutable.absolutePath
                
                // Clean up downloaded file
                File(downloadResult.filePath).delete()
                
                CommandResult(true, "UV installed successfully", "")
            } catch (e: Exception) {
                CommandResult(false, "", "Failed to install UV: ${e.message}")
            }
        }
    }
    
    /**
     * Create virtual environment
     */
    override suspend fun createVirtualEnvironment(path: String, pythonVersion: String?, extraArgs: Map<String, String>?): CommandResult {
        if (!isToolInstalled() && !installTool().success) {
            return CommandResult(false, "", "UV is not installed")
        }
        
        val command = mutableListOf(uvPath!!, "venv", path)
        
        if (pythonVersion != null) {
            command.add("--python")
            command.add(pythonVersion)
        }
        
        // Add platform if specified
        if (extraArgs != null && extraArgs.containsKey("platform")) {
            command.add("--platform")
            command.add(extraArgs["platform"]!!)
        }
        
        return executeCommand(command)
    }
    
    /**
     * Install dependencies
     */
    override suspend fun installDependencies(venvPath: String, dependencies: List<String>, extraArgs: Map<String, String>?): CommandResult {
        if (!isToolInstalled() && !installTool().success) {
            return CommandResult(false, "", "UV is not installed")
        }
        
        val command = mutableListOf(uvPath!!, "pip", "install")
        command.addAll(dependencies)
        
        // Add extra arguments
        if (extraArgs != null) {
            if (extraArgs.containsKey("extra-index-url")) {
                command.add("--extra-index-url")
                command.add(extraArgs["extra-index-url"]!!)
            }
            
            if (extraArgs.containsKey("platform") && !command.contains("--platform")) {
                command.add("--platform")
                command.add(extraArgs["platform"]!!)
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
        
        val command = mutableListOf(uvPath!!, "pip", "uninstall", "--yes")
        command.addAll(dependencies)
        
        // Add platform if specified
        if (extraArgs != null && extraArgs.containsKey("platform") && !command.contains("--platform")) {
            command.add("--platform")
            command.add(extraArgs["platform"]!!)
        }
        
        return executeInVenv(venvPath, command)
    }
    
    /**
     * Synchronize dependencies
     */
    override suspend fun syncDependencies(venvPath: String, extraArgs: Map<String, String>?): CommandResult {
        if (!isToolInstalled() && !installTool().success) {
            return CommandResult(false, "", "UV is not installed")
        }
        
        val command = mutableListOf(uvPath!!, "pip", "sync")
        
        // Add platform if specified
        if (extraArgs != null && extraArgs.containsKey("platform") && !command.contains("--platform")) {
            command.add("--platform")
            command.add(extraArgs["platform"]!!)
        }
        
        return executeInVenv(venvPath, command)
    }
    
    /**
     * Show dependency tree
     */
    override suspend fun showDependencyTree(venvPath: String, extraArgs: Map<String, String>?): CommandResult {
        if (!isToolInstalled() && !installTool().success) {
            return CommandResult(false, "", "UV is not installed")
        }
        
        val command = mutableListOf(uvPath!!, "pip", "list", "--tree")
        
        // Add platform if specified
        if (extraArgs != null && extraArgs.containsKey("platform") && !command.contains("--platform")) {
            command.add("--platform")
            command.add(extraArgs["platform"]!!)
        }
        
        return executeInVenv(venvPath, command)
    }
    
    /**
     * List available Python versions
     */
    override suspend fun listPythonVersions(): CommandResult {
        if (!isToolInstalled() && !installTool().success) {
            return CommandResult(false, "", "UV is not installed")
        }
        
        val command = listOf(uvPath!!, "python", "list")
        
        return executeCommand(command)
    }
    
    /**
     * Find a specific Python version
     */
    override suspend fun findPythonVersion(pythonVersion: String): CommandResult {
        if (!isToolInstalled() && !installTool().success) {
            return CommandResult(false, "", "UV is not installed")
        }
        
        val command = listOf(uvPath!!, "python", "find", pythonVersion)
        
        return executeCommand(command)
    }
    
    /**
     * Install a specific Python version
     */
    override suspend fun installPythonVersion(pythonVersion: String): CommandResult {
        if (!isToolInstalled() && !installTool().success) {
            return CommandResult(false, "", "UV is not installed")
        }
        
        val command = listOf(uvPath!!, "python", "install", pythonVersion)
        
        return executeCommand(command)
    }
    
    /**
     * Uninstall a specific Python version
     */
    override suspend fun uninstallPythonVersion(pythonVersion: String): CommandResult {
        if (!isToolInstalled() && !installTool().success) {
            return CommandResult(false, "", "UV is not installed")
        }
        
        val command = listOf(uvPath!!, "python", "uninstall", pythonVersion)
        
        return executeCommand(command)
    }
}
