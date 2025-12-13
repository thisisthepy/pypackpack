package org.thisisthepy.python.multiplatform.packpack.dependency.middleware.environment

import kotlinx.coroutines.runBlocking
import org.thisisthepy.python.multiplatform.packpack.config.PackPackConfig
import org.thisisthepy.python.multiplatform.packpack.dependency.backend.BaseInterface
import java.io.File

/** Development environment management Handles dev dependencies for the project root */
class DevEnv {
    private lateinit var backend: BaseInterface
    private val venvPath = ".venv"
    private val pyprojectFile = "pyproject.toml"
    private val lockFile = "uv.lock"

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

        val venvDir = File(projectRoot, venvPath)
        if (!venvDir.exists()) {
            println("Virtual environment not found. Creating...")
            runBlocking {
                val result =
                    backend.createVirtualEnvironment(
                        venvDir.absolutePath,
                        PackPackConfig.defaultPythonVersion,
                    )
                if (!result.success) {
                    println("Failed to create virtual environment: ${result.error}")
                    return@runBlocking false
                }
            }
            if (!venvDir.exists()) return false
        }

        return runBlocking {
            val result = backend.addDependencies(venvDir.absolutePath, dependencies, extraArgs)
            if (result.success) {
                println("Added dependencies: ${dependencies.joinToString(", ")}")
                true
            } else {
                println("Failed to add dependencies: ${result.error}")
                false
            }
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

        val venvDir = File(projectRoot, venvPath)
        if (!venvDir.exists()) {
            println("Virtual environment not found. Cannot remove dependencies.")
            return false
        }

        return runBlocking {
            val result = backend.removeDependencies(venvDir.absolutePath, dependencies, extraArgs)
            if (result.success) {
                println("Removed dependencies: ${dependencies.joinToString(", ")}")
                true
            } else {
                println("Failed to remove dependencies: ${result.error}")
                false
            }
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
        if (!venvDir.exists()) {
            println("Virtual environment not found. Creating...")
            return runBlocking {
                val result = backend.createVirtualEnvironment(venvDir.absolutePath, null)
                if (result.success) {
                    syncDependenciesInVenv(venvDir, extraArgs)
                } else {
                    println("Failed to create virtual environment: ${result.error}")
                    false
                }
            }
        }

        return syncDependenciesInVenv(venvDir, extraArgs)
    }

    /**
     * Synchronize dependencies in the virtual environment
     * @param venvDir Virtual environment directory
     * @param extraArgs Extra arguments (optional)
     * @return Success status
     */
    private fun syncDependenciesInVenv(
        venvDir: File,
        extraArgs: Map<String, String>?,
    ): Boolean =
        runBlocking {
            val result = backend.syncDependencies(venvDir.absolutePath, extraArgs)
            if (result.success) {
                println("Dependencies synchronized successfully")
                true
            } else {
                println("Failed to synchronize dependencies: ${result.error}")
                false
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
            val result = backend.showDependencyTree(venvDir.absolutePath, extraArgs)
            if (result.success) {
                println("=== Dependencies for project ===")
                println(result.output)
                true
            } else {
                println("Failed to show dependency tree: ${result.error}")
                false
            }
        }
    }

    /**
     * List available Python versions
     * @return Success status
     */
    fun listPythonVersions(): Boolean =
        runBlocking {
            val result = backend.listPythonVersions()
            if (result.success) {
                println("Available Python versions:")
                println(result.output)
                true
            } else {
                println("Failed to list Python versions: ${result.error}")
                false
            }
        }

    /**
     * Find a specific Python version
     * @param pythonVersion Python version
     * @return Success status
     */
    fun findPythonVersion(pythonVersion: String): Boolean =
        runBlocking {
            val result = backend.findPythonVersion(pythonVersion)
            if (result.success) {
                println("Found Python version:")
                println(result.output)
                true
            } else {
                println("Failed to find Python version $pythonVersion: ${result.error}")
                false
            }
        }

    /**
     * Install a specific Python version
     * @param pythonVersion Python version
     * @return Success status
     */
    fun installPythonVersion(pythonVersion: String): Boolean =
        runBlocking {
            val result = backend.installPythonVersion(pythonVersion)
            if (result.success) {
                println("Installed Python version $pythonVersion")
                true
            } else {
                println("Failed to install Python version $pythonVersion: ${result.error}")
                false
            }
        }

    /**
     * Uninstall a specific Python version
     * @param pythonVersion Python version
     * @return Success status
     */
    fun uninstallPythonVersion(pythonVersion: String): Boolean =
        runBlocking {
            val result = backend.uninstallPythonVersion(pythonVersion)
            if (result.success) {
                println("Uninstalled Python version $pythonVersion")
                true
            } else {
                println("Failed to uninstall Python version $pythonVersion: ${result.error}")
                false
            }
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
            val result = backend.createVirtualEnvironment(venvDir.absolutePath, pythonVersion)
            if (result.success) {
                println("Changed Python version to $pythonVersion")

                // Sync dependencies if lock file exists
                val lockFileInProject = File(projectRoot, lockFile)
                if (lockFileInProject.exists()) {
                    println("Synchronizing dependencies...")
                    syncDependenciesInVenv(venvDir, null)
                }

                true
            } else {
                println("Failed to change Python version to $pythonVersion: ${result.error}")
                false
            }
        }
    }
}
