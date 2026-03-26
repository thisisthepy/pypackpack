package org.thisisthepy.python.multiplatform.packpack.dependency.middleware

import kotlinx.coroutines.runBlocking
import org.thisisthepy.python.multiplatform.packpack.dependency.backend.BackendType
import org.thisisthepy.python.multiplatform.packpack.dependency.middleware.environment.CrossEnv
import org.thisisthepy.python.multiplatform.packpack.dependency.middleware.environment.DevEnv
import org.thisisthepy.python.multiplatform.packpack.dependency.middleware.internal.WorkspacePaths
import org.thisisthepy.python.multiplatform.packpack.utils.Platforms
import org.thisisthepy.python.multiplatform.packpack.utils.toml.TomlEditor
import java.io.File
import org.thisisthepy.python.multiplatform.packpack.dependency.backend.BaseInterface as BackendBaseInterface

/** Default middleware implementation */
class DefaultInterface : BaseInterface {
    private lateinit var backend: BackendBaseInterface
    private lateinit var devEnv: DevEnv
    private lateinit var crossEnv: CrossEnv

    /** Initialize middleware */
    override fun initialize() {
        if (!::backend.isInitialized) {
            backend = BackendBaseInterface.create(BackendType.UV)
            backend.initialize()
        }
    }

    override fun getToolVersion(): Result<String> =
        runBlocking {
            backendInterface().getVersion()
        }

    override fun changePythonVersion(pythonVersion: String): Boolean = devEnvService().changePythonVersion(pythonVersion)

    override fun listPythonVersions(): Boolean = devEnvService().listPythonVersions()

    override fun findPythonVersion(pythonVersion: String): Boolean = devEnvService().findPythonVersion(pythonVersion)

    override fun installPythonVersion(pythonVersion: String): Boolean = devEnvService().installPythonVersion(pythonVersion)

    override fun uninstallPythonVersion(pythonVersion: String): Boolean = devEnvService().uninstallPythonVersion(pythonVersion)

    private fun backendInterface(): BackendBaseInterface {
        if (!::backend.isInitialized) {
            initialize()
        }
        return backend
    }

    private fun devEnvService(): DevEnv {
        if (!::devEnv.isInitialized) {
            devEnv = DevEnv()
            devEnv.initialize(backendInterface())
        }
        return devEnv
    }

    private fun crossEnvService(): CrossEnv {
        if (!::crossEnv.isInitialized) {
            crossEnv = CrossEnv()
            crossEnv.initialize(backendInterface())
        }
        return crossEnv
    }

    /** Init Project */
    override fun initProject(
        path: String?,
        targets: List<String>?,
        extraArgs: Map<String, String>?,
    ): Result<String> {
        val uvArgs = (extraArgs?.toMutableMap() ?: mutableMapOf()).apply {
            putIfAbsent("no-workspace", "")
        }
        val result =
            runBlocking {
                backend.initProject(path, uvArgs)
            }

        val targetPlatforms: List<String>? = targets
        val projectDir = path?.let { File(it) } ?: File(System.getProperty("user.dir"))
        val pyprojectTomlPath: String = File(projectDir, "pyproject.toml").absolutePath
        val pyprojectTomlContent = File(pyprojectTomlPath).readText()
        val editor = TomlEditor(pyprojectTomlContent)

        if (!targetPlatforms.isNullOrEmpty()) {
            addPlatforms(editor, targetPlatforms)
        }

        File(pyprojectTomlPath).writeText(editor.toTomlString())

        return result
    }

    /**
     * Add dependencies to a package
     * @param packageName Package name (optional)
     * @param dependencies List of dependencies to add
     * @param targets List of target platforms (optional)
     * @param extraArgs Extra arguments (optional)
     * @return Success status
     */
    override fun addDependencies(
        packageName: String?,
        dependencies: List<String>,
        targets: List<String>?,
        extraArgs: Map<String, String>?,
    ): Boolean {
        if (dependencies.isEmpty()) {
            println("No dependencies specified")
            return false
        }
        if (dependencies.any { it.contains(';') }) {
            println("Dependency markers in package names are not allowed. Use --target instead.")
            return false
        }

        return if (packageName.isNullOrBlank()) {
            val mergedArgs = withWorkingDir(extraArgs, findProjectRoot())
            devEnvService().addDependencies(dependencies, mergedArgs.ifEmpty { null })
        } else {
            crossEnvService()
                .addDependencies(packageName, dependencies, targets, extraArgs)
                .onFailure {
                    println("Failed to add dependencies for package '$packageName': ${it.message}")
                }.isSuccess
        }
    }

