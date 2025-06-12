package org.thisisthepy.python.multiplatform.packpack.util

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.channels.Channels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Download result data class
 */
data class DownloadResult(
    val success: Boolean,
    val filePath: String,
    val error: String
)

/**
 * Utility for downloading files
 */
class Downloader {
    /**
     * Download file from URL
     * @param spec Download specification
     * @return Download result
     */
    suspend fun download(spec: DownloadSpec): DownloadResult = withContext(Dispatchers.IO) {
        try {
            val url = URL(spec.url)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connect()
            
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return@withContext DownloadResult(
                    false,
                    "",
                    "Failed to download file: HTTP ${connection.responseCode} - ${connection.responseMessage}"
                )
            }
            
            // Create temp directory if it doesn't exist
            val tempDir = File(System.getProperty("java.io.tmpdir"), "pypackpack")
            if (!tempDir.exists() && !tempDir.mkdirs()) {
                return@withContext DownloadResult(
                    false,
                    "",
                    "Failed to create temporary directory"
                )
            }
            
            // Download file
            val outputFile = File(tempDir, spec.fileName)
            val readableByteChannel = Channels.newChannel(connection.inputStream)
            val fileOutputStream = FileOutputStream(outputFile)
            val fileChannel = fileOutputStream.channel
            
            fileChannel.transferFrom(readableByteChannel, 0, Long.MAX_VALUE)
            
            fileOutputStream.close()
            readableByteChannel.close()
            
            DownloadResult(true, outputFile.absolutePath, "")
        } catch (e: Exception) {
            DownloadResult(false, "", "Failed to download file: ${e.message}")
        }
    }
}
