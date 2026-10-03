package org.thisisthepy.python.multiplatform.packpack.dependency.backend.external

import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * uv's release archives differ by platform (#61): the `.tar.gz` ones hold a top-level directory
 * (`uv-aarch64-apple-darwin/uv`), the Windows `.zip` holds `uv.exe` at its root. Stripping one path
 * component from the zip dropped every file, and the install still reported success.
 */
class UVInstallTest {
    private val tempDir: File = Files.createTempDirectory("uv-install").toFile()

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun installFromArchive_windowsZipWithBinariesAtTheRoot() {
        val archive = File(tempDir, "uv-x86_64-pc-windows-msvc.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            for (name in listOf("uv.exe", "uvw.exe", "uvx.exe")) {
                zip.putNextEntry(ZipEntry(name))
                zip.write("binary $name".toByteArray())
                zip.closeEntry()
            }
        }
        val installDir = File(tempDir, "install")

        val binary = UV.installFromArchive(archive, installDir, "uv.exe").getOrThrow()

        assertEquals(File(installDir, "uv.exe"), binary)
        assertEquals("binary uv.exe", binary.readText())
    }

    @Test
    fun installFromArchive_tarGzWithATopLevelDirectory() {
        val archive = File(tempDir, "uv-aarch64-apple-darwin.tar.gz")
        GZIPOutputStream(archive.outputStream()).use { gzip ->
            gzip.write(tarHeader("uv-aarch64-apple-darwin/", 0, '5'))
            gzip.write(tarFile("uv-aarch64-apple-darwin/uv", "binary uv".toByteArray()))
            gzip.write(tarFile("uv-aarch64-apple-darwin/uvx", "binary uvx".toByteArray()))
            gzip.write(ByteArray(1024))
        }
        val installDir = File(tempDir, "install")

        val binary = UV.installFromArchive(archive, installDir, "uv").getOrThrow()

        assertEquals(File(installDir, "uv"), binary)
        assertEquals("binary uv", binary.readText())
        assertTrue(binary.canExecute())
    }

    @Test
    fun installFromArchive_failsWhenTheArchiveHoldsNoBinary() {
        val archive = File(tempDir, "uv-x86_64-pc-windows-msvc.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("README.txt"))
            zip.write("no binary here".toByteArray())
            zip.closeEntry()
        }

        val result = UV.installFromArchive(archive, File(tempDir, "install"), "uv.exe")

        assertTrue(result.isFailure)
        assertTrue("uv.exe" in result.exceptionOrNull()!!.message!!, result.exceptionOrNull()!!.message)
    }

    private fun tarFile(
        name: String,
        content: ByteArray,
    ): ByteArray = tarHeader(name, content.size, '0') + content + ByteArray((512 - content.size % 512) % 512)

    private fun tarHeader(
        name: String,
        size: Int,
        typeFlag: Char,
    ): ByteArray {
        val header = ByteArray(512)
        name.toByteArray().copyInto(header, 0)
        "0000755\u0000".toByteArray().copyInto(header, 100)
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
