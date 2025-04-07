package org.thisisthepy.python.multiplatform.packpack.core

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class Utils {
    private val projectDir: String = System.getProperty("user.dir")
    private val toolsDir = File(projectDir, "tools")
    val os = detectOS()
    val arch = detectArch()

    private fun extractTarGz(tarGzFile: File, destDir: File) {
        // Use tar command on all platforms
        val processBuilder = ProcessBuilder(
            "tar", "-xzf", tarGzFile.absolutePath, "-C", destDir.absolutePath
        )

        // Set working directory
        processBuilder.directory(destDir.parentFile)

        try {
            val process = processBuilder.start()

            // Redirect error stream to standard output
            process.errorStream.bufferedReader().use { reader ->
                reader.lineSequence().forEach { println("tar error: $it") }
            }

            val exitCode = process.waitFor()

            if (exitCode != 0) {
                throw RuntimeException("Extraction failed (code: $exitCode)")
            }
        } catch (e: Exception) {
            println("Error during extraction: ${e.message}")
            throw RuntimeException("Extraction failed", e)
        }
    }

    fun extractZip(zipFile: File, destDir: File) {
        try {
            if (!destDir.exists()) {
                destDir.mkdirs()
            }

            val processBuilder = when (os) {
                "windows" -> ProcessBuilder(
                    "powershell", "-Command", "Expand-Archive", "-Path", zipFile.absolutePath,
                    "-DestinationPath", destDir.absolutePath, "-Force"
                )
                "macos", "linux" -> ProcessBuilder(
                    "unzip", "-o", zipFile.absolutePath, "-d", destDir.absolutePath
                )
                else -> throw IllegalStateException("Unsupported operating system: $os")
            }

            // Set working directory
            processBuilder.directory(destDir.parentFile)

            // 표준 오류와 표준 출력을 병합하여 함께 처리
            processBuilder.redirectErrorStream(true)

            val process = processBuilder.start()

            // 단일 스레드에서 출력 처리
            val outputThread = Thread {
                process.inputStream.bufferedReader().use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        println(line)
                    }
                }
            }
            outputThread.start()

            val exitCode = process.waitFor()
            outputThread.join(3000) // 최대 3초 대기

            if (exitCode != 0) {
                throw RuntimeException("Extraction failed (code: $exitCode)")
            }

            println("Extraction completed successfully")
        } catch (e: Exception) {
            println("Error during ZIP extraction: ${e.message}")
            throw RuntimeException("ZIP extraction failed", e)
        }
    }

    private fun detectOS(): String {
        val osName = System.getProperty("os.name").lowercase()
        return when {
            osName.contains("win") -> "windows"
            osName.contains("mac") -> "macos"
            osName.contains("nix") || osName.contains("nux") || osName.contains("aix") -> "linux"
            else -> "unknown"
        }
    }

    private fun detectArch(): String {
        val osArch = System.getProperty("os.arch").lowercase()
        return when {
            osArch.contains("amd64") || osArch.contains("x86_64") -> "x86_64"
            osArch.contains("aarch64") || osArch.contains("arm64") -> "aarch64"
            osArch.contains("x86") || osArch.contains("i386") || osArch.contains("i686") -> "i686" // 32-bit
            else -> "unknown"
        }
    }

    fun executeProcess(processBuilder: ProcessBuilder, processName: String) {
        try {
            val process = processBuilder.start()

            // Redirect error stream to standard output
            process.errorStream.bufferedReader().use { reader ->
                reader.lineSequence().forEach { println(it) }
            }

            // Capture and display standard output as well
            process.inputStream.bufferedReader().use { reader ->
                reader.lineSequence().forEach { println(it) }
            }

            val exitCode = process.waitFor()

            if (exitCode != 0) {
                throw RuntimeException("$processName failed (code: $exitCode)")
            }
        } catch (e: Exception) {
            println("Error during $processName: ${e.message}")
            throw RuntimeException("$processName failed", e)
        }
    }

    fun getUVFileName(): String {
        return when (os) {
            "windows" -> "uv-$arch-pc-windows-msvc.zip"
            "macos" -> "uv-$arch-apple-darwin.tar.gz"
            "linux" -> "uv-$arch-unknown-linux-gnu.tar.gz"
            else -> throw IllegalStateException("UV unsupported OS: $os-$arch")
        }
    }

    fun getNdkFileName(version: String): String {
        return when (os) {
            "windows" -> "android-ndk-$version-windows.zip"
            "macos" -> "android-ndk-$version-darwin.zip"
            "linux" -> "android-ndk-$version-linux.zip"
            else -> throw IllegalStateException("Android NDK unsupported OS: $os-$arch")
        }
    }

    fun downloadAndExtractUV(
        version: String = "0.6.12",
        uvFileName: String = getUVFileName(),
    ): String {
        val downloadUrl = "https://github.com/astral-sh/uv/releases/download/$version/$uvFileName"
        val fileName = downloadUrl.substringAfterLast("/")
        val downloadedFile = File(toolsDir, fileName)

        println("Downloading UV from: $downloadUrl")
        java.net.URI(downloadUrl).toURL().openStream().use { input ->
            Files.copy(input, downloadedFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        println("Download completed: ${downloadedFile.absolutePath}")

        // Remove extensions from filename
        val extractDir = File(toolsDir, "uv")

        // Extract based on file extension
        println("Extracting to: ${extractDir.absolutePath}")
        if (fileName.endsWith(".zip")) {
            extractZip(downloadedFile, extractDir)
        } else if (fileName.endsWith(".tar.gz")) {
            extractTarGz(downloadedFile, toolsDir)
        } else {
            throw IllegalArgumentException("Unsupported archive format: $fileName")
        }

        println("Extraction completed: ${extractDir.absolutePath}")
        return extractDir.absolutePath
    }

    fun downloadAndExtractNdk(
        version: String = "r28",
        ndkFileName: String = getNdkFileName(version),
    ): String {
        val downloadUrl = "https://dl.google.com/android/repository/$ndkFileName"
        val fileName = downloadUrl.substringAfterLast("/")
        val downloadedFile = File(toolsDir, fileName)

        println("Downloading Android NDK from: $downloadUrl")

        // Check file size in advance
        val connection = java.net.URI(downloadUrl).toURL().openConnection()
        val fileSize = connection.contentLengthLong

        var downloadedSize = 0L
        var lastProgressPercent = -1

        try {
            java.net.URI(downloadUrl).toURL().openStream().use { inputStream ->
                val buffer = ByteArray(8192)
                var bytesRead: Int

                downloadedFile.outputStream().buffered().use { outputStream ->
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        downloadedSize += bytesRead

                        // Calculate and print progress (update in 5% increments)
                        val progressPercent = (downloadedSize * 100 / fileSize).toInt()
                        if (progressPercent != lastProgressPercent && progressPercent % 5 == 0) {
                            lastProgressPercent = progressPercent
                            print("\rDownload progress: [${"=".repeat(progressPercent/5)}${" ".repeat(20-progressPercent/5)}] $progressPercent%")
                        }
                    }
                }
            }
            println("\nDownload completed: ${downloadedFile.absolutePath}")

            // Remove extensions from filename
            val extractDir = File(toolsDir, "ndk")

            // Extract based on file extension
            println("Extracting to: ${extractDir.absolutePath}")
            extractZip(downloadedFile, toolsDir)

            println("Extraction completed: ${extractDir.absolutePath}")
            return extractDir.absolutePath
        } catch (e: Exception) {
            println("\nDownload error: ${e.message}")
            throw e
        }
    }

    fun generateMesonCompilerFile(
        outputPath: String,
        c: String? = null,
        cpp: String? = null,
        ar: String? = null,
        strip: String? = null,
        additionalBinaries: Map<String, String> = emptyMap()
    ): String {
        val file = File(outputPath)

        val content = buildString {
            appendLine("[binaries]")
            c?.let { appendLine("c = '$it'") }
            cpp?.let { appendLine("cpp = '$it'") }
            ar?.let { appendLine("ar = '$it'") }
            strip?.let { appendLine("strip = '$it'") }

            additionalBinaries.forEach { (name, path) ->
                appendLine("$name = '$path'")
            }
        }

        file.writeText(content)
        return outputPath
    }
}