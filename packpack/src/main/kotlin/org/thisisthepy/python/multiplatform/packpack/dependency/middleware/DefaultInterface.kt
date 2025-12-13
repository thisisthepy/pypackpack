package org.thisisthepy.python.multiplatform.packpack.dependency.middleware

import kotlinx.coroutines.runBlocking
import org.thisisthepy.python.multiplatform.packpack.config.PackPackConfig
import org.thisisthepy.python.multiplatform.packpack.config.ProjectConfig
import org.thisisthepy.python.multiplatform.packpack.config.PyPackPackConfig
import org.thisisthepy.python.multiplatform.packpack.config.PyProjectConfig
import org.thisisthepy.python.multiplatform.packpack.config.PyProjectParser
import org.thisisthepy.python.multiplatform.packpack.config.ToolConfig
import org.thisisthepy.python.multiplatform.packpack.dependency.middleware.environment.CrossEnv
import org.thisisthepy.python.multiplatform.packpack.dependency.middleware.environment.DevEnv
import java.io.File
import org.thisisthepy.python.multiplatform.packpack.dependency.backend.BaseInterface as BackendBaseInterface

/** Default middleware implementation */
class DefaultInterface : BaseInterface {
    private lateinit var backend: BackendBaseInterface
    private lateinit var devEnv: DevEnv
    private lateinit var crossEnv: CrossEnv

    var projectPythonVersion: String = PackPackConfig.defaultPythonVersion
        private set

    /** Initialize middleware */
    override fun initialize() {
        backend = BackendBaseInterface.create("uv")
        backend.initialize()

        devEnv = DevEnv()
        devEnv.initialize(backend)

        crossEnv = CrossEnv()
        crossEnv.initialize(backend)
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
            initialize()
        }
        return devEnv
    }

    /** Get cross-platform environment */
    override fun getCrossEnv(): CrossEnv {
        if (!::crossEnv.isInitialized) {
            initialize()
        }
        return crossEnv
    }

    /** Init Project */
    override fun initProject(
        projectName: String,
        pythonVersion: String,
    ): Boolean {
        val projectDir =
            if (projectName.isNotEmpty()) {
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
        val pyprojectTomlFile = File(projectDir, "pyproject.toml")
        if (pyprojectTomlFile.exists()) {
            println("Project already exists in: ${projectDir.absolutePath}")
            return false
        }

        val parser = PyProjectParser()

        // Create pyproject.toml
        val config =
            PyProjectConfig(
                project =
                    ProjectConfig(
                        name = projectName,
                        version = "0.1.0",
                        description = "A Python multi-platform project",
                        readme = "README.md",
                        requiresPython = ">=$pythonVersion",
                        dependencies = emptyList(),
                    ),
                tool =
                    ToolConfig(
                        pypackpack =
                            PyPackPackConfig(
                                version = "1.0",
                                targets = emptyMap(),
                            ),
                    ),
            )

        try {
            parser.writeToFile(config, pyprojectTomlFile)
        } catch (e: Exception) {
            println("Failed to create pyproject.toml: ${e.message}")
            return false
        }

        // Create README.md if it doesn't exist
        val readmeFile = File(projectDir, "README.md")
        if (!readmeFile.exists()) {
            readmeFile.writeText("# $projectName\n\nA Python multi-platform project\n")
        }

        // Create .gitignore if it doesn't exist
        val gitignoreFile = File(projectDir, ".gitignore")
        if (!gitignoreFile.exists()) {
            gitignoreFile.writeText(
                """
                # Python
                __pycache__/
                """.trimIndent(),
            )
        }

        // Create virtual environment
        val venvDir = File(projectDir, ".venv")
        if (!venvDir.exists()) {
            return runBlocking {
                val versionToUse =
                    if (pythonVersion.isNotEmpty()) {
                        pythonVersion
                    } else {
                        PackPackConfig.defaultPythonVersion
                    }
                val result = backend.createVirtualEnvironment(venvDir.absolutePath, versionToUse)
                if (result.success) {
                    println(
                        "Created project with virtual environment in: ${projectDir.absolutePath}",
                    )
                    true
                } else {
                    println(
                        "Created project but failed to create virtual environment: ${result.error}",
                    )
                    false
                }
            }
        }

        println("Created project in: ${projectDir.absolutePath}")
        return true
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
        if (!::backend.isInitialized) {
            initialize()
        }

        return if (packageName == null) {
            // Add to development environment
            devEnv.addDependencies(dependencies, extraArgs)
        } else {
            // Add to cross-platform environment
            crossEnv.addDependencies(packageName, dependencies, targets, extraArgs)
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
        if (!::backend.isInitialized) {
            initialize()
        }

        return if (packageName == null) {
            // Remove from development environment
            devEnv.removeDependencies(dependencies, extraArgs)
        } else {
            // Remove from cross-platform environment
            crossEnv.removeDependencies(packageName, dependencies, targets, extraArgs)
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
        if (!::backend.isInitialized) {
            initialize()
        }

        return if (packageName == null) {
            // Sync development environment
            devEnv.syncDependencies(extraArgs)
        } else {
            // Sync cross-platform environment
            crossEnv.syncDependencies(packageName, targets, extraArgs)
        }
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
        if (!::backend.isInitialized) {
            initialize()
        }

        return if (packageName == null) {
            // Show development environment dependency tree
            devEnv.showDependencyTree(extraArgs)
        } else {
            // Show cross-platform environment dependency tree
            crossEnv.showDependencyTree(packageName, targets, extraArgs)
        }
    }
}
