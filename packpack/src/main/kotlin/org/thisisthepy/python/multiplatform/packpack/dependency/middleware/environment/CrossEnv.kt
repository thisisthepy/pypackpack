package org.thisisthepy.python.multiplatform.packpack.dependency.middleware.environment

import kotlinx.coroutines.runBlocking
import org.thisisthepy.python.multiplatform.packpack.dependency.backend.BaseInterface
import org.thisisthepy.python.multiplatform.packpack.dependency.middleware.MarkerPolicy
import org.thisisthepy.python.multiplatform.packpack.utils.Platforms
import org.thisisthepy.python.multiplatform.packpack.utils.toml.TomlEditor
import java.io.File

class CrossEnv {
    private lateinit var backend: BaseInterface
    private val pyprojectFile = "pyproject.toml"

    fun initialize(backend: BaseInterface) {
        this.backend = backend
    }

    fun addPackage(packageName: String): Result<String> = Result.success("Add package feature not yet implemented.")

    fun removePackage(packageName: String): Result<String> = Result.success("Remove package feature not yet implemented.")

    fun addTarget(
        packageName: String,
        targets: List<String>,
    ): Result<String> = Result.success("Add target feature not yet implemented.")

    fun removeTarget(
        packageName: String,
        targets: List<String>,
    ): Result<String> = Result.success("Remove target feature not yet implemented.")

    fun addDependencies(
        packageName: String,
        dependencies: List<String>,
        targets: List<String>?,
        extraArgs: Map<String, String>?,
    ): Result<String> =
        runCatching {
            require(dependencies.isNotEmpty()) { "No dependencies specified" }
            require(dependencies.none { it.contains(';') }) {
                "Dependency markers in package names are not allowed. Use --target instead."
            }

            val workspaceRoot = resolveWorkspaceRoot(packageName)
            val normalizedTargets = resolveCrossTargets(packageName, targets, workspaceRoot)
            val workingArgs = withWorkingDir(extraArgs, workspaceRoot)

            for (target in normalizedTargets) {
                val marker = MarkerPolicy.markerForTarget(target)
                val callArgs = workingArgs + mapOf("marker" to marker)
                runBlocking {
                    backend.addDependencies(packageName, dependencies, callArgs)
                }.getOrThrow()
            }

            "Added dependencies to package '$packageName' for targets: ${normalizedTargets.joinToString(", ")}" 
        }

    fun removeDependencies(
        packageName: String,
        dependencies: List<String>,
        targets: List<String>?,
        extraArgs: Map<String, String>?,
    ): Result<String> =
        runCatching {
            require(dependencies.isNotEmpty()) { "No dependencies specified" }
            require(dependencies.none { it.contains(';') }) {
                "Dependency markers in package names are not allowed. Use --target instead."
            }

            val workspaceRoot = resolveWorkspaceRoot(packageName)
            val normalizedTargets = resolveCrossTargets(packageName, targets, workspaceRoot)
            val packageDir = File(workspaceRoot, packageName)
            val packagePyproject = File(packageDir, pyprojectFile)
            require(packagePyproject.exists()) {
                "No pyproject.toml found for package '$packageName'"
            }

            val editor = TomlEditor(packagePyproject.readText())
            val entries = editor.getArray("project", "dependencies")
            val requested = dependencies.map { extractDependencyName(it) }.toSet()
            val markers = normalizedTargets.map { MarkerPolicy.markerForTarget(it) }.toSet()

            val filtered =
                entries.filterNot { entry ->
                    val depName = extractDependencyName(entry)
                    val marker = extractDependencyMarker(entry)
                    depName in requested && marker != null && marker in markers
                }

            if (filtered.size == entries.size) {
                throw IllegalStateException("No matching target-scoped dependencies found to remove")
            }

            editor.setArray("project", "dependencies", filtered)
            packagePyproject.writeText(editor.toTomlString())

            runBlocking {
                backend.lockDependencies(workspaceRoot.absolutePath)
            }.getOrThrow()

            "Removed dependencies from package '$packageName' for targets: ${normalizedTargets.joinToString(", ")}" 
        }

