package org.thisisthepy.python.multiplatform.packpack.dependency.middleware.environment

import kotlinx.coroutines.runBlocking
import org.thisisthepy.python.multiplatform.packpack.config.PackPackConfig
import org.thisisthepy.python.multiplatform.packpack.config.ProjectConfig
import org.thisisthepy.python.multiplatform.packpack.config.PyprojectConfig
import org.thisisthepy.python.multiplatform.packpack.config.PyprojectParser
import org.thisisthepy.python.multiplatform.packpack.config.ToolConfig
import org.thisisthepy.python.multiplatform.packpack.config.UVConfig
import org.thisisthepy.python.multiplatform.packpack.config.UVWorkspaceConfig
import org.thisisthepy.python.multiplatform.packpack.dependency.backend.BaseInterface
import org.thisisthepy.python.multiplatform.packpack.dependency.backend.UVInterface
import org.thisisthepy.python.multiplatform.packpack.util.Platforms
import java.io.File

/**
 * Cross-platform environment management Handles dependencies for specific package and target
 * platforms
 */
class CrossEnv {
    private lateinit var backend: BaseInterface
    private lateinit var devEnv: DevEnv
    private val venvPath = ".venv"
    private val pyprojectFile = "pyproject.toml"
    private val lockFile = "pyproject.lock"
    private val crossenvDir = "build/crossenv"

    /** Available target platforms */
    private fun normalizeTargetsOrFail(targets: List<String>): Pair<List<String>, List<String>> {
        val normalized = mutableListOf<String>()
        val invalid = mutableListOf<String>()
        for (target in targets) {
            val canonical = Platforms.normalizeTarget(target)
            if (canonical == null) {
                invalid.add(target)
            } else {
                normalized.add(canonical)
            }
        }
        return Pair(normalized.distinct(), invalid.distinct())
    }

    /**
     * Initialize cross-platform environment
     * @param backend Backend interface
     */
    fun initialize(backend: BaseInterface) {
        this.backend = backend
    }

    /**
     * Find project root directory
     * @return Project root directory or null if not found
     */
    private fun findProjectRoot(): File? {
        var dir = File(System.getProperty("user.dir"))
        while (dir.parentFile != null) {
            if (File(dir, pyprojectFile).exists()) {
                return dir
            }
            dir = dir.parentFile
        }
        return null
    }

