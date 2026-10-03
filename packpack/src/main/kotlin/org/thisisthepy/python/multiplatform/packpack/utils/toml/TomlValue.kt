package org.thisisthepy.python.multiplatform.packpack.utils.toml

/**
 * Formatting style for TOML arrays.
 */
sealed class ArrayFormat {
    /**
     * Single-line array format.
     * Example: `items = ["a", "b", "c"]`
     */
    object SingleLine : ArrayFormat()

    /**
     * Multi-line array format with custom indentation and style.
     *
     * @param indentation Indentation string for array items (e.g., "    ")
     * @param trailingComma Whether to include trailing comma after last item
     */
    data class MultiLine(
        val indentation: String,
        val trailingComma: Boolean = false,
    ) : ArrayFormat()
}

/**
 * Represents a value in TOML format.
 *
 * This sealed class hierarchy provides type-safe representation of TOML values,
 * ensuring proper handling of different data types in TOML documents.
 */
sealed class TomlValue {
    /**
     * String value in TOML.
     *
     * Example: `name = "mypackage"`
     */
    data class String(
        val value: kotlin.String,
    ) : TomlValue() {
        override fun toString(): kotlin.String = "\"$value\""
    }

    /**
     * Integer value in TOML.
     *
     * Example: `count = 42`
     */
    data class Integer(
        val value: Long,
    ) : TomlValue() {
        override fun toString(): kotlin.String = value.toString()
    }

    /**
     * Float value in TOML.
     *
     * Example: `ratio = 3.14`
     */
    data class Float(
        val value: Double,
    ) : TomlValue() {
        override fun toString(): kotlin.String = value.toString()
    }

    /**
     * Boolean value in TOML.
     *
     * Example: `enabled = true`
     */
    data class Boolean(
        val value: kotlin.Boolean,
    ) : TomlValue() {
        override fun toString(): kotlin.String = value.toString()
    }

    /**
     * String array value in TOML.
     *
     * Example: `platforms = ["windows", "linux"]`
     *
     * Note: Currently only supports string arrays. Mixed-type arrays
     * are not supported as they are rarely used in practice.
     *
     * @param items List of string items in the array
     * @param format Formatting style (single-line or multi-line)
     */
    data class Array(
        val items: List<kotlin.String>,
        val format: ArrayFormat = ArrayFormat.SingleLine,
    ) : TomlValue() {
        override fun toString(): kotlin.String {
            val itemsStr = items.joinToString(", ") { "\"$it\"" }
            return "[$itemsStr]"
        }
    }

    /**
     * Inline table value in TOML.
     *
     * Example: `metadata = { key = "value", count = 42 }`
     *
     * Note: This is a placeholder for future extension. Not fully implemented yet.
     */
    data class InlineTable(
        val pairs: Map<kotlin.String, TomlValue>,
    ) : TomlValue() {
        override fun toString(): kotlin.String {
            val pairsStr = pairs.entries.joinToString(", ") { (k, v) -> "$k = $v" }
            return "{ $pairsStr }"
        }
    }
}
