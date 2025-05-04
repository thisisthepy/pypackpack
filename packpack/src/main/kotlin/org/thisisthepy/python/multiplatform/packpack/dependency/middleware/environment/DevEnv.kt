package org.thisisthepy.python.multiplatform.packpack.dependency.middleware.environment

import org.thisisthepy.python.multiplatform.packpack.dependency.backend.BaseInterface
import org.thisisthepy.python.multiplatform.packpack.util.CommandResult
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.runBlocking

/**
 * Development environment management
 * Handles dev dependencies for the project root
 */
class DevEnv {
    private lateinit var backend: BaseInterface
    private val venvPath = ".venv"
    private val pyprojectFile = "pyproject.toml"
    private val lockFile = "pyproject.lock"
    private val requirementsFile = "requirements.txt"
    private val constraintsFile = "constraints.txt"
    
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
     * Initialize a new project
     * @param projectName Project name (optional)
     * @param pythonVersion Python version (optional)
     * @return Success status
     */
    fun initProject(projectName: String?, pythonVersion: String?): Boolean {
        val projectDir = if (projectName != null) {
            val dir = File(projectName)
            if (!dir.exists() && !dir.mkdirs()) {
                println("Failed to create project directory: ${dir.absolutePath}")
                return false
            }
            dir
        } else {
            File(System.getProperty("user.dir"))
        }
        
        // Check if project already exists
        val pyprojectTomlFile = File(projectDir, pyprojectFile)
        if (pyprojectTomlFile.exists()) {
            println("Project already exists in: ${projectDir.absolutePath}")
            return false
        }
        
        // Create pyproject.toml
        val projectNameInFile = projectName ?: projectDir.name
        val pyprojectContent = """
            [build-system]
            requires = ["setuptools>=61.0"]
            build-backend = "setuptools.build_meta"

            [project]
            name = "$projectNameInFile"
            version = "0.1.0"
            description = "A Python multi-platform project"
            readme = "README.md"
            requires-python = ">=3.8"
            
            [tool.pypackpack]
            managed = true
            
            [tool.pypackpack.packages]
            # Package definitions will be added here
        """.trimIndent()
        
        pyprojectTomlFile.writeText(pyprojectContent)
        
        // Create README.md if it doesn't exist
        val readmeFile = File(projectDir, "README.md")
        if (!readmeFile.exists()) {
            readmeFile.writeText("# $projectNameInFile\n\nA Python multi-platform project\n")
        }
        
        // Create .gitignore if it doesn't exist
        val gitignoreFile = File(projectDir, ".gitignore")
        if (!gitignoreFile.exists()) {
            gitignoreFile.writeText("""
                # Python
                __pycache__/
            """.trimIndent())
        }
        
        // Create virtual environment
        val venvDir = File(projectDir, venvPath)
        if (!venvDir.exists()) {
            return runBlocking {
                val result = backend.createVirtualEnvironment(venvDir.absolutePath, pythonVersion)
                if (result.success) {
                    println("Created project with virtual environment in: ${projectDir.absolutePath}")
                    true
                } else {
                    println("Created project but failed to create virtual environment: ${result.error}")
                    false
                }
            }
        }
        
        println("Created project in: ${projectDir.absolutePath}")
        return true
    }
    
    /**
     * Add dependencies to the development environment
     * @param dependencies List of dependencies to add
     * @param extraArgs Extra arguments (optional)
     * @return Success status
     */
    fun addDependencies(dependencies: List<String>, extraArgs: Map<String, String>?): Boolean {
        val projectRoot = findProjectRoot() ?: run {
            println("Project root not found. Please initialize a project first.")
            return false
        }
        
        val venvDir = File(projectRoot, venvPath)
        if (!venvDir.exists()) {
            println("Virtual environment not found. Creating...")
            return runBlocking {
                val result = backend.createVirtualEnvironment(venvDir.absolutePath, null)
                if (result.success) {
                    installDependencies(venvDir, dependencies, extraArgs)
                } else {
                    println("Failed to create virtual environment: ${result.error}")
                    false
                }
            }
        }
        
        return installDependencies(venvDir, dependencies, extraArgs)
    }
    