    /**
     * Add a package to the project
     * @param packageName Package name
     * @return Success status
     */
    fun addPackage(packageName: String): Boolean {
        if (packageName in
            listOf(
                "init",
                "python",
                "package",
                "target",
                "add",
                "remove",
                "sync",
                "tree",
                "build",
                "bundle",
                "deploy",
            )
        ) {
            println("Package name cannot be a reserved word: $packageName")
            return false
        }

        val projectRoot =
            findProjectRoot()
                ?: run {
                    println("Project root not found. Please initialize a project first.")
                    return false
                }

        val packageDir = File(projectRoot, packageName)
        if (packageDir.exists()) {
            println("Package already exists: ${packageDir.absolutePath}")
            return false
        }

        // Create package directory
        if (!packageDir.mkdirs()) {
            println("Failed to create package directory: ${packageDir.absolutePath}")
            return false
        }

        // Create src directory structure
        val srcDir = File(packageDir, "src")
        val mainDir = File(srcDir, "main")
        val testDir = File(srcDir, "test")

        if (!mainDir.mkdirs() || !testDir.mkdirs()) {
            println("Failed to create source directories")
            return false
        }

        // Create __init__.py in main
        File(mainDir, "__init__.py").writeText("# Main package code\n")

        // Create .gitkeep in test
        File(testDir, ".gitkeep").createNewFile()

        // Create build directory structure
        val buildDir = File(packageDir, "build")
        val crossenvDir = File(buildDir, "crossenv")

        if (!crossenvDir.mkdirs()) {
            println("Failed to create build directories")
            return false
        }

        // Create package pyproject.toml
        val parser = PyprojectParser()
        val packagePyprojectFile = File(packageDir, pyprojectFile)
        val packageConfig =
            PyprojectConfig(
                project =
                    ProjectConfig(
                        name = packageName,
                        version = "0.1.0",
                        description = "A Python package managed by PyPackPack",
                        readme = "../README.md",
                        requiresPython = ">=${PackPackConfig.defaultPythonVersion}",
                    ),
            )
        try {
            parser.writeToFile(packageConfig, packagePyprojectFile)
        } catch (e: Exception) {
            println("Failed to create package pyproject.toml: ${e.message}")
            return false
        }

        // Update root pyproject.toml to include the new package
        val rootPyprojectFile = File(projectRoot, pyprojectFile)
        if (rootPyprojectFile.exists()) {
            try {
                val config =
                    parser.parseFromFile(
                        rootPyprojectFile,
                        applyDefaults = false,
                        validateConfig = false,
                    )
                val tool = config.tool ?: ToolConfig()
                val uv = tool.uv ?: UVConfig()
                val workspace = uv.workspace ?: UVWorkspaceConfig()
                val members = (workspace.members ?: emptyList()).toMutableList()
                if (!members.contains(packageName)) {
                    members.add(packageName)
                }
                val updatedUV = uv.copy(workspace = workspace.copy(members = members))
                val updatedConfig =
                    config.copy(
                        tool =
                            tool.copy(
                                uv = updatedUV,
                            ),
                    )

                parser.writeToFile(updatedConfig, rootPyprojectFile)
            } catch (e: Exception) {
                println("Failed to update root pyproject.toml: ${e.message}")
                return false
            }
        }

        // Create host platform virtual environment
        val hostPlatform = Platforms.detectHostTarget()
        val venvDir = File(crossenvDir, hostPlatform)

        return runBlocking {
            backend
                .createVirtualEnvironment(venvDir.absolutePath, null, null)
                .onSuccess {
                    println(
                        "Created package '$packageName' with host platform environment ($hostPlatform)",
                    )
                }.onFailure { error ->
                    println("Package created but failed to create virtual environment: ${error.message}")
                }.isSuccess
        }
    }

    /**
     * Remove a package from the project
     * @param packageName Package name
     * @return Success status
     */
    fun removePackage(packageName: String): Boolean {
        val projectRoot =
            findProjectRoot()
                ?: run {
                    println("Project root not found. Please initialize a project first.")
                    return false
                }

        val packageDir = File(projectRoot, packageName)
        if (!packageDir.exists() || !packageDir.isDirectory) {
            println("Package not found: $packageName")
            return false
        }

        // Check if pyproject.toml exists to confirm it's a package
        if (!File(packageDir, pyprojectFile).exists()) {
            println("Not a valid package: $packageName")
            return false
        }

        // Remove package directory
        if (!packageDir.deleteRecursively()) {
            println("Failed to delete package directory: ${packageDir.absolutePath}")
            return false
        }

        // Update root pyproject.toml to remove the package
        val rootPyprojectFile = File(projectRoot, pyprojectFile)
        if (rootPyprojectFile.exists()) {
            val parser = PyprojectParser()
            try {
                val config =
                    parser.parseFromFile(
                        rootPyprojectFile,
                        applyDefaults = false,
                        validateConfig = false,
                    )
                val tool = config.tool ?: ToolConfig()

                // Update uv
                val uv = tool.uv
                val updatedUV =
                    uv?.let { uvConfig ->
                        val ws = uvConfig.workspace
                        val members = ws?.members
                        if (members != null && members.contains(packageName)) {
                            uvConfig.copy(
                                workspace =
                                    ws.copy(
                                        members = (members - packageName).takeIf { it.isNotEmpty() },
                                    ),
                            )
                        } else {
                            uvConfig
                        }
                    }

                val updatedConfig =
                    config.copy(
                        tool =
                            tool.copy(
                                uv = updatedUV,
                            ),
                    )

                parser.writeToFile(updatedConfig, rootPyprojectFile)
            } catch (e: Exception) {
                // Fall back to simple text update for compatibility with non-standard pyproject.toml
                val rootPyproject = rootPyprojectFile.readText()
                val packageEntry = "\"$packageName\" = { path = \"./$packageName\" }"
                val updatedContent = rootPyproject.replace(packageEntry, "").replace("\n\n\n", "\n\n")
                rootPyprojectFile.writeText(updatedContent)
            }
        }

        println("Removed package: $packageName")
        return true
    }

