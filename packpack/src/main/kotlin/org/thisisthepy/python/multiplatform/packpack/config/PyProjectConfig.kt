package org.thisisthepy.python.multiplatform.packpack.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Root configuration for pyproject.toml
 * Supports both standard PEP 518/517 sections and PyPackPack-specific extensions
 */
@Serializable
data class PyProjectConfig(
    val project: ProjectConfig? = null,
    @SerialName("build-system")
    val buildSystem: BuildSystemConfig? = null,
    val tool: ToolConfig? = null
)

/**
 * Standard [project] section as defined by PEP 621
 */
@Serializable
data class ProjectConfig(
    val name: String,
    val version: String? = null,
    val description: String? = null,
    val readme: String? = null,
    val license: LicenseConfig? = null,
    val authors: List<AuthorConfig>? = null,
    val maintainers: List<AuthorConfig>? = null,
    val keywords: List<String>? = null,
    val classifiers: List<String>? = null,
    val urls: Map<String, String>? = null,
    val dependencies: List<String>? = null,
    @SerialName("optional-dependencies")
    val optionalDependencies: Map<String, List<String>>? = null,
    @SerialName("requires-python")
    val requiresPython: String? = null,
    val dynamic: List<String>? = null
)

/**
 * License configuration
 */
@Serializable
data class LicenseConfig(
    val text: String? = null,
    val file: String? = null
)

/**
 * Author/Maintainer configuration
 */
@Serializable
data class AuthorConfig(
    val name: String? = null,
    val email: String? = null
)

/**
 * Standard [build-system] section as defined by PEP 518
 */
@Serializable
data class BuildSystemConfig(
    val requires: List<String>,
    @SerialName("build-backend")
    val buildBackend: String? = null,
    @SerialName("backend-path")
    val backendPath: List<String>? = null
)

/**
 * Tool-specific configurations
 */
@Serializable
data class ToolConfig(
    val pypackpack: PyPackPackConfig? = null,
    val setuptools: Map<String, String>? = null,
    val wheel: Map<String, String>? = null,
    val poetry: Map<String, String>? = null,
    val hatch: Map<String, String>? = null,
    val pdm: Map<String, String>? = null
)

/**
 * PyPackPack-specific configuration under [tool.pypackpack]
 */
@Serializable
data class PyPackPackConfig(
    val version: String = "1.0",
    val targets: Map<String, TargetConfig>? = null,
    val build: BuildConfig? = null,
    val bundle: BundleConfig? = null,
    val deploy: DeployConfig? = null,
    val dependencies: DependencyConfig? = null
)

/**
 * Target platform configuration
 */
@Serializable
data class TargetConfig(
    val platform: String,
    val architecture: String? = null,
    @SerialName("python-version")
    val pythonVersion: String? = null,
    val dependencies: List<String>? = null,
    @SerialName("optional-dependencies")
    val optionalDependencies: Map<String, List<String>>? = null,
    val environment: Map<String, String>? = null,
    val enabled: Boolean = true
)

/**
 * Build configuration
 */
@Serializable
data class BuildConfig(
    val level: BuildLevel = BuildLevel.INSTANT,
    val type: BuildType = BuildType.DEBUG,
    val optimization: OptimizationConfig? = null,
    val compilation: CompilationConfig? = null,
    val resources: ResourceConfig? = null
)

/**
 * Build levels supported by PyPackPack
 */
@Serializable
enum class BuildLevel {
    @SerialName("instant")
    INSTANT,
    @SerialName("bytecode")
    BYTECODE,
    @SerialName("native")
    NATIVE,
    @SerialName("mixed")
    MIXED
}

/**
 * Build types
 */
@Serializable
enum class BuildType {
    @SerialName("debug")
    DEBUG,
    @SerialName("release")
    RELEASE
}

/**
 * Optimization configuration
 */
