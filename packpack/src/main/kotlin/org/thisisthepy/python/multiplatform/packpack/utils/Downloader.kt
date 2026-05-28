package org.thisisthepy.python.multiplatform.packpack.utils

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import java.io.File

data class DownloadSpec(
    val url: String,
    val fileName: String,
    val destDir: File = File(System.getProperty("java.io.tmpdir"), "pypackpack"),
)

data class DownloadResult(
    val success: Boolean,
    val filePath: String,
    val error: String,
)

class Downloader(
    private val httpClient: HttpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 300_000
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 300_000
        }
    },
) : AutoCloseable {
    suspend fun download(spec: DownloadSpec): DownloadResult {
        return try {
            if (!spec.destDir.exists() && !spec.destDir.mkdirs()) {
                return DownloadResult(false, "", "Failed to create destination directory")
            }

            val outputFile = File(spec.destDir, spec.fileName)

            val response: HttpResponse = httpClient.get(spec.url)
            val statusCode = response.status.value

            if (statusCode !in 200..299) {
                return DownloadResult(
                    false,
                    "",
                    "Failed to download file: HTTP $statusCode - ${response.status.description}",
                )
            }

            val bytes: ByteArray = response.readRawBytes()
            outputFile.writeBytes(bytes)

            DownloadResult(true, outputFile.absolutePath, "")
        } catch (e: Exception) {
            DownloadResult(false, "", "Failed to download file: ${e.message}")
        }
    }

    override fun close() {
        httpClient.close()
    }
}
