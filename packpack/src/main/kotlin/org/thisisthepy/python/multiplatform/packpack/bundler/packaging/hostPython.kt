package org.thisisthepy.python.multiplatform.packpack.bundler.packaging
import java.io.File
import java.nio.file.Files
import java.net.URL
import java.nio.file.Paths
import java.util.zip.ZipFile
import kotlin.io.path.Path


class hostPython {
    var downloadURL = "https://github.com/astral-sh/python-build-standalone/releases/download/20250212/"

    fun downloadFile(fileUrl: String, destinationPath: String) {
        URL(fileUrl).openStream().use { input ->
            Files.copy(input, Paths.get(destinationPath))
        }
    }

    fun unzip(zipFilePath: String, destDirectory: String) {
        File(destDirectory).mkdirs()
        ZipFile(zipFilePath).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                val filePath = destDirectory + File.separator + entry.name
                if (!entry.isDirectory) {
                    zip.getInputStream(entry).use { input ->
                        File(filePath).outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                } else {
                    File(filePath).mkdirs()
                }
            }
        }
    }

    fun downloadPythonStandalone(targetPlatform: String, targetABI: String) {
        Files.createDirectories(Path("build/python"))

        when("$targetABI-$targetPlatform") {
            "aarch64-macos" -> {
                println("Downloading Python Standalone for aarch64-macos")
                downloadURL += "cpython-3.13.2+20250212-aarch64-apple-darwin-install_only.tar.gz"

                downloadFile(downloadURL, "build/python/cpython-3.13.2+20250212-aarch64-apple-darwin-install_only.tar.gz")
                unzip("build/python/cpython-3.13.2+20250212-aarch64-apple-darwin-install_only.tar.gz", "build/python")

                println("Downloaded Python Standalone for aarch64-macos")
            }
            "x86_64-macos" -> {
                println("Downloading Python Standalone for x86_64-macOS")
                downloadURL += "cpython-3.13.2+20250212-x86_64-apple-darwin-install_only.tar.gz"
            }
            "aarch64-linux" -> {
                println("Downloading Python Standalone for Linux")
                downloadURL += "cpython-3.13.2+20250212-aarch64-unknown-linux-gnu-install_only.tar.gz\n"
            }
            "x86_64-linux" -> {
                println("Downloading Python Standalone for Linux")
                downloadURL += "cpython-3.13.2+20250212-x86_64-unknown-linux-gnu-install_only.tar.gz"
            }
            "x86_64-windows" -> {
                println("Downloading Python Standalone for Windows")
                downloadURL += "cpython-3.13.2+20250212-x86_64-pc-windows-msvc-shared-install_only.tar.gz"
            }
            "ios" -> {
                println("Downloading Python Standalone for iOS")
                downloadURL += ""
            }
            "android" -> {
                println("Downloading Python Standalone for Android")
                downloadURL += ""
            }
            else -> {
                println("Downloading Python Standalone for Unknown Platform")
            }
        }
    }
}