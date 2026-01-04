# TOML Editor

A flexible and type-safe TOML editing library for managing `[tool.ppp.*]` configurations in `pyproject.toml` files.

## Features

- ✅ **Generic Core API** - Edit any key/value in TOML tables
- ✅ **Type-Safe Values** - Sealed class hierarchy for TOML types
- ✅ **Comment Preservation** - Keeps all comments (block, inline, and above keys)
- ✅ **Validation** - Automatic validation against allowed values

## Quick Start

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

## Advanced Usage

### Custom Sorting

```kotlin
editor.setArray(
    "tool.ppp.dependencies",
    "platforms",
    listOf("macos", "windows", "linux"),
    sorter = { Platforms.sort(it) }  // Custom sorter
)
```

## Architecture

```
TomlEditor (Core Engine)
├── Generic TOML operations
├── Comment preservation
└── Type-safe via TomlValue
```

## Error Handling

```kotlin
// Table not found
editor.setValue("nonexistent.table", "key", value)  // Throws IllegalArgumentException

// Create before use
if (!editor.hasTable("tool.ppp.dependencies")) {
    editor.createTable("tool.ppp.dependencies")
}
```
