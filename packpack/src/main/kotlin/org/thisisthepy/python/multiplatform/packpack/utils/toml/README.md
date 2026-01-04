# TOML Editor

A flexible and type-safe TOML editing library for managing `[tool.ppp.*]` configurations in `pyproject.toml` files.

## Features

- ✅ **Generic Core API** - Edit any key/value in TOML tables
- ✅ **Type-Safe Values** - Sealed class hierarchy for TOML types
- ✅ **Comment Preservation** - Keeps all comments (block, inline, and above keys)
- ✅ **Extension Functions** - Domain-specific helpers (e.g., platform management)
- ✅ **Auto-Sorting** - Platforms sorted by `SUPPORTED_TARGETS` order
- ✅ **Validation** - Automatic validation against allowed values

## Quick Start

### Dependency Platform Management (Recommended)

For per-package `pyproject.toml` files:

```kotlin
import org.thisisthepy.python.multiplatform.packpack.utils.toml.TomlEditor
import org.thisisthepy.python.multiplatform.packpack.utils.toml.updateDependencyPlatforms

val tomlContent = """
[project]
name = "mypackage"
version = "1.0.0"
""".trimIndent()

val editor = TomlEditor(tomlContent)

// Manage platforms in [tool.ppp.dependencies] table
editor.updateDependencyPlatforms {
    addPlatforms("windows", "linux", "macos")
    removePlatforms("macos")
}

val result = editor.toTomlString()
```

**Output:**

```toml
[project]
name = "mypackage"
version = "1.0.0"

[tool.ppp.dependencies]
platforms = ["windows", "linux"]
```

### Core API (Generic Operations)

```kotlin
import org.thisisthepy.python.multiplatform.packpack.utils.toml.TomlEditor
import org.thisisthepy.python.multiplatform.packpack.utils.toml.TomlValue

val editor = TomlEditor(tomlContent)

// Create table
editor.createTable("tool.ppp.mypackage")

// Set values
editor.setValue("tool.ppp.mypackage", "name", TomlValue.String("myapp"))
editor.setValue("tool.ppp.mypackage", "version", TomlValue.String("1.0.0"))
editor.setValue("tool.ppp.mypackage", "enabled", TomlValue.Boolean(true))
editor.setValue("tool.ppp.mypackage", "count", TomlValue.Integer(42))

// Array operations
editor.setArray("tool.ppp.mypackage", "tags", listOf("alpha", "beta"))
editor.addToArray("tool.ppp.mypackage", "tags", "gamma")
editor.removeFromArray("tool.ppp.mypackage", "tags", "alpha")

// Get values
val name = editor.getValue("tool.ppp.mypackage", "name")
val tags = editor.getArray("tool.ppp.mypackage", "tags")

// Delete operations
editor.deleteKey("tool.ppp.mypackage", "count")
editor.deleteTable("tool.ppp.mypackage")
```

## API Reference

### TomlEditor (Core)

#### Table Operations

```kotlin
fun hasTable(path: String): Boolean
fun createTable(path: String)
fun deleteTable(path: String)
```

#### Value Operations

```kotlin
fun getValue(tablePath: String, key: String): TomlValue?
fun setValue(tablePath: String, key: String, value: TomlValue)
fun deleteKey(tablePath: String, key: String)
```

#### Array Operations

```kotlin
fun getArray(tablePath: String, key: String): List<String>
fun setArray(tablePath: String, key: String, items: List<String>, sorter: ((List<String>) -> List<String>)? = null)
fun addToArray(tablePath: String, key: String, vararg items: String, sorter: ((List<String>) -> List<String>)? = null)
fun removeFromArray(tablePath: String, key: String, vararg items: String)
```

#### Output

```kotlin
fun toTomlString(): String
```

### TomlValue (Types)

```kotlin
sealed class TomlValue {
    data class String(val value: kotlin.String)
    data class Integer(val value: Long)
    data class Float(val value: Double)
    data class Boolean(val value: kotlin.Boolean)
    data class Array(val items: List<kotlin.String>)
    data class InlineTable(val pairs: Map<kotlin.String, TomlValue>)
}
```

### Platform Extensions

#### Dependency Platform Management (Recommended)

