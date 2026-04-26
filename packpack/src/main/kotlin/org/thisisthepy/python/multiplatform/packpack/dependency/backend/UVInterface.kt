package org.thisisthepy.python.multiplatform.packpack.dependency.backend

import org.thisisthepy.python.multiplatform.packpack.dependency.backend.external.UV
import java.io.File

/** UV implementation of backend interface */
open class UVInterface(
    private val uv: UV = UV(),
) : BaseInterface {
    companion object {
        private const val WORKING_DIR_KEY = "__working_dir"

        private val ADD_ALLOWED_OPTIONS =
            setOf(
                "dev",
                "editable",
                "no-sync",
                "upgrade",
                "reinstall",
                "refresh",
                "raw-sources",
                "quiet",
                "verbose",
                "frozen",
                "locked",
                "preview",
                "package",
                "marker",
            )

        private val REMOVE_ALLOWED_OPTIONS =
            setOf(
                "dev",
                "no-sync",
                "quiet",
                "verbose",
                "frozen",
                "locked",
                "preview",
                "package",
                "marker",
            )

        private val SYNC_ALLOWED_OPTIONS =
            setOf(
                "no-sync",
                "quiet",
                "verbose",
                "frozen",
                "locked",
                "preview",
                "package",
            )

        private val TREE_ALLOWED_OPTIONS =
            setOf(
                "quiet",
                "verbose",
                "frozen",
                "locked",
                "preview",
                "package",
                "python-platform",
            )

        private val INIT_ALLOWED_OPTIONS =
            setOf(
                "bare",
                "name",
                "package",
                "python",
                "directory",
            )
    }

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
            executeCommand(listOf("--version")).getOrThrow()
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
    ): Result<String> =
        runCatching {
            require(path.isNotBlank()) { "Path cannot be blank" }
            val allowedOptions = setOf("python")
            val (options, workingDir) = normalizeExtraArgs(extraArgs, allowedOptions)
            val command = mutableListOf("venv")

            appendOptions(command, options)

            if (!pythonVersion.isNullOrBlank() && options["python"].isNullOrBlank()) {
                command.add("--python")
                command.add(pythonVersion)
            }

            command.add(path)
            executeCommand(command, workingDir).getOrThrow()
        }

    override suspend fun initProject(
        path: String?,
        extraArgs: Map<String, String>?,
    ): Result<String> {
        val (options, workingDir) = normalizeExtraArgs(extraArgs, INIT_ALLOWED_OPTIONS)
        val command = mutableListOf("init")
        path?.takeIf { it.isNotEmpty() }?.let { command.add(it) }

        options.forEach { (key, value) ->
            command.add("--$key")
            if (value.isNotEmpty()) {
                command.add(value)
            }
        }

        return executeCommand(command, workingDir)
    }

    /** Add dependencies */
    override suspend fun addDependencies(
        packageName: String?,
        dependencies: List<String>,
        extraArgs: Map<String, String>?,
    ): Result<String> =
        runCatching {
            require(dependencies.isNotEmpty()) { "No dependencies specified" }
            val (options, workingDir) = normalizeExtraArgs(extraArgs, ADD_ALLOWED_OPTIONS)
            val command = mutableListOf("add")
            if (!packageName.isNullOrBlank() && options["package"].isNullOrBlank()) {
                command.add("--package")
                command.add(packageName)
            }
            appendOptions(command, options)
            command.addAll(dependencies)

            return executeCommand(command, workingDir)
        }

    /** Uninstall dependencies */
    override suspend fun removeDependencies(
        packageName: String?,
        dependencies: List<String>,
        extraArgs: Map<String, String>?,
    ): Result<String> =
        runCatching {
            require(dependencies.isNotEmpty()) { "No dependencies specified" }
            val (options, workingDir) = normalizeExtraArgs(extraArgs, REMOVE_ALLOWED_OPTIONS)
            val command = mutableListOf("remove")
            if (!packageName.isNullOrBlank() && options["package"].isNullOrBlank()) {
                command.add("--package")
                command.add(packageName)
            }
            appendOptions(command, options)
            command.addAll(dependencies)

            return executeCommand(command, workingDir)
        }

    /** Synchronize dependencies */
    override suspend fun syncDependencies(
        venvPath: String,
        extraArgs: Map<String, String>?,
    ): Result<String> =
        runCatching {
            val (options, workingDir) = normalizeExtraArgs(extraArgs, SYNC_ALLOWED_OPTIONS)
            val command = mutableListOf("sync")
            appendOptions(command, options)

            return executeCommand(command, workingDir)
        }

    /** Show dependency tree */
    override suspend fun showDependencyTree(
        packageName: String?,
        extraArgs: Map<String, String>?,
    ): Result<String> =
        runCatching {
            val (options, workingDir) = normalizeExtraArgs(extraArgs, TREE_ALLOWED_OPTIONS)
            val command = mutableListOf("tree")
            if (!packageName.isNullOrBlank() && options["package"].isNullOrBlank()) {
                command.add("--package")
                command.add(packageName)
            }
            appendOptions(command, options)

            return executeCommand(command, workingDir)
        }

    /** Lock dependencies */
    override suspend fun lockDependencies(projectRoot: String): Result<String> =
        runCatching {
            val command = mutableListOf("lock")
            val workingDir = projectRoot.takeIf { it.isNotBlank() }?.let { File(it) }

            return executeCommand(command, workingDir)
        }

    /** List available Python versions */
    override suspend fun listPython(): Result<String> = executeCommand(listOf("python", "list"))

    /** Find a specific Python version */
    override suspend fun findPython(pythonVersion: String): Result<String> = executeCommand(listOf("python", "find", pythonVersion))

    /** Install a specific Python version */
    override suspend fun installPython(pythonVersion: String): Result<String> = executeCommand(listOf("python", "install", pythonVersion))

    /** Uninstall a specific Python version */
    override suspend fun uninstallPython(pythonVersion: String): Result<String> =
        executeCommand(listOf("python", "uninstall", pythonVersion))

    /** ExecuteCommand to use UV class */
    open suspend fun executeCommand(
        command: List<String>,
        workingDir: File? = null,
    ): Result<String> =
        runCatching {
            ensureToolInstalled()
            val (exitCode, output) = uv.executeCommand(command, workingDir)
            if (exitCode == 0) {
                output
            } else {
                throw Exception(output)
            }
        }

    private suspend fun ensureToolInstalled() {
        if (!uv.isInstalled()) {
            val success = uv.ensureInstalled()
            require(success) { "Failed to install UV" }
        }
    }

    private fun normalizeExtraArgs(
        extraArgs: Map<String, String>?,
        allowedOptions: Set<String>,
    ): Pair<Map<String, String>, File?> {
        if (extraArgs.isNullOrEmpty()) {
            return emptyMap<String, String>() to null
        }

        val workingDir =
            extraArgs[WORKING_DIR_KEY]
                ?.takeIf { it.isNotBlank() }
                ?.let { File(it) }

        val options = extraArgs.filterKeys { it != WORKING_DIR_KEY }
        val unsupported = options.keys.filter { it !in allowedOptions }
        require(unsupported.isEmpty()) {
            "Unsupported uv options: ${unsupported.joinToString(", ")}"
        }

        return options to workingDir
    }

    private fun appendOptions(
        command: MutableList<String>,
        options: Map<String, String>,
    ) {
        for ((key, value) in options) {
            command.add("--$key")
            if (value.isNotEmpty()) {
                command.add(value)
            }
        }
    }
}
