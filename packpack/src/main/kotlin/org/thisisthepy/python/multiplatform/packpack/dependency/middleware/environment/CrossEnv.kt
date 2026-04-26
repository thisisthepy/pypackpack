package org.thisisthepy.python.multiplatform.packpack.dependency.middleware.environment

import kotlinx.coroutines.runBlocking
import org.thisisthepy.python.multiplatform.packpack.dependency.backend.BaseInterface
import org.thisisthepy.python.multiplatform.packpack.dependency.middleware.MarkerPolicy
import org.thisisthepy.python.multiplatform.packpack.dependency.middleware.requireWorkspaceProjectRoot
import org.thisisthepy.python.multiplatform.packpack.utils.Platforms
import org.thisisthepy.python.multiplatform.packpack.utils.toml.TomlEditor
import java.io.File

class CrossEnv {
    private lateinit var backend: BaseInterface
    private val pyprojectFile = "pyproject.toml"
    private val targetWheelInspector = TargetWheelInspector()

    private data class PackageSpec(
        val input: String,
        val name: String,
        val relativePath: String,
        val directory: File,
        val workspaceRoot: File,
    )

    fun initialize(backend: BaseInterface) {
        this.backend = backend
    }

    fun addPackage(
        packageName: String,
        path: String?,
        extraArgs: Map<String, String>?,
    ): Result<String> =
        runCatching {
            val workspaceRoot = resolveWorkspaceRootFromPath(path)
            val workspacePyproject = File(workspaceRoot, pyprojectFile)
            require(workspacePyproject.exists()) {
                "No pyproject.toml found in ${workspaceRoot.absolutePath}. Initialize a project first."
            }

            val packageSpec = resolvePackageSpec(workspaceRoot, packageName)
            val packageDir = packageSpec.directory
            require(!packageDir.exists()) {
                "Package directory already exists: ${packageDir.absolutePath}"
            }
            require(packageDir.mkdirs()) {
                "Failed to create package directory: ${packageDir.absolutePath}"
            }

            val uvArgs =
                mutableMapOf(
                    "bare" to "",
                    "directory" to packageDir.absolutePath,
                )
            extraArgs?.forEach { (key, value) ->
                uvArgs[key] = value
            }

            runBlocking {
                backend.initProject(null, extraArgs = uvArgs)
            }.getOrThrow()

            registerWorkspaceMember(workspacePyproject, packageSpec.relativePath)
            createPackageScaffold(packageSpec)

            inheritWorkspaceTargetsToPackage(
                workspacePyproject = workspacePyproject,
                packagePyproject = File(packageDir, pyprojectFile),
            )

            "Package '${packageSpec.input}' created successfully at ${packageDir.absolutePath}"
        }

    fun removePackage(
        packageName: String,
        path: String?,
    ): Result<String> =
        runCatching {
            val workspaceRoot = resolveWorkspaceRootFromPath(path)
            val workspacePyproject = File(workspaceRoot, pyprojectFile)
            require(workspacePyproject.exists()) {
                "No pyproject.toml found in ${workspaceRoot.absolutePath}"
            }

            val packageSpec = resolvePackageSpec(workspaceRoot, packageName)
            val packageDir = packageSpec.directory
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
                editor.removeFromArray(tablePath = tablePath, key = "members", packageSpec.relativePath)
                workspacePyproject.writeText(editor.toTomlString())
            }

            "Package '${packageSpec.input}' removed successfully from ${workspaceRoot.absolutePath}"
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

            val packageSpec = resolvePackageSpec(packageName)
            val workspaceRoot = packageSpec.workspaceRoot
            val normalizedTargets = resolveCrossTargets(packageSpec, targets)
            val workingArgs = withWorkingDir(extraArgs, workspaceRoot)
            val wheelAvailabilityResult =
                runBlocking {
                    targetWheelInspector.inspectDependencies(
                        workspaceRoot = workspaceRoot,
                        packageRelativePath = packageSpec.relativePath,
                        dependencies = dependencies,
                        targets = normalizedTargets,
                    )
                }

            wheelAvailabilityResult.exceptionOrNull()?.let { error ->
                println("Warning: failed to inspect target wheel availability: ${error.message}")
            }

            val wheelAvailability = wheelAvailabilityResult.getOrDefault(emptyList())