    /**
     * Add target platform to a package
     * @param packageName Package name
     * @param targets List of target platforms
     * @return Success status
     */
    fun addTarget(
        packageName: String,
        targets: List<String>,
    ): Boolean {
        val projectRoot =
            findProjectRoot()
                ?: run {
                    println("Project root not found. Please initialize a project first.")
                    return false
                }

        val packageDir = File(projectRoot, packageName)
        if (!packageDir.exists() || !packageDir.isDirectory) {
            println("Package not found: $packageName")
            return false
        }

        // Check if pyproject.toml exists to confirm it's a package
        val packagePyproject = File(packageDir, pyprojectFile)
        if (!packagePyproject.exists()) {
            println("Not a valid package: $packageName")
            return false
        }

        val (normalizedTargets, invalidPlatforms) = normalizeTargetsOrFail(targets)
        if (invalidPlatforms.isNotEmpty()) {
            println("Invalid target platforms: ${invalidPlatforms.joinToString(", ")}")
            println("Available platforms: ${Platforms.SUPPORTED_TARGETS.joinToString(", ")}")
            return false
        }

        // Create source directories for each platform
        val srcDir = File(packageDir, "src")
        for (platform in normalizedTargets) {
            val platformDir = File(srcDir, Platforms.getPlatformFamily(platform))
            if (!platformDir.exists() && !platformDir.mkdirs()) {
                println("Failed to create source directory for platform: $platform")
                return false
            }

            // Create empty __init__.py
            File(platformDir, "__init__.py").writeText("# Platform-specific code for $platform\n")
        }

        return true
    }

    /**
     * Remove target platform from a package
     * @param packageName Package name
     * @param targets List of target platforms
     * @return Success status
     */
    fun removeTarget(
        packageName: String,
        targets: List<String>,
    ): Boolean {
        val projectRoot =
            findProjectRoot()
                ?: run {
                    println("Project root not found. Please initialize a project first.")
                    return false
                }

        val packageDir = File(projectRoot, packageName)
        if (!packageDir.exists() || !packageDir.isDirectory) {
            println("Package not found: $packageName")
            return false
        }

        // Check if pyproject.toml exists to confirm it's a package
        val packagePyproject = File(packageDir, pyprojectFile)
        if (!packagePyproject.exists()) {
            println("Not a valid package: $packageName")
            return false
        }

        val (normalizedTargets, invalidPlatforms) = normalizeTargetsOrFail(targets)
        if (invalidPlatforms.isNotEmpty()) {
            println("Invalid target platforms: ${invalidPlatforms.joinToString(", ")}")
            println("Available platforms: ${Platforms.SUPPORTED_TARGETS.joinToString(", ")}")
            return false
        }

        // Remove source directories for each platform (if empty)
        val srcDir = File(packageDir, "src")
        for (platform in normalizedTargets) {
            val platformDir = File(srcDir, Platforms.getPlatformFamily(platform))
            if (platformDir.exists() && platformDir.isDirectory) {
                // Only delete if directory contains only __init__.py or is empty
                val files = platformDir.listFiles() ?: emptyArray()
                if (files.isEmpty() || (files.size == 1 && files[0].name == "__init__.py")) {
                    if (!platformDir.deleteRecursively()) {
                        println("Warning: Failed to delete source directory for $platform")
                    }
                } else {
                    println("Warning: Source directory for $platform contains files. Not deleting.")
                }
            }
        }

        return true
    }