```kotlin
fun TomlEditor.updateDependencyPlatforms(block: DependencyPlatformEditor.() -> Unit)

class DependencyPlatformEditor {
    fun addPlatforms(vararg platforms: String)      // Validates & auto-sorts
    fun removePlatforms(vararg platforms: String)   // Validates platforms
}
```

Manages platforms in `[tool.ppp.dependencies]` table for per-package `pyproject.toml` files.

#### Legacy Package Platform Management

```kotlin
@Deprecated("Use updateDependencyPlatforms() instead")
fun TomlEditor.updatePackagePlatforms(packageName: String, block: PackagePlatformEditor.() -> Unit)
```

For managing `[tool.ppp.<packageName>]` tables (deprecated).

## Advanced Usage

### Comment Preservation

All comments are preserved during editing:

```kotlin
val toml = """
# Global config

# Dependency configuration
[tool.ppp.dependencies]
# Supported platforms
platforms = ["windows"]  # Inline comment
""".trimIndent()

val editor = TomlEditor(toml)
editor.updateDependencyPlatforms {
    addPlatforms("linux")
}

// All comments are preserved!
```

### Custom Sorting

```kotlin
editor.setArray(
    "tool.ppp.dependencies",
    "platforms",
    listOf("macos", "windows", "linux"),
    sorter = { Platforms.sort(it) }  // Custom sorter
)
```

### Mix Core + Extension

```kotlin
editor.updateDependencyPlatforms {
    addPlatforms("windows", "linux")
}

editor.setValue("tool.ppp.dependencies", "version", TomlValue.String("1.0.0"))
editor.setArray("tool.ppp.dependencies", "tags", listOf("stable", "production"))
```

## Use Cases

### 1. Per-Package Configuration

```kotlin
// package1/pyproject.toml
val editor1 = TomlEditor(File("package1/pyproject.toml").readText())
editor1.updateDependencyPlatforms {
    addPlatforms("windows", "linux")
}
File("package1/pyproject.toml").writeText(editor1.toTomlString())

// package2/pyproject.toml
val editor2 = TomlEditor(File("package2/pyproject.toml").readText())
editor2.updateDependencyPlatforms {
    addPlatforms("macos", "wasm32-pyodide2024")
}
File("package2/pyproject.toml").writeText(editor2.toTomlString())
```

### 2. Batch Updates

```kotlin
val packageDirs = listOf("package1", "package2", "package3")

packageDirs.forEach { dir ->
    val file = File("$dir/pyproject.toml")
    val editor = TomlEditor(file.readText())
    editor.updateDependencyPlatforms {
        addPlatforms("windows", "linux", "macos")
    }
    file.writeText(editor.toTomlString())
}
```

### 3. Generic Configuration Management

```kotlin
val editor = TomlEditor(tomlContent)

// Setup project config
editor.createTable("tool.ppp.config")
editor.setValue("tool.ppp.config", "name", TomlValue.String("myproject"))
editor.setValue("tool.ppp.config", "version", TomlValue.String("0.1.0"))
editor.setValue("tool.ppp.config", "debug", TomlValue.Boolean(false))
editor.setArray("tool.ppp.config", "authors", listOf("John Doe", "Jane Smith"))
```

## Architecture

```
TomlEditor (Core Engine)
├── Generic TOML operations
├── Comment preservation (Level 3)
├── Type-safe via TomlValue
└── Extensible via helpers

PlatformExtensions (Domain Helper)
├── Platform-specific validation
├── Auto-sorting by SUPPORTED_TARGETS
└── DSL-style API

Future Extensions
└── DependencyExtensions, ConfigExtensions, etc.
```

## Error Handling

```kotlin
// Invalid platform
editor.updateDependencyPlatforms {
    addPlatforms("invalid-platform")  // Throws IllegalArgumentException
}

// Table not found
editor.setValue("nonexistent.table", "key", value)  // Throws IllegalArgumentException

// Create before use
if (!editor.hasTable("tool.ppp.dependencies")) {
    editor.createTable("tool.ppp.dependencies")
}
```

## Files

- **`TomlEditor.kt`** - Core editing engine (~450 lines)
- **`TomlValue.kt`** - Type definitions (~74 lines)
- **`PlatformExtensions.kt`** - Platform helper (~120 lines)