    /**
     * Remove dependencies from a package
     * @param packageName Package name (optional)
     * @param dependencies List of dependencies to remove
     * @param targets List of target platforms (optional)
     * @param extraArgs Extra arguments (optional)
     * @return Success status
     */
    override fun removeDependencies(
        packageName: String?,
        dependencies: List<String>,
        targets: List<String>?,
        extraArgs: Map<String, String>?,
    ): Boolean {
        if (dependencies.isEmpty()) {
            println("No dependencies specified")
            return false
        }
        if (dependencies.any { it.contains(';') }) {
            println("Dependency markers in package names are not allowed. Use --target instead.")
            return false
        }

        return if (packageName.isNullOrBlank()) {
            val mergedArgs = withWorkingDir(extraArgs, findProjectRoot())
            devEnvService().removeDependencies(dependencies, mergedArgs.ifEmpty { null })
        } else {
            crossEnvService()
                .removeDependencies(packageName, dependencies, targets, extraArgs)
                .onFailure {
                    println("Failed to remove dependencies for package '$packageName': ${it.message}")
                }.isSuccess
        }
    }

    /**
     * Synchronize dependencies for a package
     * @param packageName Package name (optional)
     * @param targets List of target platforms (optional)
     * @param extraArgs Extra arguments (optional)
     * @return Success status
     */
    override fun syncDependencies(
        packageName: String?,
        targets: List<String>?,
        extraArgs: Map<String, String>?,
    ): Boolean {
        if (packageName.isNullOrBlank()) {
            val mergedArgs = withWorkingDir(extraArgs, findProjectRoot())
            return devEnvService().syncDependencies(mergedArgs.ifEmpty { null })
        }

        return crossEnvService()
            .syncDependencies(packageName, targets, extraArgs)
            .onFailure {
                println("Failed to sync package '$packageName': ${it.message}")
            }.isSuccess
    }

    /**
     * Show dependency tree for a package
     * @param packageName Package name (optional)
     * @param targets List of target platforms (optional)
     * @param extraArgs Extra arguments (optional)
     * @return Success status
     */
    override fun showDependencyTree(
        packageName: String?,
        targets: List<String>?,
        extraArgs: Map<String, String>?,
    ): Boolean {
        if (!packageName.isNullOrBlank()) {
            return crossEnvService()
                .showDependencyTree(packageName, targets, extraArgs)
                .onSuccess { println(it) }
                .onFailure {
                    println("Failed to show dependency tree for package '$packageName': ${it.message}")
                }.isSuccess
        }

        val normalizedTargets = normalizeTreeTargets(targets)

        return runCatching {
            val baseDir = findProjectRoot()
            val workingArgs = withWorkingDir(extraArgs, baseDir)

            for (target in normalizedTargets) {
                val callArgs = workingArgs + mapOf("python-platform" to target)
                val result =
                    runBlocking {
                        backend.showDependencyTree(null, callArgs)
                    }
                println(result.getOrThrow())
            }
        }.onFailure {
            println("Failed to show dependency tree: ${it.message}")
        }.isSuccess
    }

