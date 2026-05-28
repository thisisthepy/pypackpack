package org.thisisthepy.python.multiplatform.packpack.utils

import com.github.luben.zstd.ZstdInputStream
import java.io.EOFException
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile

fun extractArchive(
    archiveFile: File,
    destDir: File,
    stripComponents: Int = 0,
) {
    require(stripComponents >= 0) { "stripComponents cannot be negative" }

    val fileName = archiveFile.name.lowercase()
    destDir.mkdirs()

    when {
        fileName.endsWith(".zip") -> {
            ZipFile(archiveFile).use { zip ->
                zip.entries().asSequence().forEach { entry ->
                    val entryName = stripArchivePath(entry.name, stripComponents)
                    if (entryName != null) {
                        val outputFile = outputFileForArchiveEntry(destDir, entryName)
                        if (entry.isDirectory) {
                            outputFile.mkdirs()
                        } else {
                            outputFile.parentFile?.mkdirs()
                            zip.getInputStream(entry).use { input ->
                                outputFile.outputStream().use { output -> input.copyTo(output) }
                            }
                        }
                    }
                }
            }
        }

        fileName.endsWith(".tar.gz") || fileName.endsWith(".tar.zst") -> {
            archiveFile.inputStream().buffered().use { input ->
                val tarInput =
                    if (fileName.endsWith(".tar.gz")) {
                        GZIPInputStream(input)
                    } else {
                        ZstdInputStream(input)
                    }

                tarInput.use { tar ->
                    while (true) {
                        val header = tar.readNBytes(512)
                        if (header.isEmpty()) break
                        if (header.size < 512) throw EOFException("Incomplete tar header in ${archiveFile.absolutePath}")
                        if (header.all { it == 0.toByte() }) break

                        val entryName = stripArchivePath(tarEntryName(header), stripComponents)
                        val size = tarEntrySize(header)
                        val typeFlag = header[156].toInt().toChar()

                        if (entryName == null) {
                            tar.skipNBytes(size)
                        } else {
                            val outputFile = outputFileForArchiveEntry(destDir, entryName)
                            when (typeFlag) {
                                '5' -> {
                                    outputFile.mkdirs()
                                }

                                '0', '\u0000' -> {
                                    outputFile.parentFile?.mkdirs()
                                    outputFile.outputStream().use { output ->
                                        var remaining = size
                                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                        while (remaining > 0) {
                                            val read = tar.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                            if (read == -1) throw EOFException("Incomplete tar entry $entryName")
                                            output.write(buffer, 0, read)
                                            remaining -= read
                                        }
                                    }
                                }

                                else -> {
                                    tar.skipNBytes(size)
                                }
                            }
                        }

                        val padding = (512 - size.mod(512)).mod(512)
                        if (padding > 0) tar.skipNBytes(padding.toLong())
                    }
                }
            }
        }

        else -> {
            throw IllegalArgumentException("Unsupported archive format: ${archiveFile.name}")
        }
    }
}

private fun stripArchivePath(
    entryName: String,
    stripComponents: Int,
): String? {
    val parts = entryName.split('/').filter { it.isNotEmpty() }
    if (parts.size <= stripComponents) return null
    return parts.drop(stripComponents).joinToString("/")
}

private fun outputFileForArchiveEntry(
    destDir: File,
    entryName: String,
): File {
    val outputFile = File(destDir, entryName).canonicalFile
    val destRoot = destDir.canonicalFile
    require(outputFile.toPath().startsWith(destRoot.toPath())) { "Archive entry escapes destination: $entryName" }
    return outputFile
}

private fun tarEntryName(header: ByteArray): String {
    val name = header.tarString(0, 100)
    val prefix = header.tarString(345, 155)
    return if (prefix.isBlank()) name else "$prefix/$name"
}

private fun tarEntrySize(header: ByteArray): Long =
    header
        .tarString(124, 12)
        .trim()
        .ifBlank { "0" }
        .toLong(8)

private fun ByteArray.tarString(
    offset: Int,
    length: Int,
): String {
    val end = (offset until offset + length).firstOrNull { this[it] == 0.toByte() } ?: offset + length
    return decodeToString(offset, end)
}
