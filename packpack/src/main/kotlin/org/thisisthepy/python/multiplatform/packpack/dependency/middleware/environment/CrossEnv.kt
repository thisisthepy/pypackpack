package org.thisisthepy.python.multiplatform.packpack.dependency.middleware.environment

import org.thisisthepy.python.multiplatform.packpack.dependency.backend.BaseInterface
import org.thisisthepy.python.multiplatform.packpack.dependency.backend.UVInterface
import org.thisisthepy.python.multiplatform.packpack.util.CommandResult
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.runBlocking

/**
 * Cross-platform environment management
 * Handles dependencies for specific package and target platforms
 */
class CrossEnv {
    private lateinit var backend: BaseInterface
    private val venvPath = ".venv"
    private val pyprojectFile = "pyproject.toml"
    private val lockFile = "pyproject.lock"
    private val crossenvDir = "build/crossenv"
    
    /**
     * Available target platforms
     */
    private val availablePlatforms = listOf(
        "android_21_arm64",
        "android_21_x86_64",
        "windows_amd64",
        "macos_arm64",
        "macos_x86_64",
        "linux_amd64"
    )

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
        if (packageName in listOf("init", "python", "package", "target", "add", "remove", "sync", "tree", "build", "bundle", "deploy")) {
            println("Package name cannot be a reserved word: $packageName")
            return false
        }
        
