package org.thisisthepy.python.multiplatform.packpack.dependency.middleware

import org.thisisthepy.python.multiplatform.packpack.dependency.backend.BackendInterface
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `python use <version>` (`changePythonVersion`): the one function of the former DevEnv with logic of
 * its own. Pinned before DevEnv folded into DefaultMiddleware (#86), so the move is seen not to change it.
 */
class DefaultMiddlewarePythonVersionTest {
    private val projectRoot: File = Files.createTempDirectory("ppp-python-use-").toFile().canonicalFile
    private val previousUserDir: String = System.getProperty("user.dir")

    init {
        File(projectRoot, "pyproject.toml").writeText("[project]\nname = \"demo\"\n")
        System.setProperty("user.dir", projectRoot.absolutePath)
    }

    @AfterTest
    fun cleanup() {
        System.setProperty("user.dir", previousUserDir)
        projectRoot.deleteRecursively()
    }

    @Test
    fun replacesTheVenvWithOneForTheVersionAndSyncsIntoIt() {
        val stale = File(projectRoot, ".venv/stale.txt").apply { parentFile.mkdirs() }
        stale.writeText("old")
        val backend = RecordingBackend()

        assertTrue(DefaultMiddleware().withBackend(backend).changePythonVersion("3.15t"))

        assertFalse(stale.exists(), "the old .venv is deleted first")
        val venv = File(projectRoot, ".venv").absolutePath
        assertEquals(
            listOf("venv $venv 3.15t ${projectRoot.absolutePath}", "sync $venv null ${projectRoot.absolutePath}"),
            backend.calls,
        )
    }

    /** Replacing `.venv` never follows a link out of it (#96). */
    @Test
    fun replacingTheVenvDoesNotFollowALinkOutOfIt() {
        val outside = File(projectRoot, "outside").apply { mkdirs() }
        File(outside, "keep.txt").writeText("keep")
        File(projectRoot, ".venv").mkdirs()
        java.nio.file.Files.createSymbolicLink(File(projectRoot, ".venv/link").toPath(), outside.toPath())

        assertTrue(DefaultMiddleware().withBackend(RecordingBackend()).changePythonVersion("3.15t"))

        assertEquals("keep", File(outside, "keep.txt").readText())
    }

    @Test
    fun aFailedVenvIsAFailureAndNothingIsSynced() {
        val backend = RecordingBackend(venvFails = true)

        assertFalse(DefaultMiddleware().withBackend(backend).changePythonVersion("3.15t"))

        assertEquals(1, backend.calls.size, "${backend.calls}")
        assertTrue(backend.calls.single().startsWith("venv "), "${backend.calls}")
    }

    /** Today a failed sync is printed but the change still counts as done; the fold keeps that. */
    @Test
    fun aFailedSyncStillReportsTheVersionAsChanged() {
        val backend = RecordingBackend(syncFails = true)

        assertTrue(DefaultMiddleware().withBackend(backend).changePythonVersion("3.15t"))

        assertEquals(2, backend.calls.size, "${backend.calls}")
    }

    @Test
    fun withoutAProjectNothingIsCalled() {
        File(projectRoot, "pyproject.toml").delete()
        val outside = Files.createTempDirectory("ppp-no-project-").toFile()
        System.setProperty("user.dir", outside.absolutePath)
        val backend = RecordingBackend()
        try {
            assertFalse(DefaultMiddleware().withBackend(backend).changePythonVersion("3.15t"))
            assertTrue(backend.calls.isEmpty(), "${backend.calls}")
        } finally {
            outside.deleteRecursively()
        }
    }

    private fun DefaultMiddleware.withBackend(backend: BackendInterface): DefaultMiddleware {
        DefaultMiddleware::class.java
            .getDeclaredField("backend")
            .apply { isAccessible = true }
            .set(this, backend)
        return this
    }

    private class RecordingBackend(
        private val venvFails: Boolean = false,
        private val syncFails: Boolean = false,
    ) : BackendInterface {
        val calls = mutableListOf<String>()

        private fun result(fails: Boolean) = if (fails) Result.failure<String>(IllegalStateException("boom")) else Result.success("ok")

        override fun initialize() = Unit

        override suspend fun getVersion(): Result<String> = Result.success("uv 0.0.0")

        override suspend fun isToolInstalled(): Boolean = true

        override suspend fun installTool(): Result<String> = Result.success("ok")

        override suspend fun createVirtualEnvironment(
            path: String,
            pythonVersion: String?,
            extraArgs: Map<String, String>?,
            workingDir: File?,
        ): Result<String> {
            calls += "venv $path $pythonVersion ${workingDir?.absolutePath}"
            return result(venvFails)
        }

        override suspend fun initProject(
            path: String?,
            extraArgs: Map<String, String>?,
            workingDir: File?,
        ): Result<String> = Result.success("ok")

        override suspend fun addDependencies(
            packageName: String?,
            dependencies: List<String>,
            extraArgs: Map<String, String>?,
            workingDir: File?,
        ): Result<String> = Result.success("ok")

        override suspend fun removeDependencies(
            packageName: String?,
            dependencies: List<String>,
            extraArgs: Map<String, String>?,
            workingDir: File?,
        ): Result<String> = Result.success("ok")

        override suspend fun syncDependencies(
            venvPath: String,
            extraArgs: Map<String, String>?,
            workingDir: File?,
        ): Result<String> {
            calls += "sync $venvPath $extraArgs ${workingDir?.absolutePath}"
            return result(syncFails)
        }

        override suspend fun installDependenciesToTarget(
            targetDir: String,
            pythonPlatform: String,
            extraArgs: Map<String, String>?,
            workingDir: File?,
            requirements: List<String>?,
        ): Result<String> = Result.success("ok")

        override suspend fun showDependencyTree(
            packageName: String?,
            extraArgs: Map<String, String>?,
            workingDir: File?,
        ): Result<String> = Result.success("ok")

        override suspend fun lockDependencies(projectRoot: String): Result<String> = Result.success("ok")

        override fun listPython(): Result<String> = Result.success("ok")

        override fun findPython(pythonVersion: String): Result<String> = Result.success("ok")

        override suspend fun installPython(
            pythonVersion: String,
            targetPlatform: String?,
            installDir: File?,
        ): Result<String> = Result.success("ok")

        override fun uninstallPython(pythonVersion: String): Result<String> = Result.success("ok")
    }
}
