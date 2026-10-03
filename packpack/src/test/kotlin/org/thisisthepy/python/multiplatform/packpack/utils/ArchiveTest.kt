package org.thisisthepy.python.multiplatform.packpack.utils

import com.github.luben.zstd.ZstdOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
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
    fun extractArchive_extractsZipArchiveWithDataDescriptors() {
        val archive = File(tempDir, "ios.zip")
        archive.writeBytes(zipWithStoredDataDescriptor("file.txt", "stored-data".toByteArray()))
        val destDir = File(tempDir, "ios-dest")

        extractArchive(archive, destDir)

        assertEquals("stored-data", File(destDir, "file.txt").readText())
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
    fun extractArchive_extractsTarZstArchive() {
        val archive = File(tempDir, "python.tar.zst")
        ZstdOutputStream(archive.outputStream()).use { zstd ->
            zstd.write(tarDirectory("python/"))
            zstd.write(tarDirectory("python/bin/"))
            zstd.write(tarFile("python/bin/python.txt", "tar-zst-python".toByteArray()))
            zstd.write(ByteArray(1024))
        }
        val destDir = File(tempDir, "tar-zst-dest")

        extractArchive(archive, destDir)

        assertEquals("tar-zst-python", File(destDir, "python/bin/python.txt").readText())
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

    @Test
    fun extractArchive_recreatesRelativeSymlinksAndExecutableBits() {
        val archive = File(tempDir, "pbs.tar.gz")
        GZIPOutputStream(archive.outputStream()).use { gzip ->
            gzip.write(tarDirectory("python/"))
            gzip.write(tarDirectory("python/bin/"))
            gzip.write(tarFile("python/bin/python3.14", "interp".toByteArray(), mode = "0000755"))
            gzip.write(tarFile("python/bin/README", "doc".toByteArray(), mode = "0000644"))
            gzip.write(tarHeader("python/bin/python", 0, '2', linkName = "python3.14"))
            gzip.write(ByteArray(1024))
        }
        val destDir = File(tempDir, "pbs-dest")

        extractArchive(archive, destDir, stripComponents = 1, prefixFilter = "python/")

        val link = File(destDir, "bin/python").toPath()
        assertTrue(Files.isSymbolicLink(link))
        assertEquals("python3.14", Files.readSymbolicLink(link).toString())
        assertEquals("interp", link.toFile().readText())
        assertTrue(File(destDir, "bin/python3.14").canExecute())
        assertFalse(File(destDir, "bin/README").canExecute())
    }

    @Test
    fun extractArchive_rejectsSymlinksThatEscapeTheDestination() {
        for (linkName in listOf("../../outside", "/etc/passwd")) {
            val archive = File(tempDir, "evil.tar.gz")
            GZIPOutputStream(archive.outputStream()).use { gzip ->
                gzip.write(tarHeader("bin/python", 0, '2', linkName = linkName))
                gzip.write(ByteArray(1024))
            }

            assertFailsWith<IllegalArgumentException>(linkName) {
                extractArchive(archive, File(tempDir, "evil-dest"))
            }
        }
    }

    private fun tarDirectory(name: String): ByteArray = tarHeader(name, 0, '5')

    private fun zipWithStoredDataDescriptor(
        name: String,
        content: ByteArray,
    ): ByteArray {
        val nameBytes = name.toByteArray()
        val crc = CRC32().apply { update(content) }.value.toInt()
        val out = ByteArrayOutputStream()
        val zip = DataOutputStream(out)

        zip.writeIntLe(0x04034b50)
        zip.writeShortLe(20)
        zip.writeShortLe(0x08)
        zip.writeShortLe(0)
        zip.writeShortLe(0)
        zip.writeShortLe(0)
        zip.writeIntLe(0)
        zip.writeIntLe(0)
        zip.writeIntLe(0)
        zip.writeShortLe(nameBytes.size)
        zip.writeShortLe(0)
        zip.write(nameBytes)
        zip.write(content)
        zip.writeIntLe(0x08074b50.toInt())
        zip.writeIntLe(crc)
        zip.writeIntLe(content.size)
        zip.writeIntLe(content.size)

        val centralDirectoryOffset = out.size()
        zip.writeIntLe(0x02014b50)
        zip.writeShortLe(20)
        zip.writeShortLe(20)
        zip.writeShortLe(0x08)
        zip.writeShortLe(0)
        zip.writeShortLe(0)
        zip.writeShortLe(0)
        zip.writeIntLe(crc)
        zip.writeIntLe(content.size)
        zip.writeIntLe(content.size)
        zip.writeShortLe(nameBytes.size)
        zip.writeShortLe(0)
        zip.writeShortLe(0)
        zip.writeShortLe(0)
        zip.writeShortLe(0)
        zip.writeIntLe(0)
        zip.writeIntLe(0)
        zip.write(nameBytes)

        val centralDirectorySize = out.size() - centralDirectoryOffset
        zip.writeIntLe(0x06054b50)
        zip.writeShortLe(0)
        zip.writeShortLe(0)
        zip.writeShortLe(1)
        zip.writeShortLe(1)
        zip.writeIntLe(centralDirectorySize)
        zip.writeIntLe(centralDirectoryOffset)
        zip.writeShortLe(0)
        return out.toByteArray()
    }

    private fun tarFile(
        name: String,
        content: ByteArray,
        mode: String = "0000777",
    ): ByteArray {
        val header = tarHeader(name, content.size, '0', mode = mode)
        val paddingSize = (512 - content.size % 512) % 512
        return header + content + ByteArray(paddingSize)
    }

    private fun tarHeader(
        name: String,
        size: Int,
        typeFlag: Char,
        mode: String = "0000777",
        linkName: String = "",
    ): ByteArray {
        val header = ByteArray(512)
        name.toByteArray().copyInto(header, 0)
        "$mode\u0000".toByteArray().copyInto(header, 100)
        linkName.toByteArray().copyInto(header, 157)
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

    private fun DataOutputStream.writeShortLe(value: Int) {
        writeByte(value and 0xff)
        writeByte((value ushr 8) and 0xff)
    }

    private fun DataOutputStream.writeIntLe(value: Int) {
        writeByte(value and 0xff)
        writeByte((value ushr 8) and 0xff)
        writeByte((value ushr 16) and 0xff)
        writeByte((value ushr 24) and 0xff)
    }
}