        val projectRoot = findProjectRoot() ?: run {
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
        val pyprojectContent = """
            [build-system]
            requires = ["setuptools>=61.0"]
            build-backend = "setuptools.build_meta"

            [project]
            name = "$packageName"
            version = "0.1.0"
            description = "A Python package managed by PyPackPack"
            readme = "../README.md"
            requires-python = ">=3.13"
            
            [tool.pypackpack]
            managed = true
            
            [tool.pypackpack.dependencies]
            # Dependencies will be added here
            
            [tool.pypackpack.targets]
            # Targets will be added here
        """.trimIndent()
        File(packageDir, pyprojectFile).writeText(pyprojectContent)
        
        // Update root pyproject.toml to include the new package
        val rootPyprojectFile = File(projectRoot, pyprojectFile)
        if (rootPyprojectFile.exists()) {
            val rootPyproject = rootPyprojectFile.readText()
            val updatedContent = if (rootPyproject.contains("[tool.pypackpack.packages]")) {
                // Add package to existing section
                rootPyproject.replace(
                    "[tool.pypackpack.packages]",
                    "[tool.pypackpack.packages]\n\"$packageName\" = { path = \"./$packageName\" }"
                )
            } else {
                // Add new section
                """
                $rootPyproject
                
                [tool.pypackpack.packages]
                "$packageName" = { path = "./$packageName" }
                """.trimIndent()
            }
            rootPyprojectFile.writeText(updatedContent)
        }
        
        // Create host platform virtual environment
        val hostPlatform = detectHostPlatform()
        val venvDir = File(crossenvDir, hostPlatform)
        
        return runBlocking {
            val result = backend.createVirtualEnvironment(venvDir.absolutePath, null, null)
            if (result.success) {
                println("Created package '$packageName' with host platform environment ($hostPlatform)")
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
        val projectRoot = findProjectRoot() ?: run {
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
            val rootPyproject = rootPyprojectFile.readText()
            val packageEntry = "\"$packageName\" = { path = \"./$packageName\" }"
            val updatedContent = rootPyproject.replace(packageEntry, "").replace("\n\n\n", "\n\n")
            rootPyprojectFile.writeText(updatedContent)
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
        
        return when {
            os.contains("win") -> "windows_amd64"
            os.contains("mac") -> if (arch.contains("aarch64") || arch.contains("arm")) "macos_arm64" else "macos_x86_64"
            else -> "linux_amd64" // Default to Linux x86_64
        }
    }

    /**
     * Add target platform to a package
     * @param packageName Package name
     * @param targetPlatforms List of target platforms
     * @return Success status
     */
    fun addTargetPlatform(packageName: String, targetPlatforms: List<String>): Boolean {
        val projectRoot = findProjectRoot() ?: run {
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
        
        // Verify target platforms
        val invalidPlatforms = targetPlatforms.filter { it !in availablePlatforms }
        if (invalidPlatforms.isNotEmpty()) {
            println("Invalid target platforms: ${invalidPlatforms.joinToString(", ")}")
            println("Available platforms: ${availablePlatforms.joinToString(", ")}")
            return false
        }
        
        // Create source directories for each platform
        val srcDir = File(packageDir, "src")
        for (platform in targetPlatforms) {
            val platformDir = File(srcDir, platformToSourceDir(platform))
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
        for (platform in targetPlatforms) {
            val venvDir = File(crossenvDir, platform)
            if (!venvDir.exists()) {
                runBlocking {
                    val result = backend.createVirtualEnvironment(
                        venvDir.absolutePath,
                        null,
                        null
                    )
                    if (!result.success) {
                        println("Failed to create virtual environment for $platform: ${result.error}")
                        success = false
                    }
                }
            }
        }
        
        // Update package pyproject.toml
        val pyprojectContent = packagePyproject.readText()
        val updatedContent = if (pyprojectContent.contains("[tool.pypackpack.targets]")) {
            // Add targets to existing section
            var content = pyprojectContent
            for (platform in targetPlatforms) {
                if (!content.contains("\"$platform\"")) {
                    content = content.replace(
                        "[tool.pypackpack.targets]",
                        "[tool.pypackpack.targets]\n\"$platform\" = true"
                    )
                }
            }
            content
        } else {
            // Add new section
            """
            $pyprojectContent
            
            [tool.pypackpack.targets]
            ${targetPlatforms.joinToString("\n") { "\"$it\" = true" }}
            """.trimIndent()
        }
        packagePyproject.writeText(updatedContent)
        
        println("Added target platforms to package $packageName: ${targetPlatforms.joinToString(", ")}")
        return success
    }
    
    /**
     * Remove target platform from a package
     * @param packageName Package name
     * @param targetPlatforms List of target platforms
     * @return Success status
     */
    fun removeTargetPlatform(packageName: String, targetPlatforms: List<String>): Boolean {
        val projectRoot = findProjectRoot() ?: run {
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
        
        // Remove virtual environments for each platform
        val crossenvDir = File(packageDir, "build/crossenv")
        for (platform in targetPlatforms) {
            val venvDir = File(crossenvDir, platform)
            if (venvDir.exists() && venvDir.isDirectory) {
                if (!venvDir.deleteRecursively()) {
                    println("Warning: Failed to delete virtual environment for $platform")
                }
            }
        }
        
        // Remove source directories for each platform (if empty)
        val srcDir = File(packageDir, "src")
        for (platform in targetPlatforms) {
            val platformDir = File(srcDir, platformToSourceDir(platform))
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
        for (platform in targetPlatforms) {
            updatedContent = updatedContent.replace("\"$platform\" = true\n", "")
        }
        packagePyproject.writeText(updatedContent)
        
        println("Removed target platforms from package $packageName: ${targetPlatforms.joinToString(", ")}")
        return true
    }
    
    /**
     * Convert platform identifier to source directory name
     * @param platform Platform identifier
     * @return Source directory name
     */
    private fun platformToSourceDir(platform: String): String {
        return when {
            platform.startsWith("android") -> "android"
            platform.startsWith("windows") -> "windows"
            platform.startsWith("macos") -> "macos"
            platform.startsWith("linux") -> "linux"
            platform.startsWith("wasm") -> "wasm"
            else -> platform.split("_")[0]
        }
    }

    /**
     * Add dependencies to a package
     * @param packageName Package name
     * @param dependencies List of dependencies to add
     * @param targets List of target platforms (optional)
     * @param extraArgs Extra arguments (optional)
     * @return Success status
     */
    fun addDependencies(packageName: String, dependencies: List<String>, targets: List<String>?, extraArgs: Map<String, String>?): Boolean {
        val projectRoot = findProjectRoot() ?: run {
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
        val availableTargets = crossenvDir.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()
        
        if (availableTargets.isEmpty()) {
            println("No target platforms available for package $packageName")
            return false
        }
        
        // Determine which targets to process
        val targetsToProcess = if (targets != null && targets.isNotEmpty()) {
            val invalidTargets = targets.filter { it !in availableTargets }
            if (invalidTargets.isNotEmpty()) {
                println("Invalid target platforms: ${invalidTargets.joinToString(", ")}")
                println("Available platforms for package $packageName: ${availableTargets.joinToString(", ")}")
                return false
            }
            targets
        } else {
            availableTargets
        }
        
        // Update dependencies in pyproject.toml
        var pyprojectContent = packagePyproject.readText()
        for (dependency in dependencies) {
            if (!pyprojectContent.contains("\"$dependency\"")) {
                pyprojectContent = if (pyprojectContent.contains("[tool.pypackpack.dependencies]")) {
                    // Add to existing section
                    pyprojectContent.replace(
                        "[tool.pypackpack.dependencies]",
                        "[tool.pypackpack.dependencies]\n\"$dependency\" = \"*\""
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
        
        // Install dependencies in each target's virtual environment
        var success = true
        for (target in targetsToProcess) {
            val venvDir = File(crossenvDir, target)
            var failFlag = false
            if (!venvDir.exists()) {
                println("Virtual environment for target $target does not exist. Creating...")
                runBlocking {
                    val result = backend.createVirtualEnvironment(
                        venvDir.absolutePath,
                        null,
                        null
                    )
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
            
            // Copy lock file to venv if it exists
            val packageLockFile = File(packageDir, lockFile)
            val venvLockFile = File(venvDir, lockFile)
            if (packageLockFile.exists()) {
                Files.copy(
                    packageLockFile.toPath(),
                    venvLockFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
            
            runBlocking {
                val platform = mapOf("platform" to target)
                val args = if (extraArgs != null) extraArgs + platform else platform
                
                val result = backend.installDependencies(venvDir.absolutePath, dependencies, args)
                if (result.success) {
                    // Generate lock file in the venv
                    val lockResult = backend.generateLockFile(venvDir.absolutePath, args)
                    if (lockResult.success) {
                        // Export requirements.txt for the target platform
                        val requirementsPath = File(packageDir, "$target.requirements.txt").absolutePath
                        val exportResult = (backend as UVInterface).exportLockFile(
                            venvDir.absolutePath,
                            requirementsPath,
                            "requirements-txt"
                        )
                        
                        if (exportResult.success) {
                            println("Generated platform-specific requirements file for $target: $target.requirements.txt")
                        } else {
                            println("Warning: Failed to export platform-specific requirements file for $target: ${exportResult.error}")
                        }
                        
                        // Copy main uv.lock file as platform-specific backup
                        if (venvLockFile.exists()) {
                            val targetLockFile = File(packageDir, "$target.uv.lock")
                            Files.copy(
                                venvLockFile.toPath(),
                                targetLockFile.toPath(),
                                StandardCopyOption.REPLACE_EXISTING
                            )
                        }
                    }
                    
                    println("Added dependencies to target $target: ${dependencies.joinToString(", ")}")
                } else {
                    println("Failed to add dependencies to target $target: ${result.error}")
                    success = false
                }
            }
        }
        
        return success
    }

    /**
     * Remove dependencies from a package
     * @param packageName Package name
     * @param dependencies List of dependencies to remove
     * @param targets List of target platforms (optional)
     * @param extraArgs Extra arguments (optional)
     * @return Success status
     */
    fun removeDependencies(packageName: String, dependencies: List<String>, targets: List<String>?, extraArgs: Map<String, String>?): Boolean {
        val projectRoot = findProjectRoot() ?: run {
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
        val availableTargets = crossenvDir.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()
        
        if (availableTargets.isEmpty()) {
            println("No target platforms available for package $packageName")
            return false
        }
        
        // Determine which targets to process
        val targetsToProcess = if (targets != null && targets.isNotEmpty()) {
            val invalidTargets = targets.filter { it !in availableTargets }
            if (invalidTargets.isNotEmpty()) {
                println("Invalid target platforms: ${invalidTargets.joinToString(", ")}")
                println("Available platforms for package $packageName: ${availableTargets.joinToString(", ")}")
                return false
            }
            targets
        } else {
            availableTargets
        }
        
        // Update dependencies in pyproject.toml
        var pyprojectContent = packagePyproject.readText()
        for (dependency in dependencies) {
            pyprojectContent = pyprojectContent.replace("\"$dependency\" = \"*\"\n", "")
        }
        packagePyproject.writeText(pyprojectContent)
        
        // Remove dependencies from each target's virtual environment
        var success = true
        for (target in targetsToProcess) {
            val venvDir = File(crossenvDir, target)
            if (!venvDir.exists()) {
                println("Virtual environment for target $target does not exist. Skipping.")
                continue
            }
            
            // Copy lock file to venv if it exists
            val packageLockFile = File(packageDir, lockFile)
            val venvLockFile = File(venvDir, lockFile)
            if (packageLockFile.exists()) {
                Files.copy(
                    packageLockFile.toPath(),
                    venvLockFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
            
            runBlocking {
                val platform = mapOf("platform" to target)
                val args = if (extraArgs != null) extraArgs + platform else platform
                
                val result = backend.uninstallDependencies(venvDir.absolutePath, dependencies, args)
                if (result.success) {
                    // Generate updated lock file in the venv
                    val lockResult = backend.generateLockFile(venvDir.absolutePath, args)
                    if (lockResult.success) {
                        // Export updated requirements.txt for the target platform
                        val requirementsPath = File(packageDir, "$target.requirements.txt").absolutePath
                        val exportResult = (backend as UVInterface).exportLockFile(
                            venvDir.absolutePath,
                            requirementsPath,
                            "requirements-txt"
                        )
                        
                        if (exportResult.success) {
                            println("Updated platform-specific requirements file for $target: $target.requirements.txt")
                        } else {
                            println("Warning: Failed to export updated platform-specific requirements file for $target: ${exportResult.error}")
                        }
                        
                        // Copy updated uv.lock file as platform-specific backup
                        if (venvLockFile.exists()) {
                            val targetLockFile = File(packageDir, "$target.uv.lock")
                            Files.copy(
                                venvLockFile.toPath(),
                                targetLockFile.toPath(),
                                StandardCopyOption.REPLACE_EXISTING
                            )
                        }
                    }
                    
                    println("Removed dependencies from target $target: ${dependencies.joinToString(", ")}")
                } else {
                    println("Failed to remove dependencies from target $target: ${result.error}")
                    success = false
                }
            }
        }
        
        return success
    }

    /**
     * Synchronize dependencies for a package
     * @param packageName Package name
     * @param targets List of target platforms (optional)
     * @param extraArgs Extra arguments (optional)
     * @return Success status
     */
    fun syncDependencies(packageName: String, targets: List<String>?, extraArgs: Map<String, String>?): Boolean {
        val projectRoot = findProjectRoot() ?: run {
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
        val availableTargets = crossenvDir.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()
        
        if (availableTargets.isEmpty()) {
            println("No target platforms available for package $packageName")
            return false
        }
        
        // Determine which targets to process
        val targetsToProcess = if (targets != null && targets.isNotEmpty()) {
            val invalidTargets = targets.filter { it !in availableTargets }
            if (invalidTargets.isNotEmpty()) {
                println("Invalid target platforms: ${invalidTargets.joinToString(", ")}")
                println("Available platforms for package $packageName: ${availableTargets.joinToString(", ")}")
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
                    val result = backend.createVirtualEnvironment(
                        venvDir.absolutePath,
                        null,
                        null
                    )
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
            
            // Check if we have platform-specific requirements files
            val requirementsFile = File(packageDir, "$target.requirements.txt")
            val targetLockFile = File(packageDir, "$target.uv.lock")
            val venvLockFile = File(venvDir, "uv.lock")
            
            runBlocking {
                val platform = mapOf("platform" to target)
                val args = if (extraArgs != null) extraArgs + platform else platform
                
                if (requirementsFile.exists()) {
                    // Use platform-specific requirements.txt for synchronization
                    val syncResult = (backend as UVInterface).syncFromRequirements(
                        venvDir.absolutePath,
                        requirementsFile.absolutePath,
                        extraArgs
                    )
                    
                    if (syncResult.success) {
                        println("Synchronized dependencies for target $target using $target.requirements.txt")
                    } else {
                        println("Failed to synchronize dependencies for target $target: ${syncResult.error}")
                        success = false
                    }
                } else if (targetLockFile.exists()) {
                    // Fallback: Copy platform-specific uv.lock to venv and sync
                    Files.copy(
                        targetLockFile.toPath(),
                        venvLockFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                    )
                    
                    val syncResult = backend.syncFromLockFile(venvDir.absolutePath, null, args)
                    if (syncResult.success) {
                        println("Synchronized dependencies for target $target using $target.uv.lock")
                    } else {
                        println("Failed to synchronize dependencies for target $target: ${syncResult.error}")
                        success = false
                    }
                } else {
                    // No platform-specific files, perform regular sync
                    val syncResult = backend.syncDependencies(venvDir.absolutePath, args)
                    if (syncResult.success) {
                        // Generate lock file and export requirements.txt
                        val lockResult = backend.generateLockFile(venvDir.absolutePath, args)
                        if (lockResult.success) {
                            val exportResult = (backend as UVInterface).exportLockFile(
                                venvDir.absolutePath,
                                requirementsFile.absolutePath,
                                "requirements-txt"
                            )
                            
                            if (exportResult.success) {
                                println("Generated platform-specific requirements file for $target: $target.requirements.txt")
                            }
                            
                            // Copy uv.lock as backup
                            if (venvLockFile.exists()) {
                                Files.copy(
                                    venvLockFile.toPath(),
                                    targetLockFile.toPath(),
                                    StandardCopyOption.REPLACE_EXISTING
                                )
                            }
                        }
                        
                        println("Synchronized dependencies for target $target")
                    } else {
                        println("Failed to synchronize dependencies for target $target: ${syncResult.error}")
                        success = false
                    }
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
    fun showDependencyTree(packageName: String, targets: List<String>?, extraArgs: Map<String, String>?): Boolean {
        val projectRoot = findProjectRoot() ?: run {
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
        val availableTargets = crossenvDir.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()
        
        if (availableTargets.isEmpty()) {
            println("No target platforms available for package $packageName")
            return false
        }
        
        // Determine which targets to process
        val targetsToProcess = if (targets != null && targets.isNotEmpty()) {
            val invalidTargets = targets.filter { it !in availableTargets }
            if (invalidTargets.isNotEmpty()) {
                println("Invalid target platforms: ${invalidTargets.joinToString(", ")}")
                println("Available platforms for package $packageName: ${availableTargets.joinToString(", ")}")
                return false
            }
            targets
        } else {
            availableTargets
        }
        
        // Show dependency tree for each target
        var success = true
        for (target in targetsToProcess) {
            val venvDir = File(crossenvDir, target)
            if (!venvDir.exists()) {
                println("Virtual environment for target $target does not exist. Skipping.")
                continue
            }
            
            println("\n=== Dependencies for $packageName (target: $target) ===")
            
            runBlocking {
                val platform = mapOf("platform" to target)
                val args = if (extraArgs != null) extraArgs + platform else platform
                
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
