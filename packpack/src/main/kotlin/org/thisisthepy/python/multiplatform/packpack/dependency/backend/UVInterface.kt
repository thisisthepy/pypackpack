package org.thisisthepy.python.multiplatform.packpack.dependency.backend

import org.thisisthepy.python.multiplatform.packpack.dependency.backend.external.UV
import java.io.File

/** UV implementation of backend interface */
class UVInterface : BaseInterface {
    private val uv = UV()

    /** Initialize UV backend */
    override fun initialize() {
        // UV initialization is handled by the UV class
    }

    /** Get backend tool version */
    override suspend fun getVersion(): Result<String> =
        runCatching {
            if (!isToolInstalled()) {
                installTool().getOrThrow()
            }
            val (exitCode, output) = uv.executeCommand(listOf("--version"))
            if (exitCode == 0) {
                output
            } else {
                throw Exception(output)
            }
        }

    /** Check if UV is installed */
    override suspend fun isToolInstalled(): Boolean = uv.isInstalled()

    /** Install UV */
    override suspend fun installTool(): Result<String> =
        runCatching {
            val success = uv.ensureInstalled()
            if (success) {
                "UV installed successfully"
            } else {
                throw Exception("Failed to install UV")
            }
        }

    override suspend fun createVirtualEnvironment(
        path: String,
        pythonVersion: String?,
        extraArgs: Map<String, String>?,
    ): Result<String> = Result.success("Not implemented yet")

    override suspend fun initProject(
        path: String?,
        extraArgs: Map<String, String>?,
    ): Result<String> {
        val command = mutableListOf("init")
        path?.takeIf { it.isNotEmpty() }?.let { command.add(it) }

        extraArgs?.forEach { (key, value) ->
            command.add("--$key")
            if (value.isNotEmpty()) {
                command.add(value)
            }
        }

        return executeCommand(command)
    }

    /** Add dependencies */
    override suspend fun addDependencies(
        packageName: String?,
        dependencies: List<String>,
        extraArgs: Map<String, String>?,
    ): Result<String> =
        runCatching {
            val command = mutableListOf("add")

            return executeCommand(command)
        }

    /** Uninstall dependencies */
    override suspend fun removeDependencies(
        packageName: String?,
        dependencies: List<String>,
        extraArgs: Map<String, String>?,
    ): Result<String> =
        runCatching {
            val command = mutableListOf("remove")

            return executeCommand(command)
        }

    /** Synchronize dependencies */
    override suspend fun syncDependencies(
        venvPath: String,
        extraArgs: Map<String, String>?,
    ): Result<String> =
        runCatching {
            val command = mutableListOf("sync")

            return executeCommand(command)
        }

    /** Show dependency tree */
    override suspend fun showDependencyTree(
        packageName: String?,
        extraArgs: Map<String, String>?,
    ): Result<String> =
        runCatching {
            val command = mutableListOf("tree")

            return executeCommand(command)
        }

    /** Lock dependencies */
    override suspend fun lockDependencies(projectRoot: String): Result<String> =
        runCatching {
            val command = mutableListOf("lock")

            return executeCommand(command)
        }

    /** List available Python versions */
    override suspend fun listPython(): Result<String> =
        runCatching {
            val command = listOf("python", "list")

            val (exitCode, output) = uv.executeCommand(command)
            if (exitCode == 0) {
                output
            } else {
                throw Exception(output)
            }
        }

    /** Find a specific Python version */
    override suspend fun findPython(pythonVersion: String): Result<String> =
        runCatching {
            val command = listOf("python", "find", pythonVersion)

            val (exitCode, output) = uv.executeCommand(command)
            if (exitCode == 0) {
                output
            } else {
                throw Exception(output)
            }
        }

    /** Install a specific Python version */
    override suspend fun installPython(pythonVersion: String): Result<String> =
        runCatching {
            val command = listOf("python", "install", pythonVersion)

            val (exitCode, output) = uv.executeCommand(command)
            if (exitCode == 0) {
                output
            } else {
                throw Exception(output)
            }
        }

    /** Uninstall a specific Python version */
    override suspend fun uninstallPython(pythonVersion: String): Result<String> =
        runCatching {
            val command = listOf("python", "uninstall", pythonVersion)

            val (exitCode, output) = uv.executeCommand(command)
            if (exitCode == 0) {
                output
            } else {
                throw Exception(output)
            }
        }

    /** ExecuteCommand to use UV class */
    suspend fun executeCommand(command: List<String>): Result<String> =
        runCatching {
            val (exitCode, output) = uv.executeCommand(command)
            if (exitCode == 0) {
                output
            } else {
                throw Exception(output)
            }
        }
}
