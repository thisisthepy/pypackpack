package org.thisisthepy.python.multiplatform.packpack.utils

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Deletes this file or directory tree without following symbolic links (#96): a link is removed, never what
 * it points at. Kotlin's `deleteRecursively` walks into a link to a directory and empties its target, which
 * is how a project directory, an output directory or a `.venv` holding a link reached files outside it.
 *
 * Returns `true` when nothing is left (a missing path counts), `false` when something could not be deleted.
 */
fun File.deleteWithoutFollowingLinks(): Boolean {
    val root = toPath()
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return true
    return try {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            Files.delete(root)
        } else {
            // walkFileTree does not follow links unless asked to: a link, to a directory or not, is visited as a file.
            Files.walkFileTree(
                root,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        Files.delete(file)
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                        Files.delete(file)
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                        if (exc != null) throw exc
                        Files.delete(dir)
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        }
        true
    } catch (e: IOException) {
        false
    }
}