    /**
     * Install dependencies in the virtual environment
     * @param venvDir Virtual environment directory
     * @param dependencies List of dependencies to install
     * @param extraArgs Extra arguments (optional)
     * @return Success status
     */
    private fun installDependencies(venvDir: File, dependencies: List<String>, extraArgs: Map<String, String>?): Boolean {
        return runBlocking {
            // Copy lock file to venv if it exists
            val projectRoot = venvDir.parentFile
            val lockFileInProject = File(projectRoot, lockFile)
            val lockFileInVenv = File(venvDir, lockFile)
            
            if (lockFileInProject.exists()) {
                Files.copy(
                    lockFileInProject.toPath(),
                    lockFileInVenv.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
            
            val result = backend.installDependencies(venvDir.absolutePath, dependencies, extraArgs)
            if (result.success) {
                // Copy lock file back to project root
                if (lockFileInVenv.exists()) {
                    Files.copy(
                        lockFileInVenv.toPath(),
                        lockFileInProject.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                    )
                }
                
                // Update requirements.txt
                updateRequirementsFile(projectRoot)
                
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
    fun removeDependencies(dependencies: List<String>, extraArgs: Map<String, String>?): Boolean {
        val projectRoot = findProjectRoot() ?: run {
            println("Project root not found. Please initialize a project first.")
            return false
        }
        
        val venvDir = File(projectRoot, venvPath)
        if (!venvDir.exists()) {
            println("Virtual environment not found. Cannot remove dependencies.")
            return false
        }
        
        return runBlocking {
            // Copy lock file to venv if it exists
            val lockFileInProject = File(projectRoot, lockFile)
            val lockFileInVenv = File(venvDir, lockFile)
            
            if (lockFileInProject.exists()) {
                Files.copy(
                    lockFileInProject.toPath(),
                    lockFileInVenv.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
            
            val result = backend.uninstallDependencies(venvDir.absolutePath, dependencies, extraArgs)
            if (result.success) {
                // Copy lock file back to project root
                if (lockFileInVenv.exists()) {
                    Files.copy(
                        lockFileInVenv.toPath(),
                        lockFileInProject.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                    )
                }
                
                // Update requirements.txt
                updateRequirementsFile(projectRoot)
                
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
        val projectRoot = findProjectRoot() ?: run {
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
    private fun syncDependenciesInVenv(venvDir: File, extraArgs: Map<String, String>?): Boolean {
        return runBlocking {
            // Copy lock file to venv if it exists
            val projectRoot = venvDir.parentFile
            val lockFileInProject = File(projectRoot, lockFile)
            val lockFileInVenv = File(venvDir, lockFile)
            
            if (lockFileInProject.exists()) {
                Files.copy(
                    lockFileInProject.toPath(),
                    lockFileInVenv.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
            
            val result = backend.syncDependencies(venvDir.absolutePath, extraArgs)
            if (result.success) {
                // Copy lock file back to project root
                if (lockFileInVenv.exists()) {
                    Files.copy(
                        lockFileInVenv.toPath(),
                        lockFileInProject.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                    )
                }
                
                // Update requirements.txt
                updateRequirementsFile(projectRoot)
                
                println("Dependencies synchronized successfully")
                true
            } else {
                println("Failed to synchronize dependencies: ${result.error}")
                false
            }
        }
    }
    
    /**
     * Show dependency tree in the development environment
     * @param extraArgs Extra arguments (optional)
     * @return Success status
     */
    fun showDependencyTree(extraArgs: Map<String, String>?): Boolean {
        val projectRoot = findProjectRoot() ?: run {
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
     * Update requirements.txt file
     * @param projectRoot Project root directory
     */
    private fun updateRequirementsFile(projectRoot: File): Boolean {
        val venvDir = File(projectRoot, venvPath)
        if (!venvDir.exists()) {
            return false
        }
        
        return runBlocking {
            val command = listOf("pip", "freeze")
            val result = backend.executeInVenv(venvDir.absolutePath, command)
            
            if (result.success) {
                File(projectRoot, requirementsFile).writeText(result.output)
                true
            } else {
                false
            }
        }
    }
    
    /**
     * List available Python versions
     * @return Success status
     */
    fun listPythonVersions(): Boolean {
        return runBlocking {
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
    }
    
    /**
     * Find a specific Python version
     * @param pythonVersion Python version
     * @return Success status
     */
    fun findPythonVersion(pythonVersion: String): Boolean {
        return runBlocking {
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
    }
    
    /**
     * Install a specific Python version
     * @param pythonVersion Python version
     * @return Success status
     */
    fun installPythonVersion(pythonVersion: String): Boolean {
        return runBlocking {
            val result = backend.installPythonVersion(pythonVersion)
            if (result.success) {
                println("Installed Python version $pythonVersion")
                true
            } else {
                println("Failed to install Python version $pythonVersion: ${result.error}")
                false
            }
        }
    }
    
    /**
     * Uninstall a specific Python version
     * @param pythonVersion Python version
     * @return Success status
     */
    fun uninstallPythonVersion(pythonVersion: String): Boolean {
        return runBlocking {
            val result = backend.uninstallPythonVersion(pythonVersion)
            if (result.success) {
                println("Uninstalled Python version $pythonVersion")
                true
            } else {
                println("Failed to uninstall Python version $pythonVersion: ${result.error}")
                false
            }
        }
    }
    
    /**
     * Change Python version
     * @param pythonVersion Python version
     * @return Success status
     */
    fun changePythonVersion(pythonVersion: String): Boolean {
        val projectRoot = findProjectRoot() ?: run {
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