    /** Add a new package to the workspace */
    override fun addPackage(
        packageName: String,
        path: String?,
        extraArgs: Map<String, String>?,
    ): Result<String> =
        runCatching {
            // Resolve parent directory (where workspace pyproject.toml is)
            val parentDir = path?.let { File(it) } ?: File(System.getProperty("user.dir"))
            if (!parentDir.exists() || !parentDir.isDirectory) {
                throw IllegalArgumentException("Invalid path: ${parentDir.absolutePath}")
            }

            // Check workspace pyproject.toml exists
            val workspacePyprojectPath = File(parentDir, "pyproject.toml")
            if (!workspacePyprojectPath.exists()) {
                throw IllegalStateException("No pyproject.toml found in ${parentDir.absolutePath}. Initialize a project first.")
            }

            val packageSpec = resolvePackageSpec(parentDir, packageName)
            val packageDir = packageSpec.directory
            if (packageDir.exists()) {
                throw IllegalArgumentException("Package directory already exists: ${packageDir.absolutePath}")
            }
            require(packageDir.mkdirs()) {
                "Failed to create package directory: ${packageDir.absolutePath}"
            }

            // Execute UV init with --package flag
            val uvExtraArgs = mutableMapOf<String, String>()
            uvExtraArgs["package"] = "" // --package flag (no value)
            uvExtraArgs["name"] = packageSpec.name
            uvExtraArgs["no-workspace"] = ""
            uvExtraArgs["__working_dir"] = packageDir.absolutePath

            // Merge user's extra args
            extraArgs?.forEach { (key, value) ->
                uvExtraArgs[key] = value
            }

            // Call backend to create package
            val result =
                runBlocking {
                    backend.initProject(
                        path = null,
                        extraArgs = uvExtraArgs,
                    )
                }

            if (result.isFailure) {
                throw result.exceptionOrNull() ?: Exception("Failed to create package")
            }

            val pyprojectContent = workspacePyprojectPath.readText()
            val editor = TomlEditor(pyprojectContent)
            val workspaceTablePath = "tool.uv.workspace"
            if (!editor.hasTable(workspaceTablePath)) {
                editor.createTable(workspaceTablePath)
            }
            editor.addToArray(
                tablePath = workspaceTablePath,
                key = "members",
                packageSpec.relativePath,
            )
            workspacePyprojectPath.writeText(editor.toTomlString())

            val output = result.getOrThrow()
            "Package '${packageSpec.input}' created successfully at ${packageDir.absolutePath}\n$output"
        }

    /** Remove a package from the workspace */
    override fun removePackage(
        packageName: String,
        path: String?,
    ): Result<String> =
        runCatching {
            // Resolve parent directory (where workspace pyproject.toml is)
            val parentDir = path?.let { File(it) } ?: File(System.getProperty("user.dir"))
            if (!parentDir.exists() || !parentDir.isDirectory) {
                throw IllegalArgumentException("Invalid path: ${parentDir.absolutePath}")
            }

            // Check workspace pyproject.toml exists
            val workspacePyprojectPath = File(parentDir, "pyproject.toml")
            if (!workspacePyprojectPath.exists()) {
                throw IllegalStateException("No pyproject.toml found in ${parentDir.absolutePath}")
            }

            val packageSpec = resolvePackageSpec(parentDir, packageName)
            val packageDir = packageSpec.directory
            if (!packageDir.exists()) {
                throw IllegalArgumentException("Package directory not found: ${packageDir.absolutePath}")
            }

            // Delete package directory recursively
            val deleteSuccess = packageDir.deleteRecursively()
            if (!deleteSuccess) {
                throw Exception("Failed to delete package directory: ${packageDir.absolutePath}")
            }

            // Update workspace pyproject.toml to remove from tool.uv.workspace.members
            val pyprojectContent = workspacePyprojectPath.readText()
            val editor = TomlEditor(pyprojectContent)

            val workspaceTablePath = "tool.uv.workspace"
            if (editor.hasTable(workspaceTablePath)) {
                editor.removeFromArray(
                    tablePath = workspaceTablePath,
                    key = "members",
                    packageSpec.relativePath,
                )

                workspacePyprojectPath.writeText(editor.toTomlString())
            }

            "Package '${packageSpec.input}' removed successfully from ${parentDir.absolutePath}"
        }

    private data class PackageSpec(
        val input: String,
        val name: String,
        val relativePath: String,
        val directory: File,
    )

    private fun resolvePackageSpec(
        parentDir: File,
        packageInput: String,
    ): PackageSpec {
        val trimmed = packageInput.trim()
        require(trimmed.isNotEmpty()) { "Package name cannot be blank" }

        val relativePath =
            trimmed
                .replace('\\', '/')
                .trimStart('/')

        require(relativePath.isNotEmpty()) { "Package name cannot be blank" }

        val packageDir = File(parentDir, relativePath).normalize()
        val parentPath = parentDir.canonicalFile.toPath()
        val packagePath = packageDir.canonicalFile.toPath()
        require(packagePath.startsWith(parentPath)) {
            "Package path must stay within workspace: $trimmed"
        }

        val canonicalName = packageDir.name
        require(canonicalName.isNotBlank()) { "Package name cannot be blank" }

        return PackageSpec(
            input = trimmed,
            name = canonicalName,
            relativePath = relativePath,
            directory = packageDir,
        )
    }

