package org.thisisthepy.python.multiplatform.packpack.dependency.middleware.environment

import kotlinx.coroutines.runBlocking
import org.thisisthepy.python.multiplatform.packpack.dependency.backend.BaseInterface
import org.thisisthepy.python.multiplatform.packpack.dependency.middleware.MarkerPolicy
import org.thisisthepy.python.multiplatform.packpack.utils.Platforms
import org.thisisthepy.python.multiplatform.packpack.utils.findProjectRoot
import org.thisisthepy.python.multiplatform.packpack.utils.readWorkspaceMembers
import org.thisisthepy.python.multiplatform.packpack.utils.toml.TomlEditor
import java.io.File

private const val PYPROJECT_FILE = "pyproject.toml"

class CrossEnv {
    private lateinit var backend: BaseInterface

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
            val workspacePyproject = File(workspaceRoot, PYPROJECT_FILE)
            require(workspacePyproject.exists()) {
                "No pyproject.toml found in ${workspaceRoot.absolutePath}. Initialize a project first."
            }

            val packageSpec =
                resolvePackageSpec(
                    packageInput = packageName,
                    workspaceRoot = workspaceRoot,
                    matchWorkspaceMembers = false,
                )
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
                backend.initProject(null, extraArgs = uvArgs, workingDir = workspaceRoot)
            }.getOrThrow()

            createPackageScaffold(packageSpec)

            syncWorkspaceTargetsToPackage(
                workspacePyproject = workspacePyproject,
                packagePyproject = File(packageDir, PYPROJECT_FILE),
            )

            "Package '${packageSpec.input}' created successfully at ${packageDir.absolutePath}"
        }

    fun removePackage(
        packageName: String,
        path: String?,
    ): Result<String> =
        runCatching {
            val workspaceRoot = resolveWorkspaceRootFromPath(path)
            val workspacePyproject = File(workspaceRoot, PYPROJECT_FILE)
            require(workspacePyproject.exists()) {
                "No pyproject.toml found in ${workspaceRoot.absolutePath}"
            }

            val packageSpec =
                resolvePackageSpec(
                    packageInput = packageName,
                    workspaceRoot = workspaceRoot,
                    matchWorkspaceMembers = false,
                )
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
            val options = extraArgs.orEmpty()

            for (target in normalizedTargets) {
                val marker = MarkerPolicy.markerForTarget(target)
                val callArgs = options + mapOf("marker" to marker)
                runBlocking {
                    backend.addDependencies(packageSpec.name, dependencies, callArgs, workspaceRoot)
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
            val packagePyproject = File(packageDir, PYPROJECT_FILE)
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
            val normalizedTargets = Platforms.normalizeTargetsOrThrow(targets, listOf(Platforms.detectHostTarget()))
            val options = extraArgs.orEmpty()

            val syncArgs = options.filterKeys { it != "python-platform" }
            runBlocking {
                backend.syncDependencies("", syncArgs + mapOf("package" to packageSpec.name), workspaceRoot)
            }.getOrThrow()

            for (target in normalizedTargets) {
                val treeArgs = options + mapOf("package" to packageSpec.name, "python-platform" to target)
                runBlocking {
                    backend.showDependencyTree(packageSpec.name, treeArgs, workspaceRoot)
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
            val normalizedTargets = Platforms.normalizeTargetsOrThrow(targets, listOf(Platforms.detectHostTarget()))
            val baseDir = packageSpec.workspaceRoot
            val options = extraArgs.orEmpty()

            buildString {
                for ((index, target) in normalizedTargets.withIndex()) {
                    val callArgs = options + mapOf("python-platform" to target)
                    val output =
                        runBlocking {
                            backend.showDependencyTree(packageSpec.name, callArgs, baseDir)
                        }.getOrThrow()
                    append(output)
                    if (index != normalizedTargets.lastIndex) {
                        append("\n")
                    }
                }
            }
        }

    fun addTargets(
        packageName: String?,
        targets: List<String>,
    ): Result<String> =
        runCatching {
            require(targets.isNotEmpty()) { "No targets specified" }

            val packageSpec = packageName?.let { resolvePackageSpec(it) }
            val packageDir = packageSpec?.directory ?: resolveCurrentPackageDir()
            val workspaceRoot = packageSpec?.workspaceRoot ?: resolveWorkspaceRootFromPath(null)
            val packagePyproject = File(packageDir, PYPROJECT_FILE)
            require(packagePyproject.exists()) {
                "No pyproject.toml found in ${packageDir.absolutePath}"
            }

            val normalizedTargets = Platforms.normalizeTargetsOrThrow(targets)
            addTargetsToPackage(packagePyproject, normalizedTargets)
            val workspaceMembers = readWorkspaceMembers(packageDir)
            if (workspaceMembers.isEmpty() && packageDir != workspaceRoot) {
                createTargetSourceFolders(packageDir, normalizedTargets)
            }
            workspaceMembers.forEach { member ->
                val memberPyproject = File(packageDir, member.replace('\\', '/')).resolve(PYPROJECT_FILE)
                addTargetsToPackage(memberPyproject, normalizedTargets)
                createTargetSourceFolders(memberPyproject.parentFile, normalizedTargets)
            }

            "Successfully added targets: ${Platforms.sort(normalizedTargets).joinToString(", ")}"
        }

    fun removeTargets(
        packageName: String?,
        targets: List<String>,
    ): Result<String> =
        runCatching {
            require(targets.isNotEmpty()) { "No targets specified" }

            val packageSpec = packageName?.let { resolvePackageSpec(it) }
            val packageDir = packageSpec?.directory ?: resolveCurrentPackageDir()
            val packagePyproject = File(packageDir, PYPROJECT_FILE)
            require(packagePyproject.exists()) {
                "No pyproject.toml found in ${packageDir.absolutePath}"
            }

            val normalizedTargets = Platforms.normalizeTargetsOrThrow(targets)
            removeTargetsFromPackage(packagePyproject, normalizedTargets)
            readWorkspaceMembers(packageDir).forEach { member ->
                val memberPyproject = File(packageDir, member.replace('\\', '/')).resolve(PYPROJECT_FILE)
                removeTargetsFromPackage(memberPyproject, normalizedTargets)
            }

            "Successfully removed targets: ${Platforms.sort(normalizedTargets).joinToString(", ")}"
        }

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
        val packagePyproject = File(packageSpec.directory, PYPROJECT_FILE)
        if (!packagePyproject.exists()) {
            return emptyList()
        }

        val editor = TomlEditor(packagePyproject.readText())
        return editor.getArray("tool.ppp.dependencies", "platforms")
    }

    private fun resolveWorkspaceRootFromPath(path: String?): File {
        val baseDir = path?.let { File(it) } ?: File(System.getProperty("user.dir"))
        require(baseDir.exists() && baseDir.isDirectory) {
            "Invalid path: ${baseDir.absolutePath}"
        }
        val pyproject = File(baseDir, PYPROJECT_FILE)
        return if (pyproject.exists()) {
            baseDir
        } else {
            findProjectRoot(baseDir)
                ?: throw IllegalStateException("No pyproject.toml found in current directory or parent directories")
        }
    }

    private fun resolveCurrentPackageDir(): File {
        val packageDir = File(System.getProperty("user.dir"))
        require(packageDir.exists() && packageDir.isDirectory) {
            "Invalid path: ${packageDir.absolutePath}"
        }
        return packageDir
    }

    private fun resolvePackageSpec(
        packageInput: String,
        workspaceRoot: File? = null,
        matchWorkspaceMembers: Boolean = workspaceRoot == null,
    ): PackageSpec {
        val resolvedWorkspaceRoot =
            workspaceRoot
                ?: findProjectRoot()
                ?: throw IllegalStateException("No pyproject.toml found in current directory or parent directories")
        val trimmed = packageInput.trim()
        require(trimmed.isNotEmpty()) { "Package name cannot be blank" }

        val normalizedInput = trimmed.replace('\\', '/').trimStart('/')
        require(normalizedInput.isNotEmpty()) { "Package name cannot be blank" }

        val relativePath =
            if (matchWorkspaceMembers && '/' !in normalizedInput) {
                val memberMatch =
                    readWorkspaceMembers(resolvedWorkspaceRoot)
                        .filter { member ->
                            val normalizedMember = member.replace('\\', '/').trimStart('/')
                            normalizedMember == normalizedInput || File(normalizedMember).name == normalizedInput
                        }.distinct()

                when {
                    memberMatch.size == 1 -> {
                        memberMatch.single().replace('\\', '/').trimStart('/')
                    }

                    memberMatch.size > 1 -> {
                        throw IllegalArgumentException(
                            "Package name '$trimmed' is ambiguous. Use one of: ${memberMatch.joinToString(", ")}",
                        )
                    }

                    else -> {
                        normalizedInput
                    }
                }
            } else {
                normalizedInput
            }

        val packageDir = File(resolvedWorkspaceRoot, relativePath).normalize()
        val workspacePath = resolvedWorkspaceRoot.canonicalFile.toPath()
        val packagePath = packageDir.canonicalFile.toPath()
        require(packagePath.startsWith(workspacePath)) {
            "Package path must stay within workspace: $trimmed"
        }

        val canonicalName = packageDir.name
        require(canonicalName.isNotBlank()) { "Package name cannot be blank" }

        return PackageSpec(
            input = if (matchWorkspaceMembers) relativePath else trimmed,
            name = canonicalName,
            relativePath = relativePath,
            directory = packageDir,
            workspaceRoot = resolvedWorkspaceRoot,
        )
    }

    private fun createPackageScaffold(packageSpec: PackageSpec) {
        val packageDir = packageSpec.directory
        val importName = packageSpec.name.toImportName()

        writeFileIfMissing(
            File(packageDir, "README.md"),
            "# ${packageSpec.name}\n",
        )

        writeFileIfMissing(
            File(packageDir, "src/main/__init__.py"),
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

    private fun syncWorkspaceTargetsToPackage(
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
        val existingTargets = packageEditor.getArray("tool.ppp.dependencies", "platforms")
        if (existingTargets.isEmpty()) {
            addTargetsToPackage(packagePyproject, workspaceTargets)
            createTargetSourceFolders(packagePyproject.parentFile, workspaceTargets)
        }
    }

    private fun addTargetsToPackage(
        packagePyproject: File,
        targets: List<String>,
    ) {
        if (!packagePyproject.exists()) {
            return
        }

        val packageEditor = TomlEditor(packagePyproject.readText())
        val tablePath = "tool.ppp.dependencies"
        if (!packageEditor.hasTable(tablePath)) {
            packageEditor.createTable(tablePath)
        }

        packageEditor.addToArray(
            tablePath = tablePath,
            key = "platforms",
            *targets.toTypedArray(),
            sorter = { Platforms.sort(it) },
        )
        packagePyproject.writeText(packageEditor.toTomlString())
    }

    private fun createTargetSourceFolders(
        packageDir: File?,
        targets: List<String>,
    ) {
        if (packageDir == null) {
            return
        }
        targets
            .map { Platforms.getPlatformFamily(it) }
            .distinct()
            .forEach { family ->
                writeFileIfMissing(File(packageDir, "src/$family/__init__.py"), "")
            }
    }

    private fun removeTargetsFromPackage(
        packagePyproject: File,
        targets: List<String>,
    ) {
        if (!packagePyproject.exists()) {
            return
        }

        val packageEditor = TomlEditor(packagePyproject.readText())
        val tablePath = "tool.ppp.dependencies"
        if (!packageEditor.hasTable(tablePath)) {
            return
        }

        packageEditor.removeFromArray(
            tablePath = tablePath,
            key = "platforms",
            *targets.toTypedArray(),
        )
        packagePyproject.writeText(packageEditor.toTomlString())
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
