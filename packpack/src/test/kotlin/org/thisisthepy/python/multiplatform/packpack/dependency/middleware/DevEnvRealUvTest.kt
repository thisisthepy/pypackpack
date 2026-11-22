package org.thisisthepy.python.multiplatform.packpack.dependency.middleware

import org.junit.jupiter.api.Tag
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * docs/SPEC.md *Dev environment dependency management* (#159): the root `add`, `tree`, `remove` and
 * `sync` run the real uv on a real project, and what lands in `pyproject.toml` and the root `.venv` is
 * checked. `DependencyCommandTest` covers only the CLI forwarding.
 *
 * **Requires network access and `uv`**, like `UVBackendRealInstallTest`: `six==1.17.0` (pure Python,
 * pinned) comes from PyPI.
 */
@Tag("network")
class DevEnvRealUvTest {
    @Test
    fun rootAddTreeRemoveAndSyncChangeTheProjectAndItsVenv() {
        val root = Files.createTempDirectory("ppp-devenv-real-").toFile().canonicalFile
        val oldUserDir = System.getProperty("user.dir")
        try {
            val middleware = DefaultMiddleware().apply { initialize() }
            middleware.initProject(path = root.absolutePath, targets = null, extraArgs = mapOf("name" to "devenv_demo")).getOrThrow()
            System.setProperty("user.dir", root.absolutePath)
            val pyproject = File(root, "pyproject.toml")

            assertTrue(middleware.addDependencies(null, listOf("six==1.17.0"), null, null), "add failed")
            assertTrue("six==1.17.0" in pyproject.readText(), pyproject.readText())
            assertTrue(sixInstalled(root), "uv add did not install six into the root .venv")

            val tree = captureStdout { assertTrue(middleware.showDependencyTree(null, null, null), "tree failed") }
            assertTrue(Regex("""six v1\.17\.0""").containsMatchIn(tree), tree)

            File(root, ".venv").deleteRecursively()
            assertTrue(middleware.syncDependencies(null, null, null), "sync failed")
            assertTrue(sixInstalled(root), "sync did not recreate the .venv with six")

            assertTrue(middleware.removeDependencies(null, listOf("six"), null, null), "remove failed")
            assertFalse("six" in pyproject.readText().substringAfter("dependencies"), pyproject.readText())
            assertFalse(sixInstalled(root), "uv remove left six in the root .venv")
        } finally {
            System.setProperty("user.dir", oldUserDir)
            root.deleteRecursively()
        }
    }

    private fun sixInstalled(root: File): Boolean =
        File(root, ".venv").walkTopDown().any { it.name == "six.py" && it.parentFile.name == "site-packages" }

    private fun captureStdout(block: () -> Unit): String {
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer, true))
        try {
            block()
        } finally {
            System.setOut(original)
        }
        return buffer.toString()
    }
}