    /**
     * Add dependencies to a package
     * @param packageName Package name
     * @param dependencies List of dependencies to add
     * @param targets List of target platforms (optional)
     * @param extraArgs Extra arguments (optional)
     * @return Success status
     */
    fun addDependencies(
        packageName: String,
        dependencies: List<String>,
        targets: List<String>?,
        extraArgs: Map<String, String>?,
    ): Boolean {
        val projectRoot =
            findProjectRoot()
                ?: run {
                    println("Project root not found. Please initialize a project first.")
                    return false
                }

        val packageDir = File(projectRoot, packageName)
        if (!packageDir.exists() || !packageDir.isDirectory) {
            println("Package not found: $packageName")
            return false
        }

        // Check if pyproject.toml exists to confirm it's a package
        val packagePyproject = File(packageDir, pyprojectFile)
        if (!packagePyproject.exists()) {
            println("Not a valid package: $packageName")
            return false
        }

        return runBlocking {
            backend
                .addDependencies(packageName, dependencies, extraArgs)
                .onSuccess {
                    println("Added dependencies to package $packageName: ${dependencies.joinToString(", ")}")
                }.onFailure { error ->
                    println("Failed to add dependencies to package $packageName: ${error.message}")
                }.isSuccess
        }
    }

    /**
     * Remove dependencies from a package
     * @param packageName Package name
     * @param dependencies List of dependencies to remove
     * @param targets List of target platforms (optional)
     * @param extraArgs Extra arguments (optional)
     * @return Success status
     */
    fun removeDependencies(
        packageName: String,
        dependencies: List<String>,
        targets: List<String>?,
        extraArgs: Map<String, String>?,
    ): Boolean {
        val projectRoot =
            findProjectRoot()
                ?: run {
                    println("Project root not found. Please initialize a project first.")
                    return false
                }

        val packageDir = File(projectRoot, packageName)
        if (!packageDir.exists() || !packageDir.isDirectory) {
            println("Package not found: $packageName")
            return false
        }

        // Check if pyproject.toml exists to confirm it's a package
        val packagePyproject = File(packageDir, pyprojectFile)
        if (!packagePyproject.exists()) {
            println("Not a valid package: $packageName")
            return false
        }

        return runBlocking {
            backend
                .removeDependencies(packageName, dependencies, extraArgs)
                .onSuccess {
                    println("Removed dependencies from package $packageName: ${dependencies.joinToString(", ")}")
                }.onFailure { error ->
                    println("Failed to remove dependencies from package $packageName: ${error.message}")
                }.isSuccess
        }
    }