@Serializable
data class OptimizationConfig(
    val minification: Boolean = false,
    val compression: Boolean = true,
    val deadCodeElimination: Boolean = false,
    val inlining: Boolean = false
)

/**
 * Compilation configuration
 */
@Serializable
data class CompilationConfig(
    val backend: CompilationBackend = CompilationBackend.NUITKA,
    val flags: List<String>? = null,
    val plugins: List<String>? = null
)

/**
 * Compilation backends
 */
@Serializable
enum class CompilationBackend {
    @SerialName("nuitka")
    NUITKA,
    @SerialName("cython")
    CYTHON,
    @SerialName("lpython")
    LPYTHON
}

/**
 * Resource configuration
 */
@Serializable
data class ResourceConfig(
    val include: List<String>? = null,
    val exclude: List<String>? = null,
    val compression: Boolean = true
)

/**
 * Bundle configuration
 */
@Serializable
data class BundleConfig(
    val type: BundleType = BundleType.BINARY,
    val format: BundleFormat = BundleFormat.WHEEL,
    val compression: CompressionConfig? = null,
    val patch: PatchConfig? = null
)

/**
 * Bundle types
 */
@Serializable
enum class BundleType {
    @SerialName("binary")
    BINARY,
    @SerialName("fat")
    FAT,
    @SerialName("single")
    SINGLE,
    @SerialName("patch")
    PATCH
}

/**
 * Bundle formats
 */
@Serializable
enum class BundleFormat {
    @SerialName("wheel")
    WHEEL,
    @SerialName("executable")
    EXECUTABLE,
    @SerialName("archive")
    ARCHIVE
}

/**
 * Compression configuration
 */
@Serializable
data class CompressionConfig(
    val algorithm: CompressionAlgorithm = CompressionAlgorithm.GZIP,
    val level: Int = 6
)

/**
 * Compression algorithms
 */
@Serializable
enum class CompressionAlgorithm {
    @SerialName("gzip")
    GZIP,
    @SerialName("bzip2")
    BZIP2,
    @SerialName("lzma")
    LZMA
}

/**
 * Patch configuration for incremental updates
 */
@Serializable
data class PatchConfig(
    val enabled: Boolean = false,
    val baseVersion: String? = null,
    val algorithm: PatchAlgorithm = PatchAlgorithm.BINARY_DIFF
)

/**
 * Patch algorithms
 */
@Serializable
enum class PatchAlgorithm {
    @SerialName("binary-diff")
    BINARY_DIFF,
    @SerialName("file-diff")
    FILE_DIFF
}

/**
 * Deployment configuration
 */
@Serializable
data class DeployConfig(
    val targets: List<DeployTarget>? = null,
    val credentials: Map<String, String>? = null,
    val fasttrack: FastTrackConfig? = null
)

/**
 * Deployment targets
 */
@Serializable
data class DeployTarget(
    val name: String,
    val type: DeployType,
    val url: String? = null,
    val repository: String? = null
)

/**
 * Deployment types
 */
@Serializable
enum class DeployType {
    @SerialName("pypi")
    PYPI,
    @SerialName("fasttrack")
    FASTTRACK,
    @SerialName("custom")
    CUSTOM
}

/**
 * FastTrack API configuration
 */
@Serializable
data class FastTrackConfig(
    val enabled: Boolean = false,
    val endpoint: String? = null,
    val apiKey: String? = null,
    val hotReload: Boolean = false
)

/**
 * Dependency configuration
 */
@Serializable
data class DependencyConfig(
    val resolver: DependencyResolver = DependencyResolver.UV,
    val sources: List<DependencySource>? = null,
    val constraints: List<String>? = null
)

/**
 * Dependency resolvers
 */
@Serializable
enum class DependencyResolver {
    @SerialName("uv")
    UV,
    @SerialName("pip")
    PIP
}

/**
 * Dependency sources
 */
@Serializable
data class DependencySource(
    val name: String,
    val url: String,
    val priority: Int = 0
) 