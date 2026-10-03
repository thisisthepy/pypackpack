package org.thisisthepy.python.multiplatform.packpack.utils

import com.github.luben.zstd.ZstdInputStream
import java.io.EOFException
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile

fun extractArchive(
    archiveFile: File,
    destDir: File,
    stripComponents: Int = 0,
    prefixFilter: String? = null,
) {
    require(stripComponents >= 0) { "stripComponents cannot be negative" }

    val fileName = archiveFile.name.lowercase()
    destDir.mkdirs()

    when {
        fileName.endsWith(".zip") -> {
            ZipFile(archiveFile).use { zip ->
                zip.entries().asSequence().forEach { entry ->
                    val entryName = stripArchivePath(entry.name, stripComponents, prefixFilter)
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

                        val entryName = stripArchivePath(tarEntryName(header), stripComponents, prefixFilter)
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
                                    // An interpreter whose bin/python3.x lost its x bit cannot run.
                                    if ((tarEntryMode(header) and 0b001_001_001) != 0) {
                                        outputFile.setExecutable(true, false)
                                    }
                                }

                                '2' -> {
                                    createContainedSymlink(destDir, entryName, header.tarString(157, 100))
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
    prefixFilter: String? = null,
): String? {
    if (prefixFilter != null && !entryName.startsWith(prefixFilter)) return null
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

/**
 * Recreates a tar symbolic link (python-build-standalone's `bin/python -> python3.14`). The link
 * must be relative and must resolve inside [destDir]; anything else is rejected like a `..` entry.
 * A file system that cannot hold symlinks (Windows without the privilege) skips it, as before.
 */
private fun createContainedSymlink(
    destDir: File,
    entryName: String,
    linkTarget: String,
) {
    require(linkTarget.isNotEmpty() && !linkTarget.startsWith("/") && !linkTarget.contains('\\')) {
        "Archive symlink escapes destination: $entryName -> $linkTarget"
    }
    val destRoot = destDir.canonicalFile.toPath()
    // Not canonicalised: canonicalising a path that is already a symlink would follow it.
    val link = destRoot.resolve(entryName).normalize()
    require(link.startsWith(destRoot) && link != destRoot) { "Archive entry escapes destination: $entryName" }
    val resolved = link.parent.resolve(linkTarget).normalize()
    require(resolved.startsWith(destRoot)) { "Archive symlink escapes destination: $entryName -> $linkTarget" }
    Files.createDirectories(link.parent)
    Files.deleteIfExists(link)
    try {
        Files.createSymbolicLink(link, Paths.get(linkTarget))
    } catch (e: UnsupportedOperationException) {
        // This file system has no symlinks; the entry is skipped, as every symlink was before.
    } catch (e: java.nio.file.FileSystemException) {
        // e.g. Windows without SeCreateSymbolicLinkPrivilege; skipped as above.
    }
}

private fun tarEntryMode(header: ByteArray): Int =
    header
        .tarString(100, 8)
        .trim()
        .ifBlank { "0" }
        .toInt(8)

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
