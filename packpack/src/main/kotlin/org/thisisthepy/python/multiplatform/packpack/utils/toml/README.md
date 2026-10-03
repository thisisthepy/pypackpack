# TOML Editor

A style-preserving TOML editing library for Kotlin that maintains formatting, comments, and whitespace during document manipulation.

## Features

| Feature                     | Status       | Description                                                 |
| --------------------------- | ------------ | ----------------------------------------------------------- |
| **Table Operations**        | ✅ Supported | Create, read, and delete TOML tables                        |
| **Value Operations**        | ✅ Supported | Get, set, and delete key-value pairs                        |
| **Array Operations**        | ✅ Supported | Manipulate string arrays with optional sorting              |
| **Comment Preservation**    | ✅ Supported | Maintains block comments, inline comments, and key comments |
| **Formatting Preservation** | ✅ Supported | Keeps original indentation and whitespace                   |
| **Type Safety**             | ✅ Supported | Sealed class hierarchy for TOML types                       |
| **Validation**              | 🚧 Planned   | Automatic validation against allowed values                 |

## Platform Support

| Platform          | Status       | Notes                    |
| ----------------- | ------------ | ------------------------ |
| **JVM**           | ✅ Supported | Full support             |
| **Kotlin/Native** | ✅ Supported | Multiplatform compatible |
| **Kotlin/JS**     | ✅ Supported | Multiplatform compatible |

## Supported TOML Types

| Type               | Status           | Example           | Notes                             |
| ------------------ | ---------------- | ----------------- | --------------------------------- |
| String             | ✅ Full          | `"value"`         | Basic strings supported           |
| Integer            | ✅ Full          | `42`, `-17`       | Long values                       |
| Float              | ✅ Full          | `3.14`, `-0.01`   | Double precision                  |
| Boolean            | ✅ Full          | `true`, `false`   | Standard booleans                 |
| Array              | ✅ Full          | `["a", "b", "c"]` | String arrays, multi-line support |
| Inline Table       | 🚧 Partial       | `{a = 1, b = 2}`  | Basic support                     |
| Date/Time          | ❌ Not supported | -                 | Not yet implemented               |
| Multi-line strings | ⚠️ Untested      | `"""..."""`       | May work but untested             |

## Quick Start

### Basic Usage

```kotlin
import org.thisisthepy.python.multiplatform.packpack.utils.toml.TomlEditor
import org.thisisthepy.python.multiplatform.packpack.utils.toml.TomlValue

val editor = TomlEditor(tomlContent)

// Create table
editor.createTable("database.config")

// Set values
editor.setValue("database.config", "host", TomlValue.String("localhost"))
editor.setValue("database.config", "port", TomlValue.Integer(5432))
editor.setValue("database.config", "enabled", TomlValue.Boolean(true))
editor.setValue("database.config", "timeout", TomlValue.Integer(30))

// Array operations
editor.setArray("database.config", "replicas", listOf("replica1", "replica2"))
editor.addToArray("database.config", "replicas", "replica3")
editor.removeFromArray("database.config", "replicas", "replica1")

// Get values
val host = editor.getValue("database.config", "host")
val replicas = editor.getArray("database.config", "replicas")

// Delete operations
editor.deleteKey("database.config", "timeout")
editor.deleteTable("database.config")

// Output
println(editor.toTomlString())
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

### Alphabetical order

```kotlin
editor.setArray(
    "app.metadata",
    "tags",
    listOf("production", "monitoring", "critical"),
    sorter = { items -> items.sorted() }
)
```

### Custom Sorting

```kotlin
val TAG_ORDER: List<String> = listOf("critical", "production", "monitoring")

fun customSortTags(tags: List<String>): List<String> {
    val orderMap = TAG_ORDER
        .withIndex()
        .associate { it.value to it.index }

    return tags.sortedBy { orderMap[it] ?: Int.MAX_VALUE }
}

editor.setArray(
    path = "app.metadata",
    key = "tags",
    value = listOf("production", "monitoring", "critical"),
    sorter = { items -> customSortTags(items) }
)
```

### Multi-line Arrays

```kotlin
// Reading multi-line arrays (format is automatically preserved)
val toml = """
[dependencies]
packages = [
    "numpy",
    "pandas",
    "scipy",
]
""".trimIndent()

val editor = TomlEditor(toml)
val packages = editor.getArray("dependencies", "packages")
// Returns: ["numpy", "pandas", "scipy"]
// Format is preserved in the TomlValue.Array object

// Method 1: Using setArray with format parameter (recommended)
editor.setArray(
    tablePath = "dependencies",
    key = "packages",
    items = listOf("numpy", "pandas", "matplotlib"),
    format = ArrayFormat.MultiLine(
        indentation = "    ",
        trailingComma = true
    )
)

// Method 2: Using setValue with TomlValue.Array
editor.setValue(
    "dependencies",
    "packages",
    TomlValue.Array(
        items = listOf("numpy", "pandas", "matplotlib"),
        format = ArrayFormat.MultiLine("    ", trailingComma = true)
    )
)

// Both produce:
// packages = [
//     "numpy",
//     "pandas",
//     "matplotlib",
// ]

// Default single-line format
editor.setArray("dependencies", "packages", listOf("numpy", "pandas"))
// Produces: packages = ["numpy", "pandas"]

// Format preservation when adding/removing items
editor.addToArray("dependencies", "packages", "scipy")
// Automatically preserves the existing multi-line format
```

### Working with Nested Tables

```kotlin
// Create nested tables
editor.createTable("server")
editor.createTable("server.database")
editor.createTable("server.cache")

// Set values in nested tables
editor.setValue("server.database", "host", TomlValue.String("db.example.com"))
editor.setValue("server.cache", "host", TomlValue.String("cache.example.com"))
```

## Architecture

```
TomlEditor (Core Engine)
├── Line-based parsing
├── Comment preservation (block, inline, key comments)
├── Whitespace preservation
└── Type-safe operations via TomlValue
```

## Error Handling

```kotlin
// Table not found
editor.setValue("nonexistent.table", "key", value)  // Throws IllegalArgumentException

// Create before use
if (!editor.hasTable("database.config")) {
    editor.createTable("database.config")
}
editor.setValue("database.config", "host", TomlValue.String("localhost"))

// Table already exists
editor.createTable("database.config")  // Throws IllegalArgumentException
```

## Limitations

- **Array types**: Only string arrays are supported (mixed-type arrays not implemented)
- **Array formatting**: Multi-line array format is preserved when reading, can be specified when writing
- **Inline tables**: Partial implementation, may not handle all edge cases
- **Multi-line strings**: Not explicitly tested
- **Dotted keys**: Not supported (e.g., `a.b = "value"`)
- **Array of tables**: Not supported (e.g., `[[array.of.tables]]`)
- **Date/Time types**: Not implemented

## TOML Specification Compliance

- **TOML v1.1.0**: Multi-line arrays with trailing commas, comments, and flexible whitespace
- **Format preservation**: Original array formatting is detected and can be preserved
- **Comments**: Block comments, inline comments, and key comments are preserved (array internal comments are stripped during parsing)