            wheelAvailability.forEach { report ->
                val missingTargets = report.targets.filterNot { it.hasWheel }.map { it.target }
                if (missingTargets.isNotEmpty()) {
                    println(
                        "Warning: '${report.dependencySpec}' has no compatible wheel on PyPI for targets: ${missingTargets.joinToString(
                            ", ",
                        )}",
                    )
                }
            }

            for (target in normalizedTargets) {
                val marker = MarkerPolicy.markerForTarget(target)
                val callArgs = workingArgs + mapOf("marker" to marker)
                runBlocking {
                    backend.addDependencies(packageSpec.name, dependencies, callArgs)
                }.getOrThrow()
            }

            "Added dependencies to package '${packageSpec.input}' for targets: ${normalizedTargets.joinToString(", ")}"
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

            val packageSpec = resolvePackageSpec(packageName)
            val workspaceRoot = packageSpec.workspaceRoot
            val normalizedTargets = resolveCrossTargets(packageSpec, targets)
            val packageDir = packageSpec.directory
            val packagePyproject = File(packageDir, pyprojectFile)
            require(packagePyproject.exists()) {
                "No pyproject.toml found for package '${packageSpec.input}'"
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

            "Removed dependencies from package '${packageSpec.input}' for targets: ${normalizedTargets.joinToString(", ")}"
        }

    fun syncDependencies(
        packageName: String,
        targets: List<String>?,
        extraArgs: Map<String, String>?,
    ): Result<String> =
        runCatching {
            val packageSpec = resolvePackageSpec(packageName)
            val workspaceRoot = packageSpec.workspaceRoot
            val normalizedTargets = normalizeTreeTargets(targets)
            val workingArgs = withWorkingDir(extraArgs, workspaceRoot)

            val syncArgs = workingArgs.filterKeys { it != "python-platform" }
            runBlocking {
                backend.syncDependencies("", syncArgs + mapOf("package" to packageSpec.name))
            }.getOrThrow()

            for (target in normalizedTargets) {
                val treeArgs = workingArgs + mapOf("package" to packageSpec.name, "python-platform" to target)
                runBlocking {
                    backend.showDependencyTree(packageSpec.name, treeArgs)
                }.getOrThrow()
            }

            "Synchronized package '${packageSpec.input}' for targets: ${normalizedTargets.joinToString(", ")}"
        }

    fun showDependencyTree(
        packageName: String,
        targets: List<String>?,
        extraArgs: Map<String, String>?,
    ): Result<String> =
        runCatching {
            val packageSpec = resolvePackageSpec(packageName)
            val normalizedTargets = normalizeTreeTargets(targets)
            val baseDir = packageSpec.workspaceRoot
            val workingArgs = withWorkingDir(extraArgs, baseDir)

            buildString {
                for ((index, target) in normalizedTargets.withIndex()) {
                    val callArgs = workingArgs + mapOf("python-platform" to target)
                    val output =
                        runBlocking {
                            backend.showDependencyTree(packageSpec.name, callArgs)
                        }.getOrThrow()
                    append(output)
                    if (index != normalizedTargets.lastIndex) {
                        append("\n")
                    }
                }
            }
        }

    fun addTargets(
        targets: List<String>,
        path: String?,
    ): Result<String> =
        runCatching {
            require(targets.isNotEmpty()) { "No targets specified" }

            val packageDir = resolvePackageDir(path)
            val packagePyproject = File(packageDir, pyprojectFile)
            require(packagePyproject.exists()) {
                "No pyproject.toml found in ${packageDir.absolutePath}"
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

    fun removeTargets(
        targets: List<String>,
        path: String?,
    ): Result<String> =
        runCatching {
            require(targets.isNotEmpty()) { "No targets specified" }

            val packageDir = resolvePackageDir(path)
            val packagePyproject = File(packageDir, pyprojectFile)
            require(packagePyproject.exists()) {
                "No pyproject.toml found in ${packageDir.absolutePath}"
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

    private fun findWorkspaceRoot(): File = requireWorkspaceProjectRoot()

    private fun normalizeInputTargets(targets: List<String>): List<String> = Platforms.normalizeTargetsOrThrow(targets)

    private fun resolveCrossTargets(
        packageSpec: PackageSpec,
        targets: List<String>?,
    ): List<String> {
        val targetInputs = if (targets.isNullOrEmpty()) readPackageDefaultTargets(packageSpec) else targets
        require(targetInputs.isNotEmpty()) {
            "No targets provided and no default package platforms configured"
        }
        return Platforms.normalizeTargetsOrThrow(targetInputs)
    }

    private fun readPackageDefaultTargets(packageSpec: PackageSpec): List<String> {
        val packagePyproject = File(packageSpec.directory, pyprojectFile)
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

    private fun resolveWorkspaceRootFromPath(path: String?): File {
        val baseDir = path?.let { File(it) } ?: File(System.getProperty("user.dir"))
        require(baseDir.exists() && baseDir.isDirectory) {
            "Invalid path: ${baseDir.absolutePath}"
        }
        val pyproject = File(baseDir, pyprojectFile)
        return if (pyproject.exists()) {
            baseDir
        } else {
            requireWorkspaceProjectRoot(baseDir)
        }
    }

    private fun resolvePackageDir(path: String?): File {
        val packageDir = path?.let { File(it) } ?: File(System.getProperty("user.dir"))
        require(packageDir.exists() && packageDir.isDirectory) {
            "Invalid path: ${packageDir.absolutePath}"
        }
        return packageDir
    }

    private fun resolvePackageSpec(
        workspaceRoot: File,
        packageInput: String,
    ): PackageSpec {
        val trimmed = packageInput.trim()
        require(trimmed.isNotEmpty()) { "Package name cannot be blank" }

        val relativePath =
            trimmed
                .replace('\\', '/')
                .trimStart('/')

        require(relativePath.isNotEmpty()) { "Package name cannot be blank" }

        val packageDir = File(workspaceRoot, relativePath).normalize()
        val workspacePath = workspaceRoot.canonicalFile.toPath()
        val packagePath = packageDir.canonicalFile.toPath()
        require(packagePath.startsWith(workspacePath)) {
            "Package path must stay within workspace: $trimmed"
        }

        val canonicalName = packageDir.name
        require(canonicalName.isNotBlank()) { "Package name cannot be blank" }

        return PackageSpec(
            input = trimmed,
            name = canonicalName,
            relativePath = relativePath,
            directory = packageDir,
            workspaceRoot = workspaceRoot,
        )
    }

    private fun resolvePackageSpec(packageInput: String): PackageSpec {
        val workspaceRoot = findWorkspaceRoot()
        val workspaceMembers = readWorkspaceMembers(workspaceRoot)

        val trimmed = packageInput.trim()
        require(trimmed.isNotEmpty()) { "Package name cannot be blank" }

        val normalizedInput = trimmed.replace('\\', '/').trimStart('/')
        if ('/' in normalizedInput) {
            return resolvePackageSpec(workspaceRoot, normalizedInput)
        }

        val memberMatch =
            workspaceMembers
                .filter { member ->
                    val normalizedMember = member.replace('\\', '/').trimStart('/')
                    normalizedMember == normalizedInput || File(normalizedMember).name == normalizedInput
                }.distinct()

        return when {
            memberMatch.size == 1 -> {
                resolvePackageSpec(workspaceRoot, memberMatch.single())
            }

            memberMatch.size > 1 -> {
                throw IllegalArgumentException(
                    "Package name '$trimmed' is ambiguous. Use one of: ${memberMatch.joinToString(", ")}",
                )
            }

            else -> {
                resolvePackageSpec(workspaceRoot, normalizedInput)
            }
        }
    }

    private fun readWorkspaceMembers(workspaceRoot: File): List<String> {
        val workspacePyproject = File(workspaceRoot, pyprojectFile)
        if (!workspacePyproject.exists()) {
            return emptyList()
        }

        val editor = TomlEditor(workspacePyproject.readText())
        return editor.getArray("tool.uv.workspace", "members")
    }

    private fun registerWorkspaceMember(
        workspacePyproject: File,
        relativePath: String,
    ) {
        val editor = TomlEditor(workspacePyproject.readText())
        val tablePath = "tool.uv.workspace"
        if (!editor.hasTable(tablePath)) {
            editor.createTable(tablePath)
        }
        editor.addToArray(tablePath = tablePath, key = "members", relativePath)
        workspacePyproject.writeText(editor.toTomlString())
    }

    private fun createPackageScaffold(packageSpec: PackageSpec) {
        val packageDir = packageSpec.directory
        val importName = packageSpec.name.toImportName()

        writeFileIfMissing(
            File(packageDir, "README.md"),
            "# ${packageSpec.name}\n",
        )

        writeFileIfMissing(
            File(packageDir, "src/main/$importName/__init__.py"),
            "",
        )

        writeFileIfMissing(
            File(packageDir, "src/test/test_import.py"),
            """
            import $importName

            def test_import():
                assert $importName is not None
            """.trimIndent() + "\n",
        )

        createDirectory(File(packageDir, "build"))
        createDirectory(File(packageDir, "build/crossenv"))
        createDirectory(File(packageDir, "build/packpack"))
    }

    private fun writeFileIfMissing(
        file: File,
        content: String,
    ) {
        file.parentFile?.mkdirs()
        if (!file.exists()) {
            file.writeText(content)
        }
    }

    private fun createDirectory(directory: File) {
        if (!directory.exists()) {
            require(directory.mkdirs()) {
                "Failed to create directory: ${directory.absolutePath}"
            }
        }
    }

    private fun String.toImportName(): String {
        val sanitized = replace(Regex("[^A-Za-z0-9_]"), "_")
        val withoutLeadingDigits = sanitized.replace(Regex("^[0-9]+"), "")
        return withoutLeadingDigits.ifBlank { "package_module" }
    }

    private fun inheritWorkspaceTargetsToPackage(
        workspacePyproject: File,
        packagePyproject: File,
    ) {
        if (!workspacePyproject.exists() || !packagePyproject.exists()) {
            return
        }

        val workspaceEditor = TomlEditor(workspacePyproject.readText())
        val workspaceTargets = workspaceEditor.getArray("tool.ppp.dependencies", "platforms")
        if (workspaceTargets.isEmpty()) {
            return
        }

        val packageEditor = TomlEditor(packagePyproject.readText())
        val tablePath = "tool.ppp.dependencies"
        if (!packageEditor.hasTable(tablePath)) {
            packageEditor.createTable(tablePath)
        }

        val existingTargets = packageEditor.getArray(tablePath, "platforms")
        if (existingTargets.isNotEmpty()) {
            return
        }

        packageEditor.addToArray(
            tablePath = tablePath,
            key = "platforms",
            *workspaceTargets.toTypedArray(),
            sorter = { Platforms.sort(it) },
        )
        packagePyproject.writeText(packageEditor.toTomlString())
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
