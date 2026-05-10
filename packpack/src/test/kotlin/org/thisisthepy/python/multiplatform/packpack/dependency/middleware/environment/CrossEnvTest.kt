package org.thisisthepy.python.multiplatform.packpack.dependency.middleware.environment

import kotlinx.coroutines.runBlocking
import org.thisisthepy.python.multiplatform.packpack.dependency.backend.BaseInterface
import java.io.File
import java.nio.file.Files
import kotlin.test.Test

class CrossEnvTest {
    @Test
    fun printResolvedPackageSpecContents() {
        withWorkspace(
            """
            [project]
            name = "root"

            [tool.uv.workspace]
            members = ["src/core", "packages/utils"]
            """.trimIndent(),
        ) {
            val crossEnv = CrossEnv()
            crossEnv.initialize(FakeBackend())

            val resolver =
                CrossEnv::class.java
                    .getDeclaredMethod("resolvePackageSpec", String::class.java, File::class.java, Boolean::class.javaPrimitiveType)
                    .apply { isAccessible = true }

            listOf("core", "src/core", "packages/utils").forEach { packageInput ->
                val packageSpec = resolver.invoke(crossEnv, packageInput, null, true)
                println("packageInput=$packageInput")
                println(packageSpec)
            }
        }
    }

    private fun withWorkspace(
        pyprojectContent: String,
        block: (File) -> Unit,
    ) {
        val testWorkDir = File("build/tmp/crossenv-test")
        testWorkDir.mkdirs()
        val tempRoot = Files.createTempDirectory(testWorkDir.toPath(), "workspace-").toFile()
        val oldUserDir = System.getProperty("user.dir")
        try {
            File(tempRoot, "pyproject.toml").writeText(pyprojectContent)
            System.setProperty("user.dir", tempRoot.absolutePath)
            block(tempRoot)
        } finally {
            System.setProperty("user.dir", oldUserDir)
            tempRoot.deleteRecursively()
        }
    }

    private class FakeBackend : BaseInterface {
        companion object {
            var lastInitPath: String? = null
            var lastInitExtraArgs: Map<String, String> = emptyMap()
        }

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
            lastInitPath = path
            lastInitExtraArgs = extraArgs ?: emptyMap()
            val directory = extraArgs?.get("directory")
            val packageDir =
                when {
                    !path.isNullOrBlank() && workingDir != null -> File(workingDir, path)
                    !path.isNullOrBlank() -> File(path)
                    !directory.isNullOrBlank() -> File(directory)
                    workingDir != null -> workingDir
                    else -> return Result.failure(IllegalArgumentException("path or working dir required"))
                }
            packageDir.mkdirs()
            File(packageDir, "pyproject.toml").writeText("[project]\nname = \"${packageDir.name}\"\n")
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
