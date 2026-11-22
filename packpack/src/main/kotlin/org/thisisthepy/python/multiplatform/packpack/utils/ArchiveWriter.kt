package org.thisisthepy.python.multiplatform.packpack.utils

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Deterministic archives of a directory tree (`binary` bundle, docs/SPEC.md *Bundle Type*, #156): entries
 * sorted by path, every timestamp fixed, owners empty. [extractArchive] is the reading side.
 */
object ArchiveWriter {
    /** 1980-01-01T00:00:00Z, the earliest time a zip entry can carry; used for tar too. */
    private const val FIXED_EPOCH_SECONDS = 315532800L

    /**
     * Writes [root]'s tree under the top-level directory [topName] to a `.tar.gz` [archive]. Symbolic
     * links are stored as links (not followed); a file keeps its executable bit (mode 755, else 644).
     */
    fun writeTarGz(root: File, topName: String, archive: File) {
        archive.parentFile?.mkdirs()
        GZIPOutputStream(archive.outputStream().buffered()).use { gz ->
            entries(root).forEach { path ->
                val rel = "$topName/" + root.toPath().relativize(path).toString().replace(File.separatorChar, '/')
                when {
                    Files.isSymbolicLink(path) -> writeTarEntry(gz, rel, '2', 0, Files.readSymbolicLink(path).toString().replace(File.separatorChar, '/'), 0x1ff)
                    Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) -> writeTarEntry(gz, "$rel/", '5', 0, "", 0x1ed)
                    else -> {
                        val bytes = Files.readAllBytes(path)
                        writeTarEntry(gz, rel, '0', bytes.size.toLong(), "", if (isExecutable(path)) 0x1ed else 0x1a4)
                        gz.write(bytes)
                        pad(gz, bytes.size.toLong())
                    }
                }
            }
            gz.write(ByteArray(1024))
        }
    }

    /** Writes [root]'s tree under [topName] to a `.zip` [archive]; a symbolic link is refused (Windows trees have none). */
    fun writeZip(root: File, topName: String, archive: File) {
        archive.parentFile?.mkdirs()
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            entries(root).forEach { path ->
                require(!Files.isSymbolicLink(path)) { "A .zip archive cannot hold the symbolic link $path" }
                val rel = "$topName/" + root.toPath().relativize(path).toString().replace(File.separatorChar, '/')
                val isDir = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                val entry = ZipEntry(if (isDir) "$rel/" else rel).apply { time = FIXED_EPOCH_SECONDS * 1000 }
                zip.putNextEntry(entry)
                if (!isDir) Files.copy(path, zip)
                zip.closeEntry()
            }
        }
    }

    /** Every path under [root] (not [root] itself), not following links, sorted by its relative path. */
    private fun entries(root: File): List<Path> =
        Files.walk(root.toPath()).use { stream ->
            stream.filter { it != root.toPath() }.toList()
        }.sortedBy { root.toPath().relativize(it).toString().replace(File.separatorChar, '/') }

    private fun isExecutable(path: Path): Boolean =
        runCatching { PosixFilePermission.OWNER_EXECUTE in Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS) }
            .getOrElse { Files.isExecutable(path) }

    /**
     * One ustar header. A name over 100 bytes is split into ustar's 155-byte prefix and 100-byte name at a
     * `/` (what [extractArchive] reads); only a name or link target that still does not fit gets a PAX header.
     */
    private fun writeTarEntry(out: OutputStream, name: String, type: Char, size: Long, link: String, mode: Int) {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val linkBytes = link.toByteArray(Charsets.UTF_8)
        val split = if (nameBytes.size > 100) ustarSplit(name) else null
        val nameFits = nameBytes.size <= 100 || split != null
        if (!nameFits || linkBytes.size > 100) {
            val records = ByteArrayOutputStream()
            if (!nameFits) records.write(paxRecord("path", name))
            if (linkBytes.size > 100) records.write(paxRecord("linkpath", link))
            val body = records.toByteArray()
            out.write(header("PaxHeader", 'x', body.size.toLong(), "", 0x1a4))
            out.write(body)
            pad(out, body.size.toLong())
        }
        out.write(header(split?.second ?: name, type, size, link, mode, prefix = split?.first ?: ""))
    }

    /** `prefix/name` with prefix <= 155 and name <= 100 bytes, split at the last `/` that allows it, or null. */
    private fun ustarSplit(name: String): Pair<String, String>? {
        val trimmed = name.trimEnd('/')
        val trailing = name.substring(trimmed.length)
        var index = trimmed.lastIndexOf('/')
        while (index > 0) {
            val prefix = trimmed.substring(0, index)
            val rest = trimmed.substring(index + 1) + trailing
            if (prefix.toByteArray(Charsets.UTF_8).size <= 155 && rest.toByteArray(Charsets.UTF_8).size <= 100) return prefix to rest
            index = trimmed.lastIndexOf('/', index - 1)
        }
        return null
    }

    private fun paxRecord(key: String, value: String): ByteArray {
        val content = " $key=$value\n"
        val contentLength = content.toByteArray(Charsets.UTF_8).size
        var length = contentLength + 1
        while ((length.toString().length + contentLength) != length) length = length.toString().length + contentLength
        return "$length$content".toByteArray(Charsets.UTF_8)
    }

    private fun header(name: String, type: Char, size: Long, link: String, mode: Int, prefix: String = ""): ByteArray {
        val h = ByteArray(512)
        fun put(offset: Int, length: Int, value: ByteArray) = value.copyInto(h, offset, 0, minOf(length, value.size))
        fun octal(offset: Int, length: Int, value: Long) = put(offset, length, (value.toString(8).padStart(length - 1, '0') + "\u0000").toByteArray())
        put(0, 100, name.toByteArray(Charsets.UTF_8))
        octal(100, 8, mode.toLong())
        octal(108, 8, 0)
        octal(116, 8, 0)
        octal(124, 12, size)
        octal(136, 12, FIXED_EPOCH_SECONDS)
        "        ".toByteArray().copyInto(h, 148)
        h[156] = type.code.toByte()
        put(157, 100, link.toByteArray(Charsets.UTF_8))
        put(257, 6, "ustar\u0000".toByteArray())
        put(263, 2, "00".toByteArray())
        put(345, 155, prefix.toByteArray(Charsets.UTF_8))
        val checksum = h.sumOf { it.toInt() and 0xff }
        put(148, 8, (checksum.toString(8).padStart(6, '0') + "\u0000 ").toByteArray())
        return h
    }

    private fun pad(out: OutputStream, size: Long) {
        val remainder = (size % 512).toInt()
        if (remainder != 0) out.write(ByteArray(512 - remainder))
    }
}
