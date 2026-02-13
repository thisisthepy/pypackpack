package org.thisisthepy.python.multiplatform.packpack.dependency.middleware

import kotlinx.coroutines.runBlocking
import org.thisisthepy.python.multiplatform.packpack.dependency.backend.BackendType
import org.thisisthepy.python.multiplatform.packpack.dependency.middleware.environment.CrossEnv
import org.thisisthepy.python.multiplatform.packpack.dependency.middleware.environment.DevEnv
import org.thisisthepy.python.multiplatform.packpack.utils.Platforms
import org.thisisthepy.python.multiplatform.packpack.utils.toml.TomlEditor
import org.thisisthepy.python.multiplatform.packpack.utils.toml.TomlValue
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

    /** Get backend interface */
    override fun getBackend(): BackendBaseInterface {
        if (!::backend.isInitialized) {
            initialize()
        }
        return backend
    }

    /** Get development environment */
    override fun getDevEnv(): DevEnv {
        if (!::devEnv.isInitialized) {
            devEnv = DevEnv()
            devEnv.initialize(backend)
        }
        return devEnv
    }

    /** Get cross-platform environment */
    override fun getCrossEnv(): CrossEnv {
        if (!::crossEnv.isInitialized) {
            crossEnv = CrossEnv()
            crossEnv.initialize(backend)
        }
        return crossEnv
    }

    /** Init Project */
    override fun initProject(
        path: String?,
        targets: List<String>?,
        extraArgs: Map<String, String>?,
    ): Result<String> {
        val result =
            runBlocking {
                backend.initProject(path, extraArgs)
            }

        val targetPlatforms: List<String>? = targets
        val pyprojectTomlPath: String = File(path, "pyproject.toml").absolutePath
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
    ): Boolean = true

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
    ): Boolean = true

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
    ): Boolean = true

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
    ): Boolean = true

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

            // Prepare package directory path
            val packageDir = File(parentDir, packageName)
            if (packageDir.exists()) {
                throw IllegalArgumentException("Package directory already exists: ${packageDir.absolutePath}")
            }

            // Execute UV init with --package flag
            val uvExtraArgs = mutableMapOf<String, String>()
            uvExtraArgs["package"] = "" // --package flag (no value)
            uvExtraArgs["name"] = packageName

            // Merge user's extra args
            extraArgs?.forEach { (key, value) ->
                uvExtraArgs[key] = value
            }

            // Call backend to create package
            val result =
                runBlocking {
                    backend.initProject(
                        path = packageDir.absolutePath,
                        extraArgs = uvExtraArgs,
                    )
                }

            // UV automatically adds to tool.uv.workspace.members
            if (result.isFailure) {
                throw result.exceptionOrNull() ?: Exception("Failed to create package")
            }

            val output = result.getOrThrow()
            "Package '$packageName' created successfully at ${packageDir.absolutePath}\n$output"
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

            // Check package directory exists
            val packageDir = File(parentDir, packageName)
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
                    packageName,
                )

                workspacePyprojectPath.writeText(editor.toTomlString())
            }

            "Package '$packageName' removed successfully from ${parentDir.absolutePath}"
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
        // Validate all platforms first
        platforms.forEach { platform ->
            require(platform in Platforms.SUPPORTED_TARGETS) {
                "Unsupported platform: $platform. Must be one of ${Platforms.SUPPORTED_TARGETS}"
            }
        }

        // Create table if it doesn't exist
        if (!tomlEditor.hasTable(tablePath)) {
            tomlEditor.createTable(tablePath)
        }

        // Add to array with platform sorter
        tomlEditor.addToArray(
            tablePath = tablePath,
            key = "platforms",
            items = *platforms.toTypedArray(),
            sorter = { Platforms.sort(it) },
        )
    }

    private fun removePlatforms(
        tomlEditor: TomlEditor,
        platforms: List<String>,
    ) {
        val tablePath = "tool.ppp.dependencies"

        // Validate all platforms first
        platforms.forEach { platform ->
            require(platform in Platforms.SUPPORTED_TARGETS) {
                "Unsupported platform: $platform. Must be one of ${Platforms.SUPPORTED_TARGETS}"
            }
        }

        // Remove from array (silently does nothing if table/key doesn't exist)
        if (tomlEditor.hasTable(tablePath)) {
            tomlEditor.removeFromArray(
                tablePath = tablePath,
                key = "platforms",
                items = *platforms.toTypedArray(),
            )
        }
    }
}
