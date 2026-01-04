package org.thisisthepy.python.multiplatform.packpack.utils.toml

import org.thisisthepy.python.multiplatform.packpack.utils.Platforms

/**
 * Extension function for managing platform arrays in [tool.ppp.dependencies] table.
 *
 * This provides a high-level DSL for dependency platform management, handling validation
 * and sorting automatically. Designed for per-package pyproject.toml files.
 *
 * Example:
 * ```kotlin
 * // In package's pyproject.toml
 * val editor = TomlEditor(tomlContent)
 * editor.updateDependencyPlatforms {
 *     addPlatforms("windows", "linux")
 *     removePlatforms("macos")
 * }
 * val result = editor.toTomlString()
 * ```
 *
 * Result:
 * ```toml
 * [tool.ppp.dependencies]
 * platforms = ["windows", "linux"]
 * ```
 *
 * @param block Configuration block for platform operations
 * @return The TomlEditor instance for chaining
 */
fun TomlEditor.updateDependencyPlatforms(block: DependencyPlatformEditor.() -> Unit): TomlEditor {
    val editor = DependencyPlatformEditor(this)
    editor.block()
    return this
}

/**
 * Legacy extension function for managing platform arrays in [tool.ppp.*] tables.
 *
 * @deprecated Use updateDependencyPlatforms() for per-package pyproject.toml files
 * @param packageName The package name (without "tool.ppp." prefix)
 * @param block Configuration block for platform operations
 * @return The TomlEditor instance for chaining
 */
@Deprecated(
    message = "Use updateDependencyPlatforms() instead for per-package pyproject.toml files",
    replaceWith = ReplaceWith("updateDependencyPlatforms(block)"),
)
fun TomlEditor.updatePackagePlatforms(
    packageName: String,
    block: PackagePlatformEditor.() -> Unit,
): TomlEditor {
    val editor = PackagePlatformEditor(this, packageName)
    editor.block()
    return this
}

/**
 * DSL builder for dependency platform configuration.
 *
 * This class provides platform-specific operations with automatic validation
 * against Platforms.SUPPORTED_TARGETS and sorting for [tool.ppp.dependencies] table.
 */
class DependencyPlatformEditor(
    private val tomlEditor: TomlEditor,
) {
    private val tablePath = "tool.ppp.dependencies"

    /**
     * Add platforms to the dependencies.
     *
     * Features:
     * - Validates platforms against Platforms.SUPPORTED_TARGETS
     * - Creates the [tool.ppp.dependencies] table if it doesn't exist
     * - Automatically sorts platforms by SUPPORTED_TARGETS order
     * - Deduplicates platforms (no duplicates in final array)
     *
     * @param platforms Platform names to add (e.g., "windows", "linux", "aarch64-apple-darwin")
     * @throws IllegalArgumentException if any platform is not in SUPPORTED_TARGETS
     *
     * Example:
     * ```kotlin
     * addPlatforms("windows", "linux", "macos")
     * addPlatforms("aarch64-apple-darwin", "arm64-apple-ios")
     * ```
     */
    fun addPlatforms(vararg platforms: String) {
        // Validate all platforms first
        platforms.forEach { platform ->
            require(platform in Platforms.SUPPORTED_TARGETS) {
                "Unsupported platform: $platform. Must be one of ${Platforms.SUPPORTED_TARGETS}"
            }
        }

        // Create table if it doesn't exist
        if (!tomlEditor.hasTable(tablePath)) {
            tomlEditor.createTable(tablePath)
        }

        // Add to array with platform sorter
        tomlEditor.addToArray(
            tablePath = tablePath,
            key = "platforms",
            items = platforms,
            sorter = { Platforms.sort(it) },
        )
    }

    /**
     * Remove platforms from the dependencies.
     *
     * Features:
     * - Validates platforms against Platforms.SUPPORTED_TARGETS
     * - Silently ignores platforms that don't exist in the array
     * - Does nothing if the table or platforms array doesn't exist
     *
     * @param platforms Platform names to remove
     * @throws IllegalArgumentException if any platform is not in SUPPORTED_TARGETS
     *
     * Example:
     * ```kotlin
     * removePlatforms("macos")
     * removePlatforms("windows", "linux")
     * ```
     */
    fun removePlatforms(vararg platforms: String) {
        // Validate all platforms first
        platforms.forEach { platform ->
            require(platform in Platforms.SUPPORTED_TARGETS) {
                "Unsupported platform: $platform. Must be one of ${Platforms.SUPPORTED_TARGETS}"
            }
        }

        // Remove from array (silently does nothing if table/key doesn't exist)
        if (tomlEditor.hasTable(tablePath)) {
            tomlEditor.removeFromArray(
                tablePath = tablePath,
                key = "platforms",
                items = platforms,
            )
        }
    }
}

/**
 * Legacy DSL builder for package platform configuration.
 *
 * @deprecated Use DependencyPlatformEditor instead
 */
@Deprecated("Use DependencyPlatformEditor instead")
class PackagePlatformEditor(
    private val tomlEditor: TomlEditor,
    private val packageName: String,
) {
    private val tablePath = "tool.ppp.$packageName"

    fun addPlatforms(vararg platforms: String) {
        platforms.forEach { platform ->
            require(platform in Platforms.SUPPORTED_TARGETS) {
                "Unsupported platform: $platform. Must be one of ${Platforms.SUPPORTED_TARGETS}"
            }
        }

        if (!tomlEditor.hasTable(tablePath)) {
            tomlEditor.createTable(tablePath)
        }

        tomlEditor.addToArray(
            tablePath = tablePath,
            key = "platforms",
            items = platforms,
            sorter = { Platforms.sort(it) },
        )
    }

    fun removePlatforms(vararg platforms: String) {
        platforms.forEach { platform ->
            require(platform in Platforms.SUPPORTED_TARGETS) {
                "Unsupported platform: $platform. Must be one of ${Platforms.SUPPORTED_TARGETS}"
            }
        }

        if (tomlEditor.hasTable(tablePath)) {
            tomlEditor.removeFromArray(
                tablePath = tablePath,
                key = "platforms",
                items = platforms,
            )
        }
    }
}
