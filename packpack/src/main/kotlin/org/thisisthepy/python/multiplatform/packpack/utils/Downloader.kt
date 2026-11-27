package org.thisisthepy.python.multiplatform.packpack.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.StandardCopyOption
import java.time.Duration

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

/**
 * One HTTP GET into a file, with the JDK's [HttpClient]. Not ktor: packpack runs inside Gradle, whose embedded
 * kotlin-stdlib (2.0.20 in Gradle 8.11) lacks what ktor 3.4 is compiled against (`SpillingKt`), so every download
 * from toolchain's plugin failed with NoClassDefFoundError (#170, `GradleStdlibCompatTest`). Redirects are
 * followed (GitHub release assets redirect to their storage), and the body streams to a temporary file that
 * replaces the target only when complete.
 */
class Downloader(
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(30))
        .build(),
    private val requestTimeout: Duration = Duration.ofSeconds(300),
) : AutoCloseable {
    suspend fun download(spec: DownloadSpec): DownloadResult = withContext(Dispatchers.IO) {
        try {
            if (!spec.destDir.exists() && !spec.destDir.mkdirs()) {
                return@withContext DownloadResult(false, "", "Failed to create destination directory")
            }
            val outputFile = File(spec.destDir, spec.fileName)
            val partial = File(spec.destDir, "${spec.fileName}.part").apply { delete() }
            val request = HttpRequest.newBuilder(URI.create(spec.url)).timeout(requestTimeout).GET().build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(partial.toPath()))
            val statusCode = response.statusCode()
            if (statusCode !in 200..299) {
                partial.delete()
                return@withContext DownloadResult(false, "", "Failed to download file: HTTP $statusCode from ${spec.url}")
            }
            java.nio.file.Files.move(partial.toPath(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            DownloadResult(true, outputFile.absolutePath, "")
        } catch (e: Exception) {
            File(spec.destDir, "${spec.fileName}.part").delete()
            DownloadResult(false, "", "Failed to download file: ${e.message ?: e::class.simpleName}")
        }
    }

    override fun close() {}
}
