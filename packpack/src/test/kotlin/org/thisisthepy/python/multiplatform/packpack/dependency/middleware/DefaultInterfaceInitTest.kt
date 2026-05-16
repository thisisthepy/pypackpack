package org.thisisthepy.python.multiplatform.packpack.dependency.middleware

import org.thisisthepy.python.multiplatform.packpack.dependency.backend.BaseInterface
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

class DefaultInterfaceInitTest {
    @Test
    fun initProjectCreatesDefaultReadmeAndLicense() {
        val tempRoot = Files.createTempDirectory("ppp-init-").toFile()
        try {
            val middleware = DefaultInterface().withBackend(FakeBackend())

            middleware
                .initProject(
                    path = tempRoot.absolutePath,
                    targets = null,
                    extraArgs = mapOf("name" to "demo_pkg"),
                ).getOrThrow()

            val readme = File(tempRoot, "README.md").readText()
            assertTrue(readme.startsWith("# demo_pkg\n\n"))
            assertTrue(readme.contains("A Python project managed by pypackpack."))
            assertTrue(readme.contains("Use ppp to manage dependencies, targets, and builds."))

            val license = File(tempRoot, "LICENSE").readText()
            assertTrue(license.contains("All rights reserved."))
            assertTrue(license.contains("demo_pkg"))
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    private fun DefaultInterface.withBackend(backend: BaseInterface): DefaultInterface {
        DefaultInterface::class.java
            .getDeclaredField("backend")
            .apply { isAccessible = true }
            .set(this, backend)
        return this
    }

    private class FakeBackend : BaseInterface {
        override fun initialize() = Unit

        override suspend fun getVersion(): Result<String> = Result.success("uv 0.0.0")

        override suspend fun isToolInstalled(): Boolean = true

        override suspend fun installTool(): Result<String> = Result.success("ok")

        override suspend fun createVirtualEnvironment(
            path: String,
            pythonVersion: String?,
            extraArgs: Map<String, String>?,
            workingDir: File?,
        ): Result<String> = Result.success("ok")

        override suspend fun initProject(
            path: String?,
            extraArgs: Map<String, String>?,
            workingDir: File?,
        ): Result<String> {
            val projectDir = path?.let { File(it) } ?: workingDir ?: return Result.failure(IllegalArgumentException("path required"))
            projectDir.mkdirs()
            File(projectDir, "pyproject.toml").writeText("[project]\nname = \"${projectDir.name}\"\n")
            return Result.success("ok")
        }

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
        ): Result<String> = Result.success("ok")

        override suspend fun showDependencyTree(
            packageName: String?,
            extraArgs: Map<String, String>?,
            workingDir: File?,
        ): Result<String> = Result.success("ok")

        override suspend fun lockDependencies(projectRoot: String): Result<String> = Result.success("ok")

        override suspend fun listPython(): Result<String> = Result.success("ok")

        override suspend fun findPython(pythonVersion: String): Result<String> = Result.success("ok")

        override suspend fun installPython(pythonVersion: String): Result<String> = Result.success("ok")

        override suspend fun uninstallPython(pythonVersion: String): Result<String> = Result.success("ok")
    }
}
