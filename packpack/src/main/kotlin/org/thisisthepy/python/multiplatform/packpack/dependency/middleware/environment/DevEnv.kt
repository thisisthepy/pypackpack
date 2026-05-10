package org.thisisthepy.python.multiplatform.packpack.dependency.middleware.environment

import kotlinx.coroutines.runBlocking
import org.thisisthepy.python.multiplatform.packpack.dependency.backend.BaseInterface
import org.thisisthepy.python.multiplatform.packpack.dependency.middleware.findWorkspaceProjectRoot
import java.io.File

/** Development environment management Handles dev dependencies for the project root */
class DevEnv {
    private lateinit var backend: BaseInterface
    private val venvPath = ".venv"

    /**
     * Initialize development environment
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
        return findWorkspaceProjectRoot()
    }

    /**
     * Add dependencies to the development environment
     * @param dependencies List of dependencies to add
     * @param extraArgs Extra arguments (optional)
     * @return Success status
     */
    fun addDependencies(
        dependencies: List<String>,
        extraArgs: Map<String, String>?,
    ): Boolean {
        val projectRoot =
            findProjectRoot()
                ?: run {
                    println("Project root not found. Please initialize a project first.")
                    return false
                }

        return runBlocking {
            backend
                .addDependencies(null, dependencies, extraArgs, projectRoot)
                .onSuccess {
                    println("Added dependencies: ${dependencies.joinToString(", ")}")
                }.onFailure { error ->
                    println("Failed to add dependencies: ${error.message}")
                }.isSuccess
        }
    }

    /**
     * Remove dependencies from the development environment
     * @param dependencies List of dependencies to remove
     * @param extraArgs Extra arguments (optional)
     * @return Success status
     */
    fun removeDependencies(
        dependencies: List<String>,
        extraArgs: Map<String, String>?,
    ): Boolean {
        val projectRoot =
            findProjectRoot()
                ?: run {
                    println("Project root not found. Please initialize a project first.")
                    return false
                }

        return runBlocking {
            backend
                .removeDependencies(null, dependencies, extraArgs, projectRoot)
                .onSuccess {
                    println("Removed dependencies: ${dependencies.joinToString(", ")}")
                }.onFailure { error ->
                    println("Failed to remove dependencies: ${error.message}")
                }.isSuccess
        }
    }

    /**
     * Synchronize dependencies in the development environment
     * @param extraArgs Extra arguments (optional)
     * @return Success status
     */
    fun syncDependencies(extraArgs: Map<String, String>?): Boolean {
        val projectRoot =
            findProjectRoot()
                ?: run {
                    println("Project root not found. Please initialize a project first.")
                    return false
                }

        val venvDir = File(projectRoot, venvPath)

        return runBlocking {
            backend
                .syncDependencies(venvDir.absolutePath, extraArgs, projectRoot)
                .onSuccess {
                    println("Dependencies synchronized successfully")
                }.onFailure { error ->
                    println("Failed to synchronize dependencies: ${error.message}")
                }.isSuccess
        }
    }

    /**
     * Show dependency tree in the development environment
     * @param extraArgs Extra arguments (optional)
     * @return Success status
     */
    fun showDependencyTree(extraArgs: Map<String, String>?): Boolean {
        val projectRoot =
            findProjectRoot()
                ?: run {
                    println("Project root not found. Please initialize a project first.")
                    return false
                }

        val venvDir = File(projectRoot, venvPath)
        if (!venvDir.exists()) {
            println("Virtual environment not found. Cannot show dependency tree.")
            return false
        }

        return runBlocking {
            backend
                .showDependencyTree(packageName = null, extraArgs = extraArgs, workingDir = projectRoot)
                .onSuccess { output ->
                    println("=== Dependencies for project ===")
                    println(output)
                }.onFailure { error ->
                    println("Failed to show dependency tree: ${error.message}")
                }.isSuccess
        }
    }

    /**
     * List available Python versions
     * @return Success status
     */
    fun listPythonVersions(): Boolean =
        runBlocking {
            backend
                .listPython()
                .onSuccess { output ->
                    println("Available Python versions:")
                    println(output)
                }.onFailure { error ->
                    println("Failed to list Python versions: ${error.message}")
                }.isSuccess
        }

    /**
     * Find a specific Python version
     * @param pythonVersion Python version
     * @return Success status
     */
    fun findPythonVersion(pythonVersion: String): Boolean =
        runBlocking {
            backend
                .findPython(pythonVersion)
                .onSuccess { output ->
                    println("Found Python version:")
                    println(output)
                }.onFailure { error ->
                    println("Failed to find Python version $pythonVersion: ${error.message}")
                }.isSuccess
        }

    /**
     * Install a specific Python version
     * @param pythonVersion Python version
     * @return Success status
     */
    fun installPythonVersion(pythonVersion: String): Boolean =
        runBlocking {
            backend
                .installPython(pythonVersion)
                .onSuccess {
                    println("Installed Python version $pythonVersion")
                }.onFailure { error ->
                    println("Failed to install Python version $pythonVersion: ${error.message}")
                }.isSuccess
        }

    /**
     * Uninstall a specific Python version
     * @param pythonVersion Python version
     * @return Success status
     */
    fun uninstallPythonVersion(pythonVersion: String): Boolean =
        runBlocking {
            backend
                .uninstallPython(pythonVersion)
                .onSuccess {
                    println("Uninstalled Python version $pythonVersion")
                }.onFailure { error ->
                    println("Failed to uninstall Python version $pythonVersion: ${error.message}")
                }.isSuccess
        }

    /**
     * Change Python version
     * @param pythonVersion Python version
     * @return Success status
     */
    fun changePythonVersion(pythonVersion: String): Boolean {
        val projectRoot =
            findProjectRoot()
                ?: run {
                    println("Project root not found. Please initialize a project first.")
                    return false
                }

        val venvDir = File(projectRoot, venvPath)

        if (venvDir.exists()) {
            println("Removing existing virtual environment...")
            if (!venvDir.deleteRecursively()) {
                println("Failed to remove existing virtual environment")
                return false
            }
        }

        return runBlocking {
            backend
                .createVirtualEnvironment(venvDir.absolutePath, pythonVersion, workingDir = projectRoot)
                .onSuccess {
                    backend
                        .syncDependencies(venvDir.absolutePath, null, projectRoot)
                        .onSuccess {
                            println("Changed Python version to $pythonVersion")
                            println("Dependencies synchronized successfully")
                        }.onFailure { error ->
                            println("Failed to synchronize dependencies: ${error.message}")
                        }
                }.onFailure { error ->
                    println("Failed to change Python version to $pythonVersion: ${error.message}")
                }.isSuccess
        }
    }
}
