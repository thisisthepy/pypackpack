package org.thisisthepy.python.multiplatform.packpack.utils

import java.io.File
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.io.TempDir

class ArchiveTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun extractArchive_extractsZipArchive() {
        val archive = File(tempDir, "python.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("python/bin/python.txt"))
            zip.write("zip-python".toByteArray())
            zip.closeEntry()
        }
        val destDir = File(tempDir, "zip-dest")

        extractArchive(archive, destDir)

        assertEquals("zip-python", File(destDir, "python/bin/python.txt").readText())
    }

    @Test
    fun extractArchive_extractsTarGzArchive() {
        val archive = File(tempDir, "python.tar.gz")
        GZIPOutputStream(archive.outputStream()).use { gzip ->
            gzip.write(tarDirectory("python/"))
            gzip.write(tarDirectory("python/bin/"))
            gzip.write(tarFile("python/bin/python.txt", "tar-python".toByteArray()))
            gzip.write(ByteArray(1024))
        }
        val destDir = File(tempDir, "tar-dest")

        extractArchive(archive, destDir)

        assertEquals("tar-python", File(destDir, "python/bin/python.txt").readText())
    }

    @Test
    fun extractArchive_stripsLeadingPathComponents() {
        val archive = File(tempDir, "uv.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("uv-x86_64/uv"))
            zip.write("uv-binary".toByteArray())
            zip.closeEntry()
        }
        val destDir = File(tempDir, "uv-dest")

        extractArchive(archive, destDir, stripComponents = 1)

        assertEquals("uv-binary", File(destDir, "uv").readText())
    }

    @Test
    fun extractArchive_stripsLeadingPathComponentsFromTarGz() {
        val archive = File(tempDir, "uv.tar.gz")
        GZIPOutputStream(archive.outputStream()).use { gzip ->
            gzip.write(tarDirectory("uv-x86_64/"))
            gzip.write(tarFile("uv-x86_64/uv", "uv-binary".toByteArray()))
            gzip.write(ByteArray(1024))
        }
        val destDir = File(tempDir, "uv-tar-dest")

        extractArchive(archive, destDir, stripComponents = 1)

        assertEquals("uv-binary", File(destDir, "uv").readText())
    }

    @Test
    fun extractArchive_rejectsPathTraversalEntries() {
        val archive = File(tempDir, "unsafe.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("../outside.txt"))
            zip.write("unsafe".toByteArray())
            zip.closeEntry()
        }

        assertFailsWith<IllegalArgumentException> {
            extractArchive(archive, File(tempDir, "unsafe-dest"))
        }
    }

    private fun tarDirectory(name: String): ByteArray = tarHeader(name, 0, '5')

    private fun tarFile(
        name: String,
        content: ByteArray,
    ): ByteArray {
        val header = tarHeader(name, content.size, '0')
        val paddingSize = (512 - content.size % 512) % 512
        return header + content + ByteArray(paddingSize)
    }

    private fun tarHeader(
        name: String,
        size: Int,
        typeFlag: Char,
    ): ByteArray {
        val header = ByteArray(512)
        name.toByteArray().copyInto(header, 0)
        "0000777\u0000".toByteArray().copyInto(header, 100)
        "0000000\u0000".toByteArray().copyInto(header, 108)
        "0000000\u0000".toByteArray().copyInto(header, 116)
        size.toString(8).padStart(11, '0').plus('\u0000').toByteArray().copyInto(header, 124)
        "00000000000\u0000".toByteArray().copyInto(header, 136)
        "        ".toByteArray().copyInto(header, 148)
        header[156] = typeFlag.code.toByte()
        "ustar\u000000".toByteArray().copyInto(header, 257)

        val checksum = header.sumOf { it.toUByte().toInt() }
        checksum.toString(8).padStart(6, '0').plus("\u0000 ").toByteArray().copyInto(header, 148)
        return header
    }
}
