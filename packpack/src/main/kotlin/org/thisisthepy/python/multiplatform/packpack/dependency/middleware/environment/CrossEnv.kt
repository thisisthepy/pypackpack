package org.thisisthepy.python.multiplatform.packpack.dependency.middleware.environment

import kotlinx.coroutines.runBlocking
import org.thisisthepy.python.multiplatform.packpack.dependency.backend.BaseInterface
import org.thisisthepy.python.multiplatform.packpack.util.Platforms
import java.io.File

/**
 * Cross-platform environment management Handles dependencies for specific package and target
 * platforms
 */
class CrossEnv {
    private lateinit var backend: BaseInterface
    private lateinit var devEnv: DevEnv
    private val pyprojectFile = "pyproject.toml"
    private val crossenvDir = "build/crossenv"

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

        return true
    } // TODO: Implement addPackage

    /**
     * Remove a package from the project
     * @param packageName Package name
     * @return Success status
     */
    fun removePackage(packageName: String): Boolean = true // TODO: Implement removePackage

    /**
     * Add target platform to a package
     * @param packageName Package name
     * @param targets List of target platforms
     * @return Success status
     */
    fun addTarget(
        packageName: String,
        targets: List<String>,
    ): Boolean = true // TODO: Implement addTarget

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
        return true
    } //

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
