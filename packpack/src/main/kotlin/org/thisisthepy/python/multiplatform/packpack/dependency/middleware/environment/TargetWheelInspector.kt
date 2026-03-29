package org.thisisthepy.python.multiplatform.packpack.dependency.middleware.environment

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.thisisthepy.python.multiplatform.packpack.dependency.backend.external.UV
import org.thisisthepy.python.multiplatform.packpack.dependency.middleware.MarkerPolicy
import org.thisisthepy.python.multiplatform.packpack.utils.toml.TomlEditor
import java.io.File
import java.nio.file.Files

internal data class DependencyWheelAvailability(
    val dependencySpec: String,
    val normalizedPackageName: String,
    val resolvedVersion: String?,
    val targets: List<TargetWheelAvailability>,
)

internal data class TargetWheelAvailability(
    val target: String,
    val hasWheel: Boolean,
    val matchedFiles: List<String>,
)

internal class TargetWheelInspector(
    private val uv: UV = UV(),
) {
    suspend fun inspectDependencies(
        workspaceRoot: File,
        packageRelativePath: String,
        dependencies: List<String>,
        targets: List<String>,
    ): Result<List<DependencyWheelAvailability>> =
        runCatching {
            require(workspaceRoot.exists() && workspaceRoot.isDirectory) {
                "Workspace root not found: ${workspaceRoot.absolutePath}"
            }
            require(dependencies.isNotEmpty()) { "No dependencies specified" }
            require(targets.isNotEmpty()) { "No targets specified" }

            val reportsByDependency =
                dependencies.associate { spec ->
                    parseDependencySpec(spec).normalizedName to
                        DependencyWheelAvailability(
                            dependencySpec = spec,
                            normalizedPackageName = parseDependencySpec(spec).normalizedName,
                            resolvedVersion = null,
                            targets = emptyList(),
                        )
                }.toMutableMap()

            val targetReports = mutableMapOf<String, MutableList<TargetWheelAvailability>>()
            val resolvedVersions = mutableMapOf<String, String?>()

            for (target in targets) {
                val lockPackages =
                    resolveLockPackagesForTarget(
                        workspaceRoot = workspaceRoot,
                        packageRelativePath = packageRelativePath,
                        dependencies = dependencies,
                        target = target,
                    )

                for (dependency in dependencies) {
                    val parsed = parseDependencySpec(dependency)
                    val packageEntries = lockPackages.filter { it.name == parsed.normalizedName }
                    val resolvedVersion = packageEntries.firstOrNull()?.version
                    if (resolvedVersion != null) {
                        resolvedVersions.putIfAbsent(parsed.normalizedName, resolvedVersion)
                    }

                    val matchedFiles =
                        packageEntries
                            .flatMap { it.wheelUrls }
                            .map { it.substringAfterLast('/') }
                            .distinct()
                            .filter { isWheelCompatibleWithTarget(it, target) }

                    targetReports
                        .getOrPut(parsed.normalizedName) { mutableListOf() }
                        .add(
                            TargetWheelAvailability(
                                target = target,
                                hasWheel = matchedFiles.isNotEmpty(),
                                matchedFiles = matchedFiles,
                            ),
                        )
                }
            }

            dependencies.map { dependency ->
                val parsed = parseDependencySpec(dependency)
                DependencyWheelAvailability(
                    dependencySpec = dependency,
                    normalizedPackageName = parsed.normalizedName,
                    resolvedVersion = resolvedVersions[parsed.normalizedName],
                    targets = targetReports[parsed.normalizedName].orEmpty(),
                )
            }
        }

    private suspend fun resolveLockPackagesForTarget(
        workspaceRoot: File,
        packageRelativePath: String,
        dependencies: List<String>,
        target: String,
    ): List<ResolvedLockPackage> =
        withContext(Dispatchers.IO) {
            val tempRoot = Files.createTempDirectory("ppp-target-resolve").toFile()
            try {
                copyWorkspaceForInspection(workspaceRoot, tempRoot)

                val packagePyproject = File(tempRoot, "$packageRelativePath/pyproject.toml")
                require(packagePyproject.exists()) {
                    "Package pyproject.toml not found for inspection: ${packagePyproject.absolutePath}"
                }

                val editor = TomlEditor(packagePyproject.readText())
                dependencies.forEach { dependency ->
                    editor.addToArray(
                        tablePath = "project",
                        key = "dependencies",
                        appendTargetMarker(dependency, target),
                    )
                }
                packagePyproject.writeText(editor.toTomlString())

                val result = uv.executeCommand(listOf("lock"), tempRoot)
                if (result.first != 0) {
                    throw IllegalStateException("Failed to resolve dependencies for target '$target': ${result.second}")
                }

                val lockFile = File(tempRoot, "uv.lock")
                require(lockFile.exists()) { "uv.lock was not generated for target '$target'" }
                parseLockPackages(lockFile.readText())
            } finally {
                tempRoot.deleteRecursively()
            }
        }

    private fun copyWorkspaceForInspection(
        sourceRoot: File,
        targetRoot: File,
    ) {
        sourceRoot
            .listFiles()
            .orEmpty()
            .filterNot { shouldSkipCopy(it.name) }
            .forEach { source ->
                val destination = File(targetRoot, source.name)
                if (source.isDirectory) {
                    source.copyRecursively(destination, overwrite = true)
                } else {
                    source.copyTo(destination, overwrite = true)
                }
            }
    }

    private fun shouldSkipCopy(name: String): Boolean =
        name == ".git" ||
            name == ".gradle" ||
            name == "build" ||
            name == ".idea"

    private fun appendTargetMarker(
        dependency: String,
        target: String,
    ): String {
        val trimmed = dependency.trim()
        require(trimmed.isNotEmpty()) { "Dependency cannot be blank" }
        require(!trimmed.contains(';')) { "Dependency spec already contains a marker: $dependency" }
        return "$trimmed ; ${MarkerPolicy.markerForTarget(target)}"
    }

    internal fun parseDependencySpec(spec: String): ParsedDependencySpec {
        val requirement = spec.substringBefore(';').trim()
        require(requirement.isNotEmpty()) { "Invalid dependency spec: $spec" }

        val nameMatch =
            Regex("^[A-Za-z0-9_.-]+")
                .find(requirement)
                ?: throw IllegalArgumentException("Invalid dependency spec: $spec")

        return ParsedDependencySpec(
            original = spec,
            normalizedName = normalizePackageName(nameMatch.value),
        )
    }

    internal fun parseLockPackages(lockContent: String): List<ResolvedLockPackage> {
        val blocks =
            Regex("""(?m)^\[\[package]]\s*$""")
                .split(lockContent)
                .drop(1)

        return blocks.mapNotNull { block ->
            val name = Regex("""(?m)^name = "([^"]+)"""").find(block)?.groupValues?.get(1) ?: return@mapNotNull null
            val version = Regex("""(?m)^version = "([^"]+)"""").find(block)?.groupValues?.get(1) ?: return@mapNotNull null
            val wheelUrls =
                Regex("""url = "([^"]+\.whl)"""")
                    .findAll(block)
                    .map { it.groupValues[1] }
                    .toList()

            ResolvedLockPackage(
                name = normalizePackageName(name),
                version = version,
                wheelUrls = wheelUrls,
            )
        }
    }

    internal fun isWheelCompatibleWithTarget(
        filename: String,
        target: String,
    ): Boolean {
        if (!filename.endsWith(".whl", ignoreCase = true)) {
            return false
        }

        val lower = filename.lowercase()
        val platformSegment =
            lower
                .removeSuffix(".whl")
                .substringAfterLast('-', missingDelimiterValue = "")
        if (platformSegment.isEmpty()) {
            return false
        }

        val platformTags = platformSegment.split('.')
        if (platformTags.any { it == "any" }) {
            return true
        }

        return when (target) {
            "x86_64-pc-windows-msvc" -> platformTags.any { it == "win_amd64" }
            "aarch64-pc-windows-msvc" -> platformTags.any { it == "win_arm64" }
            "i686-pc-windows-msvc" -> platformTags.any { it == "win32" }
            "x86_64-unknown-linux-gnu" ->
                platformTags.any {
                    it == "linux_x86_64" || (it.contains("manylinux") && it.contains("x86_64"))
                }
            "aarch64-unknown-linux-gnu" ->
                platformTags.any {
                    it == "linux_aarch64" || (it.contains("manylinux") && it.contains("aarch64"))
                }
            "x86_64-unknown-linux-musl" ->
                platformTags.any {
                    (it.contains("musllinux") && it.contains("x86_64")) || it == "linux_x86_64"
                }
            "aarch64-unknown-linux-musl" ->
                platformTags.any {
                    (it.contains("musllinux") && it.contains("aarch64")) || it == "linux_aarch64"
                }
            "x86_64-apple-darwin" ->
                platformTags.any {
                    (it.contains("macosx") && it.contains("x86_64")) || it.endsWith("universal2")
                }
            "aarch64-apple-darwin" ->
                platformTags.any {
                    (it.contains("macosx") && it.contains("arm64")) || it.endsWith("universal2")
                }
            else ->
                when {
                    target.startsWith("x86_64-manylinux") ->
                        platformTags.any { it.contains("manylinux") && it.contains("x86_64") }
                    target.startsWith("aarch64-manylinux") ->
                        platformTags.any { it.contains("manylinux") && it.contains("aarch64") }
                    target == "aarch64-linux-android" ->
                        platformTags.any { it.contains("android") && it.contains("aarch64") }
                    target == "x86_64-linux-android" ->
                        platformTags.any { it.contains("android") && it.contains("x86_64") }
                    target == "wasm32-pyodide2024" ->
                        platformTags.any { it.contains("emscripten") || it.contains("pyodide") || it.contains("wasm32") }
                    target == "arm64-apple-ios" ->
                        platformTags.any { it.contains("ios") && it.contains("arm64") }
                    target == "arm64-apple-ios-simulator" ->
                        platformTags.any { it.contains("ios") && it.contains("arm64") && it.contains("simulator") }
                    target == "x86_64-apple-ios-simulator" ->
                        platformTags.any { it.contains("ios") && it.contains("x86_64") && it.contains("simulator") }
                    else -> false
                }
        }
    }

    private fun normalizePackageName(name: String): String = name.lowercase().replace('_', '-').replace('.', '-')
}

internal data class ParsedDependencySpec(
    val original: String,
    val normalizedName: String,
)

internal data class ResolvedLockPackage(
    val name: String,
    val version: String,
    val wheelUrls: List<String>,
)