    fun syncDependencies(
        packageName: String,
        targets: List<String>?,
        extraArgs: Map<String, String>?,
    ): Result<String> =
        runCatching {
            val workspaceRoot = resolveWorkspaceRoot(packageName)
            val normalizedTargets = normalizeTreeTargets(targets)
            val workingArgs = withWorkingDir(extraArgs, workspaceRoot)

            val syncArgs = workingArgs.filterKeys { it != "python-platform" }
            runBlocking {
                backend.syncDependencies("", syncArgs + mapOf("package" to packageName))
            }.getOrThrow()

            for (target in normalizedTargets) {
                val treeArgs = workingArgs + mapOf("package" to packageName, "python-platform" to target)
                runBlocking {
                    backend.showDependencyTree(packageName, treeArgs)
                }.getOrThrow()
            }

            "Synchronized package '$packageName' for targets: ${normalizedTargets.joinToString(", ")}" 
        }

    fun showDependencyTree(
        packageName: String,
        targets: List<String>?,
        extraArgs: Map<String, String>?,
    ): Result<String> =
        runCatching {
            val normalizedTargets = normalizeTreeTargets(targets)
            val baseDir = resolveWorkspaceRoot(packageName)
            val workingArgs = withWorkingDir(extraArgs, baseDir)

            buildString {
                for ((index, target) in normalizedTargets.withIndex()) {
                    val callArgs = workingArgs + mapOf("python-platform" to target)
                    val output =
                        runBlocking {
                            backend.showDependencyTree(packageName, callArgs)
                        }.getOrThrow()
                    append(output)
                    if (index != normalizedTargets.lastIndex) {
                        append("\n")
                    }
                }
            }
        }

    private fun resolveWorkspaceRoot(packageName: String): File {
        var dir = File(System.getProperty("user.dir"))
        while (true) {
            val pyproject = File(dir, pyprojectFile)
            val packageDir = File(dir, packageName)
            if (pyproject.exists() && packageDir.exists() && packageDir.isDirectory) {
                return dir
            }

            val parent = dir.parentFile
                ?: throw IllegalStateException("Unable to resolve workspace root for package '$packageName'")
            dir = parent
        }
    }

    private fun resolveCrossTargets(
        packageName: String,
        targets: List<String>?,
        workspaceRoot: File,
    ): List<String> {
        val targetInputs = if (targets.isNullOrEmpty()) readPackageDefaultTargets(packageName, workspaceRoot) else targets
        require(targetInputs.isNotEmpty()) {
            "No targets provided and no default package platforms configured"
        }
        return targetInputs
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { target ->
                Platforms.normalizeTarget(target)
                    ?: throw IllegalArgumentException("Unsupported target: $target")
            }
            .distinct()
    }

    private fun readPackageDefaultTargets(
        packageName: String,
        workspaceRoot: File,
    ): List<String> {
        val packagePyproject = File(File(workspaceRoot, packageName), pyprojectFile)
        if (!packagePyproject.exists()) {
            return emptyList()
        }

        val editor = TomlEditor(packagePyproject.readText())
        return editor.getArray("tool.ppp.dependencies", "platforms")
    }

    private fun normalizeTreeTargets(targets: List<String>?): List<String> {
        if (targets.isNullOrEmpty()) {
            return listOf(Platforms.detectHostTarget())
        }

        return targets
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { target ->
                Platforms.normalizeTarget(target)
                    ?: throw IllegalArgumentException("Unsupported target: $target")
            }
            .distinct()
    }

    private fun withWorkingDir(
        extraArgs: Map<String, String>?,
        directory: File,
    ): Map<String, String> {
        val base = extraArgs?.toMutableMap() ?: mutableMapOf()
        base["__working_dir"] = directory.absolutePath
        return base
    }

    private fun extractDependencyName(spec: String): String {
        val requirement = spec.substringBefore(';').trim()
        val match =
            Regex("^[A-Za-z0-9_.-]+")
                .find(requirement)
                ?: throw IllegalArgumentException("Invalid dependency spec: $spec")
        return match.value.lowercase()
    }

    private fun extractDependencyMarker(spec: String): String? {
        val index = spec.indexOf(';')
        if (index == -1) {
            return null
        }
        return spec.substring(index + 1).trim()
    }
}