    /**
     * Synchronize dependencies for a package
     * @param packageName Package name
     * @param targets List of target platforms (optional)
     * @param extraArgs Extra arguments (optional)
     * @return Success status
     */
    fun syncDependencies(
        packageName: String,
        targets: List<String>?,
        extraArgs: Map<String, String>?,
    ): Boolean {
        val projectRoot =
            findProjectRoot()
                ?: run {
                    println("Project root not found. Please initialize a project first.")
                    return false
                }

        val packageDir = File(projectRoot, packageName)
        if (!packageDir.exists() || !packageDir.isDirectory) {
            println("Package not found: $packageName")
            return false
        }

        // Check if pyproject.toml exists to confirm it's a package
        val packagePyproject = File(packageDir, pyprojectFile)
        if (!packagePyproject.exists()) {
            println("Not a valid package: $packageName")
            return false
        }

        // Get available target platforms from the package
        val crossenvDir = File(packageDir, "build/crossenv")
        val availableTargets =
            crossenvDir.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()

        if (availableTargets.isEmpty()) {
            println("No target platforms available for package $packageName")
            return false
        }

        // Determine which targets to process
        val targetsToProcess =
            if (!targets.isNullOrEmpty()) {
                val invalidTargets = targets.filter { it !in availableTargets }
                if (invalidTargets.isNotEmpty()) {
                    println("Invalid target platforms: ${invalidTargets.joinToString(", ")}")
                    println(
                        "Available platforms for package $packageName: ${availableTargets.joinToString(", ")}",
                    )
                    return false
                }
                targets
            } else {
                availableTargets
            }

        // Sync dependencies for each target
        var success = true
        for (target in targetsToProcess) {
            val venvDir = File(crossenvDir, target)
            var failFlag = false
            if (!venvDir.exists()) {
                println("Virtual environment for target $target does not exist. Creating...")
                runBlocking {
                    backend
                        .createVirtualEnvironment(venvDir.absolutePath, null, null)
                        .onFailure { error ->
                            println("Failed to create virtual environment for $target: ${error.message}")
                            success = false
                            failFlag = true
                        }
                }
            }
            if (failFlag) {
                continue
            }

            runBlocking {
                val platform = mapOf("platform" to target)
                val args = if (extraArgs != null) extraArgs + platform else platform

                backend
                    .syncDependencies(venvDir.absolutePath, args)
                    .onSuccess {
                        println("Synchronized dependencies for target $target")
                    }.onFailure { error ->
                        println("Failed to synchronize dependencies for target $target: ${error.message}")
                        success = false
                    }
            }
        }

        return success
    }

    /**
     * Show dependency tree for a package
     * @param packageName Package name
     * @param targets List of target platforms (optional)
     * @param extraArgs Extra arguments (optional)
     * @return Success status
     */
    fun showDependencyTree(
        packageName: String,
        targets: List<String>?,
        extraArgs: Map<String, String>?,
    ): Boolean {
        val projectRoot =
            findProjectRoot()
                ?: run {
                    println("Project root not found. Please initialize a project first.")
                    return false
                }

        val packageDir = File(projectRoot, packageName)
        if (!packageDir.exists() || !packageDir.isDirectory) {
            println("Package not found: $packageName")
            return false
        }

        // Check if pyproject.toml exists to confirm it's a package
        if (!File(packageDir, pyprojectFile).exists()) {
            println("Not a valid package: $packageName")
            return false
        }

        // Get available target platforms from the package
        val crossenvDir = File(packageDir, "build/crossenv")
        val availableTargets =
            crossenvDir.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()

        if (availableTargets.isEmpty()) {
            println("No target platforms available for package $packageName")
            return false
        }

        // Determine which targets to process
        val targetsToProcess =
            if (!targets.isNullOrEmpty()) {
                val invalidTargets = targets.filter { it !in availableTargets }
                if (invalidTargets.isNotEmpty()) {
                    println("Invalid target platforms: ${invalidTargets.joinToString(", ")}")
                    println(
                        "Available platforms for package $packageName: ${availableTargets.joinToString(", ")}",
                    )
                    return false
                }
                targets
            } else {
                availableTargets
            }

        // Show dependency tree for each target
        // NOTE: Do not automatically inject a target/platform flag.
        // Only add the platform flag when the user explicitly provided --target.
        val shouldAddPythonPlatform = !targets.isNullOrEmpty()
        var success = true
        for (target in targetsToProcess) {
            val venvDir = File(crossenvDir, target)
            if (!venvDir.exists()) {
                println("Virtual environment for target $target does not exist. Skipping.")
                continue
            }

            println("\n=== Dependencies for $packageName (target: $target) ===")

            runBlocking {
                val args =
                    buildMap {
                        if (extraArgs != null) putAll(extraArgs)
                        if (shouldAddPythonPlatform && !containsKey("python-platform")) {
                            put("python-platform", target)
                        }
                    }.ifEmpty { null }

                backend
                    .showDependencyTree(venvDir.absolutePath, args)
                    .onSuccess { output ->
                        println(output)
                    }.onFailure { error ->
                        println("Failed to show dependency tree for target $target: ${error.message}")
                        success = false
                    }
            }
        }

        return success
    }
}
