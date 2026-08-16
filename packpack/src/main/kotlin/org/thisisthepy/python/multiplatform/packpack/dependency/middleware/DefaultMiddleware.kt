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
import org.thisisthepy.python.multiplatform.packpack.dependency.backend.BackendInterface

private const val PYPROJECT_FILE = "pyproject.toml"

/** Default middleware implementation */
class DefaultMiddleware : MiddlewareInterface {
    private lateinit var backend: BackendInterface
    private lateinit var devEnv: DevEnv
    private lateinit var crossEnv: CrossEnv

    /** Initialize middleware */
    override fun initialize() {
        if (!::backend.isInitialized) {
            backend = BackendInterface.create(BackendType.UV)
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

    override fun installPythonVersion(pythonVersion: String, targetPlatform: String?): Result<String> = devEnvService().installPythonVersion(pythonVersion, targetPlatform)

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

    /**
     * `sys.platform`-style spelling for each `platform.system()`-style
     * [Platforms.TargetDescriptor.markerSystem] value.
     *
     * `uv add --marker "<text from [markerForTarget]>"` does not persist that text verbatim into
     * `pyproject.toml`: it rewrites the `platform_system == '<value>'` clause into an equivalent
     * `sys_platform == '<value>'` clause and reorders the clauses alphabetically (verified by hand
     * against uv 0.12.3 -- `platform_system == 'Windows' and platform_machine == 'x86_64'` round-trips
     * as `platform_machine == 'x86_64' and sys_platform == 'win32'`; likewise Linux/Darwin/Android/
     * Emscripten). `iOS` was the one family that round-tripped unchanged in that same check, so
     * [targetKeyFor] treats both spellings as equivalent for every family rather than special-casing
     * iOS -- a future uv version normalizing it the same way should not silently break matching again.
     *
     * This is what made `removeDependencies` fail to find a target-scoped dependency it had itself
     * added (docs/SPEC.md's "remove --target" limitation, docs/KNOWN_ISSUES.md): it compared the
     * literal text [markerForTarget] recomputes against whatever uv actually wrote, and those two
     * strings disagree.
     */
    private val SYS_PLATFORM_BY_MARKER_SYSTEM: Map<String, String> =
        mapOf(
            "Windows" to "win32",
            "Linux" to "linux",
            "Darwin" to "darwin",
            "Android" to "android",
            "Emscripten" to "emscripten",
            "iOS" to "ios",
        )

    private val MARKER_SYSTEM_BY_SYS_PLATFORM: Map<String, String> =
        SYS_PLATFORM_BY_MARKER_SYSTEM.entries.associate { (markerSystemValue, sysPlatformValue) ->
            sysPlatformValue to markerSystemValue
        }

    /**
     * Parses a persisted PEP 508 marker string (as read back from `project.dependencies`) into the
     * (family, machine) pair it constrains -- tolerant of both the `platform_system`/
     * `platform_machine` spelling [markerForTarget] writes and the `sys_platform`/`platform_machine`
     * spelling `uv add` actually persists (see [SYS_PLATFORM_BY_MARKER_SYSTEM]), and tolerant of
     * clause order. Returns null when the marker does not contain a recognizable system clause and
     * machine clause (e.g. hand-written markers outside this codebase's own convention).
     */
    fun targetKeyFor(markerText: String): Pair<String, String>? {
        val clauses =
            Regex("(platform_system|sys_platform|platform_machine)\\s*==\\s*'([^']*)'")
                .findAll(markerText)
                .associate { it.groupValues[1] to it.groupValues[2] }

        val machine = clauses["platform_machine"] ?: return null
        val system =
            clauses["platform_system"]
                ?: clauses["sys_platform"]?.let { MARKER_SYSTEM_BY_SYS_PLATFORM[it] }
                ?: return null

        return system to machine
    }

    /** The (family, machine) key [targetKeyFor] would parse back out of [markerForTarget]'s own output. */
    fun targetKeyForTarget(target: String): Pair<String, String> {
        val descriptor = Platforms.describeTarget(target)
        return descriptor.markerSystem to descriptor.markerMachine
    }
}
