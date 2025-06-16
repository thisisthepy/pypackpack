package org.thisisthepy.python.multiplatform.packpack.dependency.backend.external

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.thisisthepy.python.multiplatform.packpack.util.Downloader
import org.thisisthepy.python.multiplatform.packpack.util.DownloadSpec
import java.io.File
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

/**
 * UV package manager downloader and wrapper
 * Handles automatic download and installation of UV standalone binaries
 */
class UV {
    companion object {
        private const val UV_VERSION = "0.7.12"
        private const val UV_BASE_URL = "https://github.com/astral-sh/uv/releases/download"
        
        // Platform-specific UV binary information
        private val PLATFORM_BINARIES = mapOf(
            "linux_amd64" to "uv-x86_64-unknown-linux-gnu.tar.gz",
            "linux_arm64" to "uv-aarch64-unknown-linux-gnu.tar.gz",
            "macos_amd64" to "uv-x86_64-apple-darwin.tar.gz",
            "macos_arm64" to "uv-aarch64-apple-darwin.tar.gz",
            "windows_amd64" to "uv-x86_64-pc-windows-msvc.zip",
            "windows_arm64" to "uv-aarch64-pc-windows-msvc.zip"
        )
        
        private fun getCurrentPlatform(): String {
            val osName = System.getProperty("os.name").lowercase()
            val osArch = System.getProperty("os.arch").lowercase()
            
            val platform = when {
                osName.contains("linux") -> "linux"
                osName.contains("mac") || osName.contains("darwin") -> "macos"
                osName.contains("windows") -> "windows"
                else -> throw UnsupportedOperationException("Unsupported OS: $osName")
            }
            
            val arch = when {
                osArch.contains("amd64") || osArch.contains("x86_64") -> "amd64"
                osArch.contains("aarch64") || osArch.contains("arm64") -> "arm64"
                else -> throw UnsupportedOperationException("Unsupported architecture: $osArch")
            }
            
            return "${platform}_${arch}"
        }
        
        private fun getUvInstallDir(): File {
            val userHome = System.getProperty("user.home")
            return File(userHome, ".pypackpack/uv")
        }
        
        private fun getUvBinaryPath(): File {
            val installDir = getUvInstallDir()
            val binaryName = if (System.getProperty("os.name").lowercase().contains("windows")) {
                "uv.exe"
            } else {
                "uv"
            }
            return File(installDir, binaryName)
        }
    }
    
    /**
     * Check if UV is installed on the system (either system-wide or downloaded by PyPackPack)
     */
    suspend fun isInstalled(): Boolean {
        return isSystemInstalled() || isDownloadedInstalled()
    }
    
    /**
     * Check if UV is installed system-wide
     */
    private suspend fun isSystemInstalled(): Boolean = withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder("uv", "--version")
                .redirectErrorStream(true)
                .start()
            
            val exitCode = process.waitFor(5, TimeUnit.SECONDS)
            exitCode && process.exitValue() == 0
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Check if UV is installed in PyPackPack's local directory
     */
    private fun isDownloadedInstalled(): Boolean {
        val uvBinary = getUvBinaryPath()
        return uvBinary.exists() && uvBinary.canExecute()
    }
    
    /**
     * Get the path to UV binary (system or downloaded)
     */
    suspend fun getUvPath(): String? {
        return when {
            isSystemInstalled() -> "uv"
            isDownloadedInstalled() -> getUvBinaryPath().absolutePath
            else -> null
        }
    }
    
    /**
     * Download and install UV if not already available
     */
    suspend fun ensureInstalled(): Boolean = withContext(Dispatchers.IO) {
        if (isInstalled()) {
            return@withContext true
        }
        
        try {
            println("UV not found. Downloading UV v$UV_VERSION...")
            downloadAndInstall()
            true
        } catch (e: Exception) {
            println("Failed to download UV: ${e.message}")
            false
        }
    }
    
    /**
     * Download and install UV standalone binary
     */
    private suspend fun downloadAndInstall() = withContext(Dispatchers.IO) {
        val platform = getCurrentPlatform()
        val binaryName = PLATFORM_BINARIES[platform] 
            ?: throw UnsupportedOperationException("No UV binary available for platform: $platform")
        
        val downloadUrl = "$UV_BASE_URL/$UV_VERSION/$binaryName"
        val installDir = getUvInstallDir()
        
        // Create install directory
        installDir.mkdirs()
        
        // Download the archive
        println("Downloading from: $downloadUrl")
        val downloader = Downloader()
        val downloadSpec = DownloadSpec(downloadUrl, binaryName)
        val downloadResult = downloader.download(downloadSpec)
        
        if (!downloadResult.success) {
            throw RuntimeException("Download failed: ${downloadResult.error}")
        }
        
        val tempFile = File(downloadResult.filePath)
        try {
            // Extract the archive
            extractUvBinary(tempFile, installDir, binaryName.endsWith(".zip"))
            
            // Make binary executable on Unix systems
            val uvBinary = getUvBinaryPath()
            if (!System.getProperty("os.name").lowercase().contains("windows")) {
                uvBinary.setExecutable(true)
            }
            
            println("UV v$UV_VERSION installed successfully to: ${uvBinary.absolutePath}")
            
        } finally {
            tempFile.delete()
        }
    }
    
    /**
     * Extract UV binary from downloaded archive
     */
    private fun extractUvBinary(archiveFile: File, targetDir: File, isZip: Boolean) {
        if (isZip) {
            extractFromZip(archiveFile, targetDir)
        } else {
            extractFromTarGz(archiveFile, targetDir)
        }
    }
    
    /**
     * Extract UV binary from ZIP archive (Windows)
     */
    private fun extractFromZip(zipFile: File, targetDir: File) {
        val process = ProcessBuilder("unzip", "-j", zipFile.absolutePath, "*/uv.exe", "-d", targetDir.absolutePath)
            .redirectErrorStream(true)
            .start()
        
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            // Fallback: try with Java's built-in ZIP support
            java.util.zip.ZipInputStream(zipFile.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name.endsWith("uv.exe")) {
                        val outputFile = File(targetDir, "uv.exe")
                        Files.copy(zis, outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        break
                    }
                    entry = zis.nextEntry
                }
            }
        }
    }
    
    /**
     * Extract UV binary from TAR.GZ archive (Unix systems)
     */
    private fun extractFromTarGz(tarGzFile: File, targetDir: File) {
        val process = ProcessBuilder("tar", "-xzf", tarGzFile.absolutePath, "--strip-components=1", "-C", targetDir.absolutePath)
            .redirectErrorStream(true)
            .start()
        
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw RuntimeException("Failed to extract UV binary from archive")
        }
    }
    
    /**
     * Get UV version information
     */
    suspend fun getVersion(): String? = withContext(Dispatchers.IO) {
        val uvPath = getUvPath() ?: return@withContext null
        
        try {
            val process = ProcessBuilder(uvPath, "--version")
                .redirectErrorStream(true)
                .start()
            
            val output = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor(5, TimeUnit.SECONDS)
            
            if (exitCode && process.exitValue() == 0) {
                output
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Execute UV command with given arguments
     */
    suspend fun executeCommand(args: List<String>, workingDir: File? = null): Pair<Int, String> = withContext(Dispatchers.IO) {
        val uvPath = getUvPath() ?: throw IllegalStateException("UV is not installed")
        
        val command = listOf(uvPath) + args
        val processBuilder = ProcessBuilder(command)
            .redirectErrorStream(true)
        
        if (workingDir != null) {
            processBuilder.directory(workingDir)
        }
        
        val process = processBuilder.start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        
        Pair(exitCode, output)
    }
}