package org.thisisthepy.python.multiplatform.packpack.dependency.backend

import org.thisisthepy.python.multiplatform.packpack.utils.DownloadSpec
import org.thisisthepy.python.multiplatform.packpack.utils.Downloader
import org.thisisthepy.python.multiplatform.packpack.utils.Platforms
import org.thisisthepy.python.multiplatform.packpack.utils.extractArchive
import org.thisisthepy.python.multiplatform.packpack.utils.findProjectRoot
import java.io.File

abstract class DefaultInterface : BaseInterface {
    protected open fun pythonInstallRoot(): File = File(System.getProperty("user.home"), ".pypackpack/python")

    private fun requirePythonVersionName(pythonVersion: String) {
        require(pythonVersion.isNotBlank()) { "Python version cannot be blank" }
        require(!pythonVersion.contains('/') && !pythonVersion.contains('\\')) {
            "Python version cannot contain path separators"
        }
    }

    override suspend fun installPython(
        pythonVersion: String,
        targetPlatform: String?,
    ): Result<String> {
        if (pythonVersion != "3.13") {
            return Result.failure(IllegalArgumentException("Only Python 3.13 is supported due to python-mutliplatform limitations"))
        } // TODO: Remove when python-multiplatform supports more versions

        val targetPlatform =
            Platforms.normalizeTarget(targetPlatform ?: Platforms.detectHostTarget())
                ?: return Result.failure(IllegalArgumentException("Unsupported target platform '$targetPlatform'"))

        val (downloadFileName, fileName) =
            when (targetPlatform) {
                "windows", "x86_64-pc-windows-msvc" -> {
                    "cpython-3.13.0+20241008-x86_64-pc-windows-msvc-shared-pgo-full.tar.zst" to "x86_64-pc-windows-msvc"
                }

                "linux", "x86_64-unknown-linux-gnu" -> {
                    "cpython-3.13.0+20241008-x86_64_v4-unknown-linux-gnu-lto-full.tar.zst" to "x86_64-unknown-linux-gnu"
                }

                "macos", "aarch64-apple-darwin" -> {
                    "cpython-3.13.0+20241008-aarch64-apple-darwin-pgo+lto-full.tar.zst" to "aarch64-apple-darwin"
                }

                "x86_64-apple-darwin" -> {
                    "cpython-3.13.0+20241008-x86_64-apple-darwin-pgo+lto-full.tar.zst" to "x86_64-apple-darwin"
                }

                "aarch64-linux-android" -> {
                    "aarch64-linux-android.tar.xz" to "aarch64-linux-android"
                }

                "x86_64-linux-android" -> {
                    "x86_64-linux-android.tar.xz" to "x86_64-linux-android"
                }

                "arm64-apple-ios" -> {
                    "arm64-iphoneos.zip" to "arm64-apple-ios"
                }

                "arm64-apple-ios-simulator" -> {
                    "arm64-iphonesimulator.zip" to "arm64-apple-ios-simulator"
                }

                "x86_64-apple-ios-simulator" -> {
                    "x86_64-iphonesimulator.zip" to "x86_64-apple-ios-simulator"
                }

                else -> {
                    return Result.failure(IllegalArgumentException("Unsupported target platform '$targetPlatform'"))
                }
            }
        val baseUrl = "https://github.com/thisisthepy/python-multiplatform/raw/release/binary"
        val url = "$baseUrl/$downloadFileName"

        val hostPlatform = Platforms.detectHostTarget()

        val installDir: File =
            if (targetPlatform == hostPlatform) {
                File(findProjectRoot(), ".venv")
            } else {
                val dirName =
                    when (targetPlatform) {
                        "windows", "x86_64-pc-windows-msvc" -> "windows_amd64"
                        "linux", "x86_64-unknown-linux-gnu" -> "linux_x86_64"
                        "macos", "aarch64-apple-darwin" -> "macos_arm64"
                        "x86_64-apple-darwin" -> "macos_x86_64"
                        "aarch64-linux-android" -> "android_arm64"
                        "x86_64-linux-android" -> "android_x86_64"
                        "arm64-apple-ios" -> "ios_arm64"
                        "arm64-apple-ios-simulator" -> "ios_simulator_arm64"
                        "x86_64-apple-ios-simulator" -> "ios_simulator_x86_64"
                        else -> return Result.failure(IllegalArgumentException("Unsupported target platform '$targetPlatform'"))
                    }
                File(findProjectRoot(), dirName)
            }

        val downloadSpec = DownloadSpec(url, downloadFileName, installDir)
        val result = Downloader().use { downloader -> downloader.download(downloadSpec) }
        println("Downloaded path: ${result.filePath}")

        extractArchive(File(result.filePath), installDir)

        if (!result.success) throw RuntimeException("Download failed: ${result.error}")

        // TODO: unzip and install to correct location
        return Result.success("Downloaded ${result.filePath} for Python $pythonVersion")
    }

    override fun uninstallPython(pythonVersion: String): Result<String> =
        runCatching {
            requirePythonVersionName(pythonVersion)

            val installDir = File(pythonInstallRoot(), pythonVersion)
            require(installDir.exists()) { "Python $pythonVersion is not installed" }
            require(installDir.isDirectory) { "Python $pythonVersion install path is not a directory: ${installDir.absolutePath}" }

            if (!installDir.deleteRecursively()) {
                throw IllegalStateException("Failed to remove ${installDir.absolutePath}")
            }

            "Removed Python $pythonVersion from ${installDir.absolutePath}"
        }

    override fun findPython(pythonVersion: String): Result<String> =
        runCatching {
            requirePythonVersionName(pythonVersion)

            val installDir = File(pythonInstallRoot(), pythonVersion)
            require(installDir.exists() && installDir.isDirectory) { "Python $pythonVersion is not installed" }

            installDir.absolutePath
        }

    override fun listPython(): Result<String> =
        runCatching {
            val installRoot = pythonInstallRoot()
            if (!installRoot.exists()) {
                return@runCatching "No Python versions installed"
            }
            require(installRoot.isDirectory) { "Python install root is not a directory: ${installRoot.absolutePath}" }
            val versions =
                installRoot
                    .listFiles()
                    .orEmpty()
                    .filter { it.isDirectory }
                    .map { it.name }
                    .sorted()
            if (versions.isEmpty()) {
                "No Python versions installed"
            } else {
                versions.joinToString(System.lineSeparator())
            }
        }
}
