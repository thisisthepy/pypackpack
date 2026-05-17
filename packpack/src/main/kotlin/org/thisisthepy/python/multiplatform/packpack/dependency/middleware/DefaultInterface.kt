package org.thisisthepy.python.multiplatform.packpack.dependency.middleware

import kotlinx.coroutines.runBlocking
import org.thisisthepy.python.multiplatform.packpack.dependency.backend.BackendType
import org.thisisthepy.python.multiplatform.packpack.dependency.middleware.environment.CrossEnv
import org.thisisthepy.python.multiplatform.packpack.dependency.middleware.environment.DevEnv
import org.thisisthepy.python.multiplatform.packpack.utils.Platforms
import org.thisisthepy.python.multiplatform.packpack.utils.findProjectRoot
import org.thisisthepy.python.multiplatform.packpack.utils.toml.TomlEditor
import java.io.File
import java.time.Year
import org.thisisthepy.python.multiplatform.packpack.dependency.backend.BaseInterface as BackendBaseInterface

private const val PYPROJECT_FILE = "pyproject.toml"

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

    private fun devEnvService(): DevEnv {
        if (!::devEnv.isInitialized) {
            devEnv = DevEnv()
            devEnv.initialize(backend)
        }
        return devEnv
    }

    private fun crossEnvService(): CrossEnv {
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
        val uvArgs =
            (extraArgs?.toMutableMap() ?: mutableMapOf()).apply {
                putIfAbsent("bare", "")
            }
        val result = runBlocking { backend.initProject(path, uvArgs) }
        if (result.isFailure) return result

        val targetPlatforms: List<String>? = targets
        val projectDir = path?.let { File(it) } ?: File(System.getProperty("user.dir"))
        val pyprojectTomlPath: String = File(projectDir, "pyproject.toml").absolutePath
        val pyprojectTomlContent = File(pyprojectTomlPath).readText()
        val editor = TomlEditor(pyprojectTomlContent)

        if (!targetPlatforms.isNullOrEmpty()) {
            addPlatforms(editor, targetPlatforms)
        }

        File(pyprojectTomlPath).writeText(editor.toTomlString())
        writeInitScaffold(
            projectDir = projectDir,
            projectName = uvArgs["name"].takeUnless { it.isNullOrBlank() } ?: projectDir.name,
            pythonVersion = uvArgs["python"].takeUnless { it.isNullOrBlank() },
        )

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
            println("Dependency markers in package names are not allowed.")
            return false
        }

        return if (packageName.isNullOrBlank()) {
            devEnvService().addDependencies(dependencies, extraArgs)
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
            println("Dependency markers in package names are not allowed.")
            return false
        }

        return if (packageName.isNullOrBlank()) {
            devEnvService().removeDependencies(dependencies, extraArgs)
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
            return devEnvService().syncDependencies(extraArgs)
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
            val options = extraArgs.orEmpty()

            for (target in normalizedTargets) {
                val callArgs = options + mapOf("python-platform" to target)
                val result =
                    runBlocking { backend.showDependencyTree(null, callArgs, baseDir) }
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
    ): Result<String> = crossEnvService().addPackage(packageName, path, extraArgs)

    /** Remove a package from the workspace */
    override fun removePackage(
        packageName: String,
        path: String?,
    ): Result<String> = crossEnvService().removePackage(packageName, path)

    /** Add target platforms to a package */
    override fun addTargets(
        packageName: String?,
        targets: List<String>,
    ): Result<String> = crossEnvService().addTargets(packageName, targets)

    /** Remove target platforms from a package */
    override fun removeTargets(
        packageName: String?,
        targets: List<String>,
    ): Result<String> = crossEnvService().removeTargets(packageName, targets)

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

    override fun getToolVersion(): Result<String> = runBlocking { backend.getVersion() }

    override fun changePythonVersion(pythonVersion: String): Boolean = devEnvService().changePythonVersion(pythonVersion)

    override fun listPythonVersions(): Boolean = devEnvService().listPythonVersions()

    override fun findPythonVersion(pythonVersion: String): Boolean = devEnvService().findPythonVersion(pythonVersion)

    override fun installPythonVersion(pythonVersion: String): Boolean = devEnvService().installPythonVersion(pythonVersion)

    override fun uninstallPythonVersion(pythonVersion: String): Boolean = devEnvService().uninstallPythonVersion(pythonVersion)

    private fun normalizeTreeTargets(targets: List<String>?): List<String> {
        val defaults = listOf(Platforms.detectHostTarget())
        return Platforms.normalizeTargetsOrThrow(targets, defaultTargets = defaults)
    }

    private fun writeInitScaffold(
        projectDir: File,
        projectName: String,
        pythonVersion: String?,
    ) {
        writeFileIfMissing(
            File(projectDir, ".gitignore"),
            """
            .venv/
            __pycache__/
            *.pyc
            *.pyo
            *.pyd
            .python-version
            build/
            dist/
            *.egg-info/
            """.trimIndent() + "\n",
        )

        writeFileIfMissing(
            File(projectDir, "README.md"),
            """
            # $projectName

            A Python project managed by pypackpack.

            Use ppp to manage dependencies, targets, and builds.
            """.trimIndent() + "\n",
        )

        writeFileIfMissing(
            File(projectDir, "LICENSE"),
            """
            Copyright (c) ${Year.now().value} $projectName

            All rights reserved.
            """.trimIndent() + "\n",
        )

        pythonVersion?.let { version ->
            File(projectDir, ".python-version").writeText("$version\n")
        }
    }

    private fun writeFileIfMissing(
        file: File,
        content: String,
    ) {
        if (!file.exists()) {
            file.writeText(content)
        }
    }
}

internal object MarkerPolicy {
    fun markerForTarget(target: String): String {
        val descriptor = Platforms.describeTarget(target)
        val system = descriptor.markerSystem
        val machine = descriptor.markerMachine
        return "platform_system == '$system' and platform_machine == '$machine'"
    }
}
