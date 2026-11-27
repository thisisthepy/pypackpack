package org.thisisthepy.python.multiplatform.packpack.utils

import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [ArchiveWriter] (`binary` bundle, #156): round trips through [extractArchive] and is deterministic. */
class ArchiveWriterTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun aLongPathRoundTripsThroughTheUstarPrefixAndTheArchiveIsDeterministic() {
        val root = File(tempDir, "tree")
        val deep = "runtime/lib/python3.15t/site-packages/" + "a_rather_long_directory_name/".repeat(4) + "module_with_a_long_name.py"
        assertTrue(("pkg/$deep").length > 100, "the path must need the prefix field")
        File(root, deep).apply { parentFile.mkdirs(); writeText("X = 1\n") }
        File(root, "bin/run").apply { parentFile.mkdirs(); writeText("#!/bin/sh\n"); setExecutable(true, false) }

        val first = File(tempDir, "a.tar.gz").also { ArchiveWriter.writeTarGz(root, "pkg", it) }
        val second = File(tempDir, "b.tar.gz").also { ArchiveWriter.writeTarGz(root, "pkg", it) }
        assertContentEquals(gunzip(first), gunzip(second), "the same tree gives the same tar")

        val out = File(tempDir, "out").apply { mkdirs() }
        extractArchive(first, out)
        assertEquals("X = 1\n", File(out, "pkg/$deep").readText())
        assertTrue(File(out, "pkg/bin/run").canExecute())
    }

    private fun gunzip(file: File): ByteArray = GZIPInputStream(file.inputStream()).use { it.readBytes() }
}