    /** Add target platforms to a package */
    override fun addTargets(
        targets: List<String>,
        path: String?,
    ): Result<String> =
        runCatching {
            // Resolve package directory
            val packageDir = path?.let { File(it) } ?: File(System.getProperty("user.dir"))
            if (!packageDir.exists() || !packageDir.isDirectory) {
                throw IllegalArgumentException("Invalid path: ${packageDir.absolutePath}")
            }

            // Check pyproject.toml exists
            val pyprojectPath = File(packageDir, "pyproject.toml")
            if (!pyprojectPath.exists()) {
                throw IllegalStateException("No pyproject.toml found in ${packageDir.absolutePath}")
            }

            // Load and edit pyproject.toml
            val pyprojectContent = pyprojectPath.readText()
            val editor = TomlEditor(pyprojectContent)

            // Use existing private helper method
            addPlatforms(editor, targets)

            // Save changes
            pyprojectPath.writeText(editor.toTomlString())

            val sortedTargets = Platforms.sort(targets)
            "Successfully added targets: ${sortedTargets.joinToString(", ")}"
        }

    /** Remove target platforms from a package */
    override fun removeTargets(
        targets: List<String>,
        path: String?,
    ): Result<String> =
        runCatching {
            // Resolve package directory
            val packageDir = path?.let { File(it) } ?: File(System.getProperty("user.dir"))
            if (!packageDir.exists() || !packageDir.isDirectory) {
                throw IllegalArgumentException("Invalid path: ${packageDir.absolutePath}")
            }

            // Check pyproject.toml exists
            val pyprojectPath = File(packageDir, "pyproject.toml")
            if (!pyprojectPath.exists()) {
                throw IllegalStateException("No pyproject.toml found in ${packageDir.absolutePath}")
            }

            // Load and edit pyproject.toml
            val pyprojectContent = pyprojectPath.readText()
            val editor = TomlEditor(pyprojectContent)

            // Use existing private helper method
            removePlatforms(editor, targets)

            // Save changes
            pyprojectPath.writeText(editor.toTomlString())

            "Successfully removed targets: ${targets.joinToString(", ")}"
        }

    private fun addPlatforms(
        tomlEditor: TomlEditor,
        platforms: List<String>,
    ) {
        val tablePath = "tool.ppp.dependencies"
        val normalizedPlatforms = Platforms.normalizeTargetsOrThrow(platforms, label = "platform")

        // Create table if it doesn't exist
        if (!tomlEditor.hasTable(tablePath)) {
            tomlEditor.createTable(tablePath)
        }

        // Add to array with platform sorter
        tomlEditor.addToArray(
            tablePath = tablePath,
            key = "platforms",
            *normalizedPlatforms.toTypedArray(),
            sorter = { Platforms.sort(it) },
        )
    }

    private fun removePlatforms(
        tomlEditor: TomlEditor,
        platforms: List<String>,
    ) {
        val tablePath = "tool.ppp.dependencies"
        val normalizedPlatforms = Platforms.normalizeTargetsOrThrow(platforms, label = "platform")

        // Remove from array (silently does nothing if table/key doesn't exist)
        if (tomlEditor.hasTable(tablePath)) {
            tomlEditor.removeFromArray(
                tablePath = tablePath,
                key = "platforms",
                *normalizedPlatforms.toTypedArray(),
            )
        }
    }

    private fun findProjectRoot(): File? = WorkspacePaths.findProjectRoot()

    private fun normalizeTreeTargets(targets: List<String>?): List<String> {
        val defaults = listOf(Platforms.detectHostTarget())
        return Platforms.normalizeTargetsOrThrow(targets, defaultTargets = defaults)
    }

    private fun withWorkingDir(
        extraArgs: Map<String, String>?,
        directory: File?,
    ): Map<String, String> {
        val base = extraArgs?.toMutableMap() ?: mutableMapOf()
        directory?.let { base["__working_dir"] = it.absolutePath }
        return base
    }
}

internal object MarkerPolicy {
    fun markerForTarget(target: String): String {
        val system = mapTargetToPlatformSystem(target)
        val machine = target.substringBefore('-').replace("aarch64", "arm64")
        return "platform_system == '$system' and platform_machine == '$machine'"
    }

    private fun mapTargetToPlatformSystem(target: String): String =
        when {
            target.contains("windows") -> "Windows"
            target.contains("android") -> "Android"
            target.contains("apple-ios") -> "iOS"
            target.startsWith("wasm32") || target.contains("pyodide") || target.contains("emscripten") -> "Emscripten"
            target.contains("apple-darwin") -> "Darwin"
            target.contains("linux") || target.contains("manylinux") -> "Linux"
            else -> throw IllegalArgumentException("Unsupported target for marker mapping: $target")
        }
}
