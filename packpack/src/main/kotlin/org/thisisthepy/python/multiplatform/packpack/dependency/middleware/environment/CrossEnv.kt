package org.thisisthepy.python.multiplatform.packpack.dependency.middleware.environment

import kotlinx.coroutines.runBlocking
import org.thisisthepy.python.multiplatform.packpack.cli.internal.CommandResult
import org.thisisthepy.python.multiplatform.packpack.config.PackPackConfig
import org.thisisthepy.python.multiplatform.packpack.config.PackageRefConfig
import org.thisisthepy.python.multiplatform.packpack.config.ProjectConfig
import org.thisisthepy.python.multiplatform.packpack.config.PyPackPackConfig
import org.thisisthepy.python.multiplatform.packpack.config.PyProjectConfig
import org.thisisthepy.python.multiplatform.packpack.config.PyProjectParser
import org.thisisthepy.python.multiplatform.packpack.config.ToolConfig
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
        val parser = PyProjectParser()
        val packagePyprojectFile = File(packageDir, pyprojectFile)
        val packageConfig =
            PyProjectConfig(
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
                val pypackpack = tool.pypackpack ?: PyPackPackConfig()
                val packages = (pypackpack.packages ?: emptyMap())
                val updatedPackages =
                    if (packages.containsKey(packageName)) {
                        packages
                    } else {
                        packages + (packageName to PackageRefConfig(path = "./$packageName"))
                    }

                val updatedConfig =
                    config.copy(
                        tool = tool.copy(pypackpack = pypackpack.copy(packages = updatedPackages)),
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
            val result = backend.createVirtualEnvironment(venvDir.absolutePath, null, null)
            if (result.success) {
                println(
                    "Created package '$packageName' with host platform environment ($hostPlatform)",
                )
                true
            } else {
                println("Package created but failed to create virtual environment: ${result.error}")
                false
            }
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
            val parser = PyProjectParser()
            try {
                val config =
                    parser.parseFromFile(
                        rootPyprojectFile,
                        applyDefaults = false,
                        validateConfig = false,
                    )

                val tool = config.tool
                val pypackpack = tool?.pypackpack
                val existingPackages = pypackpack?.packages

                if (existingPackages != null && existingPackages.containsKey(packageName)) {
                    val updatedPackages = existingPackages - packageName
                    val updatedPyPackPack =
                        pypackpack.copy(
                            packages = updatedPackages.takeIf { it.isNotEmpty() },
                        )
                    val updatedConfig = config.copy(tool = tool.copy(pypackpack = updatedPyPackPack))
                    parser.writeToFile(updatedConfig, rootPyprojectFile)
                } else {
                    // Nothing to remove (either missing tool section or not a known package entry)
                }
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
     * Detect host platform
     * @return Platform identifier
     */
    private fun detectHostPlatform(): String {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()

        println("Detected OS: $os, Architecture: $arch")

        return Platforms.detectHostTarget(osName = os, osArch = arch)
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

        // Create virtual environments for each platform
        val crossenvDir = File(packageDir, "build/crossenv")
        if (!crossenvDir.exists() && !crossenvDir.mkdirs()) {
            println("Failed to create crossenv directory")
            return false
        }

        var success = true
        for (platform in normalizedTargets) {
            val venvDir = File(crossenvDir, platform)
            if (!venvDir.exists()) {
                runBlocking {
                    val result = backend.createVirtualEnvironment(venvDir.absolutePath, null, null)
                    if (!result.success) {
                        println(
                            "Failed to create virtual environment for $platform: ${result.error}",
                        )
                        success = false
                    }
                }
            }
        }

        // Update package pyproject.toml
        val pyprojectContent = packagePyproject.readText()
        val updatedContent =
            if (pyprojectContent.contains("[tool.pypackpack.targets]")) {
                // Add targets to existing section
                var content = pyprojectContent
                for (platform in normalizedTargets) {
                    if (!content.contains("\"$platform\"")) {
                        content =
                            content.replace(
                                "[tool.pypackpack.targets]",
                                "[tool.pypackpack.targets]\n\"$platform\" = true",
                            )
                    }
                }
                content
            } else {
                // Add new section
                """
                $pyprojectContent
                
                [tool.pypackpack.targets]
                ${normalizedTargets.joinToString("\n") { "\"$it\" = true" }}
                """.trimIndent()
            }
        packagePyproject.writeText(updatedContent)

        println(
            "Added target platforms to package $packageName: ${normalizedTargets.joinToString(", ")}",
        )
        return success
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

        // Remove virtual environments for each platform
        val crossenvDir = File(packageDir, "build/crossenv")
        val venvDirsToRemove = (targets + normalizedTargets).distinct()
        for (platform in venvDirsToRemove) {
            val venvDir = File(crossenvDir, platform)
            if (venvDir.exists() && venvDir.isDirectory) {
                if (!venvDir.deleteRecursively()) {
                    println("Warning: Failed to delete virtual environment for $platform")
                }
            }
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

        // Update package pyproject.toml
        val pyprojectContent = packagePyproject.readText()
        var updatedContent = pyprojectContent
        val targetsToRemove = (targets + normalizedTargets).distinct()
        for (platform in targetsToRemove) {
            updatedContent = updatedContent.replace("\"$platform\" = true\n", "")
        }
        packagePyproject.writeText(updatedContent)

        println(
            "Removed target platforms from package $packageName: ${normalizedTargets.joinToString(", ")}",
        )
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

        // Update dependencies in pyproject.toml
        var pyprojectContent = packagePyproject.readText()
        for (dependency in dependencies) {
            if (!pyprojectContent.contains("\"$dependency\"")) {
                pyprojectContent =
                    if (pyprojectContent.contains("[tool.pypackpack.dependencies]")) {
                        // Add to existing section
                        pyprojectContent.replace(
                            "[tool.pypackpack.dependencies]",
                            "[tool.pypackpack.dependencies]\n\"$dependency\" = \"*\"",
                        )
                    } else {
                        // Add new section
                        """
                        $pyprojectContent
                        
                        [tool.pypackpack.dependencies]
                        "$dependency" = "*"
                        """.trimIndent()
                    }
            }
        }
        packagePyproject.writeText(pyprojectContent)
        println("Added dependencies to package $packageName: ${dependencies.joinToString(", ")}")
        println("Note: Dependencies will be installed when target environments are synchronized.")

        return true
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

        // Update dependencies in pyproject.toml
        var pyprojectContent = packagePyproject.readText()
        var changed = false
        for (dependency in dependencies) {
            if (pyprojectContent.contains("\"$dependency\"")) {
                pyprojectContent = pyprojectContent.replace("\"$dependency\" = \"*\"\n", "")
                changed = true
            }
        }

        if (changed) {
            packagePyproject.writeText(pyprojectContent)
            println("Removed dependencies from package $packageName: ${dependencies.joinToString(", ")}")
            println("Note: Dependencies will be removed when target environments are synchronized.")
            return true
        } else {
            println("Dependencies not found in package $packageName")
            return false
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
                    val result = backend.createVirtualEnvironment(venvDir.absolutePath, null, null)
                    if (!result.success) {
                        println("Failed to create virtual environment for $target: ${result.error}")
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

                val result = backend.syncDependencies(venvDir.absolutePath, args)
                if (result.success) {
                    println("Synchronized dependencies for target $target")
                } else {
                    println("Failed to synchronize dependencies for target $target: ${result.error}")
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

                val result = backend.showDependencyTree(venvDir.absolutePath, args)
                if (result.success) {
                    println(result.output)
                } else {
                    println("Failed to show dependency tree for target $target: ${result.error}")
                    success = false
                }
            }
        }

        return success
    }
}
