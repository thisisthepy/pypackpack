package org.thisisthepy.python.multiplatform.packpack.dependency.middleware.environment

import kotlinx.coroutines.runBlocking
import org.thisisthepy.python.multiplatform.packpack.dependency.backend.BaseInterface
import org.thisisthepy.python.multiplatform.packpack.dependency.middleware.MarkerPolicy
import org.thisisthepy.python.multiplatform.packpack.dependency.middleware.internal.WorkspacePaths
import org.thisisthepy.python.multiplatform.packpack.utils.Platforms
import org.thisisthepy.python.multiplatform.packpack.utils.toml.TomlEditor
import java.io.File

class CrossEnv {
    private lateinit var backend: BaseInterface
    private val pyprojectFile = "pyproject.toml"

    private data class PackageSpec(
        val input: String,
        val name: String,
        val relativePath: String,
        val directory: File,
    )

    fun initialize(backend: BaseInterface) {
        this.backend = backend
    }

    fun addPackage(packageName: String): Result<String> =
        runCatching {
            require(packageName.isNotBlank()) { "Package name cannot be blank" }

            val workspaceRoot = findWorkspaceRoot()
            val workspacePyproject = File(workspaceRoot, pyprojectFile)
            require(workspacePyproject.exists()) {
                "No pyproject.toml found in ${workspaceRoot.absolutePath}. Initialize a project first."
            }

            val packageDir = File(workspaceRoot, packageName)
            require(!packageDir.exists()) {
                "Package directory already exists: ${packageDir.absolutePath}"
            }
            require(packageDir.mkdirs()) {
                "Failed to create package directory: ${packageDir.absolutePath}"
            }

            val extraArgs =
                mapOf(
                    "package" to "",
                    "directory" to packageDir.absolutePath,
                )
            runBlocking {
                backend.initProject(null, extraArgs = extraArgs)
            }.getOrThrow()

            "Package '$packageName' created successfully at ${packageDir.absolutePath}"
        }

    fun removePackage(packageName: String): Result<String> =
        runCatching {
            require(packageName.isNotBlank()) { "Package name cannot be blank" }

            val workspaceRoot = findWorkspaceRoot()
            val workspacePyproject = File(workspaceRoot, pyprojectFile)
            require(workspacePyproject.exists()) {
                "No pyproject.toml found in ${workspaceRoot.absolutePath}"
            }

            val packageDir = File(workspaceRoot, packageName)
            require(packageDir.exists()) {
                "Package directory not found: ${packageDir.absolutePath}"
            }

            val deleted = packageDir.deleteRecursively()
            require(deleted) {
                "Failed to delete package directory: ${packageDir.absolutePath}"
            }

            val editor = TomlEditor(workspacePyproject.readText())
            val tablePath = "tool.uv.workspace"
            if (editor.hasTable(tablePath)) {
                editor.removeFromArray(tablePath = tablePath, key = "members", packageName)
                workspacePyproject.writeText(editor.toTomlString())
            }

            "Package '$packageName' removed successfully from ${workspaceRoot.absolutePath}"
        }

    fun addTarget(
        packageName: String,
        targets: List<String>,
    ): Result<String> =
        runCatching {
            require(targets.isNotEmpty()) { "No targets specified" }

            val workspaceRoot = findWorkspaceRoot()
            val packagePyproject = packagePyprojectPath(workspaceRoot, packageName)
            require(packagePyproject.exists()) {
                "No pyproject.toml found in package '$packageName'"
            }

            val normalizedTargets = normalizeInputTargets(targets)

            val editor = TomlEditor(packagePyproject.readText())
            val tablePath = "tool.ppp.dependencies"
            if (!editor.hasTable(tablePath)) {
                editor.createTable(tablePath)
            }

            editor.addToArray(
                tablePath = tablePath,
                key = "platforms",
                *normalizedTargets.toTypedArray(),
                sorter = { Platforms.sort(it) },
            )
            packagePyproject.writeText(editor.toTomlString())

            "Successfully added targets: ${Platforms.sort(normalizedTargets).joinToString(", ")}"
        }

    fun removeTarget(
        packageName: String,
        targets: List<String>,
    ): Result<String> =
        runCatching {
            require(targets.isNotEmpty()) { "No targets specified" }

            val workspaceRoot = findWorkspaceRoot()
            val packagePyproject = packagePyprojectPath(workspaceRoot, packageName)
            require(packagePyproject.exists()) {
                "No pyproject.toml found in package '$packageName'"
            }

            val normalizedTargets = normalizeInputTargets(targets)

            val editor = TomlEditor(packagePyproject.readText())
            val tablePath = "tool.ppp.dependencies"
            if (editor.hasTable(tablePath)) {
                editor.removeFromArray(
                    tablePath = tablePath,
                    key = "platforms",
                    *normalizedTargets.toTypedArray(),
                )
                packagePyproject.writeText(editor.toTomlString())
            }

            "Successfully removed targets: ${Platforms.sort(normalizedTargets).joinToString(", ")}"
        }

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

    private fun resolveWorkspaceRoot(packageName: String): File = WorkspacePaths.resolveWorkspaceRootForPackage(packageName)

    private fun findWorkspaceRoot(): File = WorkspacePaths.requireProjectRoot()

    private fun packagePyprojectPath(
        workspaceRoot: File,
        packageName: String,
    ): File {
        val packageDir = File(workspaceRoot, packageName)
        require(packageDir.exists() && packageDir.isDirectory) {
            "Package directory not found: ${packageDir.absolutePath}"
        }
        return File(packageDir, pyprojectFile)
    }

    private fun normalizeInputTargets(targets: List<String>): List<String> = Platforms.normalizeTargetsOrThrow(targets)

    private fun resolveCrossTargets(
        packageName: String,
        targets: List<String>?,
        workspaceRoot: File,
    ): List<String> {
        val targetInputs = if (targets.isNullOrEmpty()) readPackageDefaultTargets(packageName, workspaceRoot) else targets
        require(targetInputs.isNotEmpty()) {
            "No targets provided and no default package platforms configured"
        }
        return Platforms.normalizeTargetsOrThrow(targetInputs)
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
        val defaults = listOf(Platforms.detectHostTarget())
        return Platforms.normalizeTargetsOrThrow(targets, defaultTargets = defaults)
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
