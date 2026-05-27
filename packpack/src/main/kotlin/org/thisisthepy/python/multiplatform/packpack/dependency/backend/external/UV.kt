package org.thisisthepy.python.multiplatform.packpack.dependency.backend.external

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.thisisthepy.python.multiplatform.packpack.utils.DownloadSpec
import org.thisisthepy.python.multiplatform.packpack.utils.Downloader
import org.thisisthepy.python.multiplatform.packpack.utils.Platforms
import org.thisisthepy.python.multiplatform.packpack.utils.extractArchive
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * UV package manager downloader and wrapper
 * Handles automatic download and installation of UV standalone binaries
 */
class UV {
    companion object {
        private const val UV_VERSION = "0.10.2"
        private const val UV_BASE_URL = "https://github.com/astral-sh/uv/releases/download"

        // Platform-specific UV binary information
        private val PLATFORM_BINARIES =
            mapOf(
                "x86_64-unknown-linux-gnu" to "uv-x86_64-unknown-linux-gnu.tar.gz",
                "aarch64-unknown-linux-gnu" to "uv-aarch64-unknown-linux-gnu.tar.gz",
                "x86_64-apple-darwin" to "uv-x86_64-apple-darwin.tar.gz",
                "aarch64-apple-darwin" to "uv-aarch64-apple-darwin.tar.gz",
                "x86_64-pc-windows-msvc" to "uv-x86_64-pc-windows-msvc.zip",
                "aarch64-pc-windows-msvc" to "uv-aarch64-pc-windows-msvc.zip",
            )

        private fun getCurrentPlatformTarget(): String = Platforms.detectHostTarget()

        private fun getUVInstallDir(): File {
            val userHome = System.getProperty("user.home")
            return File(userHome, ".pypackpack/uv")
        }

        private fun getUVBinaryPath(): File {
            val installDir = getUVInstallDir()
            val binaryName =
                if (System.getProperty("os.name").lowercase().contains("windows")) {
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
    suspend fun isInstalled(): Boolean = isSystemInstalled() || isDownloadedInstalled()

    /**
     * Check if UV is installed system-wide
     */
    private suspend fun isSystemInstalled(): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val process =
                    ProcessBuilder("uv", "--version")
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
        val uvBinary = getUVBinaryPath()
        return uvBinary.exists() && uvBinary.canExecute()
    }

    /**
     * Get the path to UV binary (system or downloaded)
     */
    suspend fun getUVPath(): String? =
        when {
            isSystemInstalled() -> "uv"
            isDownloadedInstalled() -> getUVBinaryPath().absolutePath
            else -> null
        }

    /**
     * Download and install UV if not already available
     */
    suspend fun ensureInstalled(): Boolean =
        withContext(Dispatchers.IO) {
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
    private suspend fun downloadAndInstall() =
        withContext(Dispatchers.IO) {
            val platform = getCurrentPlatformTarget()
            val binaryName =
                PLATFORM_BINARIES[platform]
                    ?: throw UnsupportedOperationException("No UV binary available for platform: $platform")

            val downloadUrl = "$UV_BASE_URL/$UV_VERSION/$binaryName"
            val installDir = getUVInstallDir()

            // Create install directory
            installDir.mkdirs()

            // Download the archive
            println("Downloading from: $downloadUrl")
            val downloadSpec = DownloadSpec(downloadUrl, binaryName)
            val downloadResult =
                Downloader().use { downloader ->
                    downloader.download(downloadSpec)
                }

            if (!downloadResult.success) {
                throw RuntimeException("Download failed: ${downloadResult.error}")
            }

            val tempFile = File(downloadResult.filePath)
            try {
                // Extract the archive
                extractArchive(tempFile, installDir, stripComponents = 1)

                // Make binary executable on Unix systems
                val uvBinary = getUVBinaryPath()
                if (!System.getProperty("os.name").lowercase().contains("windows")) {
                    uvBinary.setExecutable(true)
                }

                println("UV v$UV_VERSION installed successfully to: ${uvBinary.absolutePath}")
            } finally {
                tempFile.delete()
            }
        }

    /**
     * Execute UV command with given arguments
     */
    suspend fun executeCommand(
        args: List<String>,
        workingDir: File? = null,
    ): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val uvPath = getUVPath() ?: throw IllegalStateException("UV is not installed")

            val command = listOf(uvPath) + args
            val processBuilder =
                ProcessBuilder(command)
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
