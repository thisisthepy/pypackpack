package org.thisisthepy.python.multiplatform.packpack.utils

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [Downloader] against a loopback server: a redirect is followed (GitHub release assets redirect), an error status fails naming it. */
class DownloaderTest {
    @TempDir
    lateinit var tempDir: File

    private lateinit var server: HttpServer
    private val base get() = "http://127.0.0.1:${server.address.port}"
    private val payload = ByteArray(200_000) { (it % 251).toByte() }

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/asset") { exchange ->
            exchange.responseHeaders.add("Location", "/blob")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/blob") { exchange ->
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.createContext("/missing") { exchange ->
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        server.start()
    }

    @AfterTest
    fun stop() = server.stop(0)

    @Test
    fun followsARedirectAndWritesTheBody() {
        val dest = File(tempDir, "nested/dir")
        val result = runBlocking { Downloader().use { it.download(DownloadSpec("$base/asset", "python.tar.gz", dest)) } }
        assertTrue(result.success, result.error)
        assertEquals(File(dest, "python.tar.gz").absolutePath, result.filePath)
        assertTrue(payload.contentEquals(File(result.filePath).readBytes()))
    }

    @Test
    fun anErrorStatusFailsNamingItAndLeavesNoFile() {
        val result = runBlocking { Downloader().use { it.download(DownloadSpec("$base/missing", "x.tar.gz", tempDir)) } }
        assertFalse(result.success)
        assertTrue("404" in result.error, result.error)
        assertFalse(File(tempDir, "x.tar.gz").exists())
    }
}
