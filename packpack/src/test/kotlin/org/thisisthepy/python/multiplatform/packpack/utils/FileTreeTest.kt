package org.thisisthepy.python.multiplatform.packpack.utils

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Deleting a directory never follows a symbolic link out of it (#96). Kotlin's `deleteRecursively` does: a
 * link to a directory is walked into, and the directory it points at is emptied.
 */
class FileTreeTest {
    private val root: File = Files.createTempDirectory("ppp-delete-").toFile().canonicalFile

    @AfterTest
    fun cleanup() {
        root.walkBottomUp().forEach { Files.deleteIfExists(it.toPath()) }
    }

    private fun outside(): File = File(root, "outside").apply { mkdirs(); File(this, "keep.txt").writeText("keep") }

    @Test
    fun `a link inside the tree is removed, and what it points at is kept`() {
        val keep = outside()
        val tree = File(root, "tree/a/b").apply { mkdirs() }
        File(tree, "file.txt").writeText("x")
        Files.createSymbolicLink(File(tree, "link").toPath(), keep.toPath())
        Files.createSymbolicLink(File(tree, "file-link").toPath(), File(keep, "keep.txt").toPath())

        assertTrue(File(root, "tree").deleteWithoutFollowingLinks())

        assertFalse(File(root, "tree").exists())
        assertEquals("keep", File(keep, "keep.txt").readText())
    }

    @Test
    fun `a link given as the tree itself is removed, not its target`() {
        val keep = outside()
        val link = File(root, "link").toPath()
        Files.createSymbolicLink(link, keep.toPath())

        assertTrue(link.toFile().deleteWithoutFollowingLinks())

        assertFalse(Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertEquals("keep", File(keep, "keep.txt").readText())
    }

    @Test
    fun `a broken link and a missing tree are fine`() {
        val tree = File(root, "tree").apply { mkdirs() }
        Files.createSymbolicLink(File(tree, "broken").toPath(), File(root, "nowhere").toPath())

        assertTrue(tree.deleteWithoutFollowingLinks())
        assertFalse(tree.exists())
        assertTrue(File(root, "never-existed").deleteWithoutFollowingLinks())
    }

    @Test
    fun `a plain file is deleted`() {
        val file = File(root, "f.txt").apply { writeText("x") }
        assertTrue(file.deleteWithoutFollowingLinks())
        assertFalse(file.exists())
    }
}
