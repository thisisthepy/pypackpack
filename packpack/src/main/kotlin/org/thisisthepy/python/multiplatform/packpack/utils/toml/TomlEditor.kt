package org.thisisthepy.python.multiplatform.packpack.utils.toml

/**
 * Generic TOML editor for [tool.ppp.*] tables.
 *
 * This editor provides low-level operations for manipulating TOML documents while
 * preserving comments, whitespace, and original formatting. It specifically targets
 * tables under the [tool.ppp.*] namespace.
 *
 * Features:
 * - Preserves inline comments and block comments (Level 3)
 * - Maintains original indentation
 * - Type-safe value operations via TomlValue
 * - Array manipulation with optional sorting
 *
 * Example:
 * ```kotlin
 * val editor = TomlEditor(tomlContent)
 * editor.addToArray("tool.ppp.package1", "platforms", "windows", "linux")
 * val result = editor.toTomlString()
 * ```
 */
class TomlEditor(
    tomlContent: String,
) {
    // ===== Internal State =====

    private val lines: MutableList<String> = tomlContent.lines().toMutableList()

    /**
     * Information about a TOML table location and its comments.
     */
    private data class TableInfo(
        val startLine: Int, // Line index of [table.name]
        val endLine: Int, // Last line of this table section
        val blockComments: List<String>, // Comments above the table header
    )

    /**
     * Information about a key within a table and its comments.
     */
    private data class KeyInfo(
        val lineIndex: Int, // Line index of the key = value
        val keyComments: List<String>, // Comments above the key
        val inlineComment: String?, // Comment on the same line as key = value
    )

    // ===== Public API: Table Operations =====

    /**
     * Check if a table exists.
     *
     * @param path Dotted table path (e.g., "tool.ppp.package1")
     * @return true if table exists, false otherwise
     */
    fun hasTable(path: String): Boolean = findTable(path) != null

    /**
     * Create a new table.
     *
     * @param path Dotted table path (e.g., "tool.ppp.package1")
     * @throws IllegalArgumentException if table already exists
     */
    fun createTable(path: String) {
        require(!hasTable(path)) { "Table [$path] already exists" }

        // Add blank line separator if file is not empty and last line is not blank
        if (lines.isNotEmpty() && lines.last().isNotBlank()) {
            lines.add("")
        }

        lines.add("[$path]")
    }

    /**
     * Delete a table and all its contents.
     *
     * @param path Dotted table path (e.g., "tool.ppp.package1")
     */
    fun deleteTable(path: String) {
        val tableInfo = findTable(path) ?: return

        // Delete from block comments to end of table
        val deleteStart = tableInfo.startLine - tableInfo.blockComments.size
        val deleteEnd = tableInfo.endLine

        for (i in deleteEnd downTo deleteStart) {
            lines.removeAt(i)
        }
    }

    // ===== Public API: Value Operations =====

    /**
     * Get a value from a table.
     *
     * @param tablePath Dotted table path (e.g., "tool.ppp.package1")
     * @param key Key name within the table
     * @return TomlValue if found, null otherwise
     */
    fun getValue(
        tablePath: String,
        key: String,
    ): TomlValue? {
        val tableInfo = findTable(tablePath) ?: return null
        val keyInfo = findKey(tableInfo, key) ?: return null
        return parseValue(lines[keyInfo.lineIndex], key)
    }

    /**
     * Set a value in a table.
     *
     * @param tablePath Dotted table path (e.g., "tool.ppp.package1")
     * @param key Key name within the table
     * @param value TomlValue to set
     */
    fun setValue(
        tablePath: String,
        key: String,
        value: TomlValue,
    ) {
        val tableInfo =
            findTable(tablePath)
                ?: throw IllegalArgumentException("Table [$tablePath] not found")

        val keyInfo = findKey(tableInfo, key)

        if (keyInfo != null) {
            // Update existing key
            val line = lines[keyInfo.lineIndex]
            val indent = line.takeWhile { it.isWhitespace() }
            lines[keyInfo.lineIndex] = buildValueLine(key, value, indent, keyInfo.inlineComment)
        } else {
            // Add new key at end of table
            val newLine = buildValueLine(key, value, "", null)
            lines.add(tableInfo.endLine + 1, newLine)
        }
    }

    /**
     * Delete a key from a table.
     *
     * @param tablePath Dotted table path (e.g., "tool.ppp.package1")
     * @param key Key name to delete
     */
    fun deleteKey(
        tablePath: String,
        key: String,
    ) {
        val tableInfo = findTable(tablePath) ?: return
        val keyInfo = findKey(tableInfo, key) ?: return

        // Delete key comments and the key line itself
        val deleteStart = keyInfo.lineIndex - keyInfo.keyComments.size
        val deleteEnd = keyInfo.lineIndex

        for (i in deleteEnd downTo deleteStart) {
            lines.removeAt(i)
        }
    }

    // ===== Public API: Array Convenience Methods =====

    /**
     * Get a string array from a table.
     *
     * @param tablePath Dotted table path (e.g., "tool.ppp.package1")
     * @param key Key name within the table
     * @return List of strings, empty list if not found or not an array
     */
    fun getArray(
        tablePath: String,
        key: String,
    ): List<String> {
        val value = getValue(tablePath, key)
        return when (value) {
            is TomlValue.Array -> value.items
            else -> emptyList()
        }
    }

    /**
     * Set a string array in a table.
     *
     * @param tablePath Dotted table path (e.g., "tool.ppp.package1")
     * @param key Key name within the table
     * @param items List of strings to set
     * @param sorter Optional function to sort the items before setting
     */
    fun setArray(
        tablePath: String,
        key: String,
        items: List<String>,
        sorter: ((List<String>) -> List<String>)? = null,
    ) {
        val sortedItems = sorter?.invoke(items) ?: items
        setValue(tablePath, key, TomlValue.Array(sortedItems))
    }

    /**
     * Add items to a string array in a table.
     *
     * If the array doesn't exist, it will be created.
     * Duplicates are automatically removed.
     *
     * @param tablePath Dotted table path (e.g., "tool.ppp.package1")
     * @param key Key name within the table
     * @param items Items to add
     * @param sorter Optional function to sort the final array
     */
    fun addToArray(
        tablePath: String,
        key: String,
        vararg items: String,
        sorter: ((List<String>) -> List<String>)? = null,
    ) {
        val currentItems = getArray(tablePath, key).toMutableSet()
        currentItems.addAll(items)
        setArray(tablePath, key, currentItems.toList(), sorter)
    }

    /**
     * Remove items from a string array in a table.
     *
     * If the item doesn't exist, it's silently ignored.
     *
     * @param tablePath Dotted table path (e.g., "tool.ppp.package1")
     * @param key Key name within the table
     * @param items Items to remove
     */
    fun removeFromArray(
        tablePath: String,
        key: String,
        vararg items: String,
    ) {
        val currentItems = getArray(tablePath, key).toMutableSet()
        currentItems.removeAll(items.toSet())
        setArray(tablePath, key, currentItems.toList())
    }

    // ===== Output =====

    /**
     * Generate the final TOML string with all modifications applied.
     *
     * @return TOML formatted string
     */
    fun toTomlString(): String = lines.joinToString("\n")

    // ===== Private Implementation =====

    /**
     * Find a table by its dotted path.
     * Only matches [tool.ppp.*] tables.
     */
    private fun findTable(path: String): TableInfo? {
        val tableRegex = """\[(${Regex.escape(path)})\]""".toRegex()

        var currentTableStart = -1
        var currentBlockComments = mutableListOf<String>()

        lines.forEachIndexed { index, line ->
            val trimmed = line.trim()

            // Collect comments above the table
            if (trimmed.startsWith("#")) {
                if (currentTableStart == -1) {
                    currentBlockComments.add(trimmed)
                }
            }
            // Check if this is our target table
            else if (tableRegex.matches(trimmed)) {
                currentTableStart = index

                // Find end of table (next table or end of file)
                var endLine = lines.size - 1
                for (i in (index + 1) until lines.size) {
                    val nextTrimmed = lines[i].trim()
                    if (nextTrimmed.startsWith("[")) {
                        endLine = i - 1
                        break
                    }
                }

                return TableInfo(
                    startLine = currentTableStart,
                    endLine = endLine,
                    blockComments = currentBlockComments.toList(),
                )
            }
            // Reset comment collection if we hit a non-comment, non-table line
            else if (trimmed.isNotEmpty()) {
                currentBlockComments.clear()
            }
        }

        return null
    }

    /**
     * Find a key within a table.
     */
    private fun findKey(
        tableInfo: TableInfo,
        key: String,
    ): KeyInfo? {
        val keyRegex = """^(\s*)${Regex.escape(key)}\s*=""".toRegex()

        var keyComments = mutableListOf<String>()

        for (i in (tableInfo.startLine + 1)..tableInfo.endLine) {
            val line = lines[i]
            val trimmed = line.trim()

            // Collect comments above the key
            if (trimmed.startsWith("#")) {
                keyComments.add(trimmed)
            }
            // Check if this is our key
            else if (keyRegex.find(line) != null) {
                return KeyInfo(
                    lineIndex = i,
                    keyComments = keyComments.toList(),
                    inlineComment = extractInlineComment(line),
                )
            }
            // Reset comment collection if we hit another key
            else if (trimmed.contains("=")) {
                keyComments.clear()
            }
            // Keep collecting comments if it's a blank line
            else if (trimmed.isEmpty()) {
                // Don't reset, blank lines can be part of comment block
            }
            // Reset on other content
            else {
                keyComments.clear()
            }
        }

        return null
    }

    /**
     * Parse a value from a key = value line.
     */
    private fun parseValue(
        line: String,
        key: String,
    ): TomlValue? {
        val keyRegex = """${Regex.escape(key)}\s*=\s*(.+?)(?:\s*#.*)?$""".toRegex()
        val match = keyRegex.find(line) ?: return null
        val valueStr = match.groupValues[1].trim()

        return when {
            // Array: ["item1", "item2"]
            valueStr.startsWith("[") && valueStr.contains("]") -> {
                TomlValue.Array(parseArrayValue(line))
            }

            // String: "value"
            valueStr.startsWith("\"") && valueStr.endsWith("\"") -> {
                TomlValue.String(valueStr.trim('"'))
            }

            // Boolean: true/false
            valueStr == "true" || valueStr == "false" -> {
                TomlValue.Boolean(valueStr.toBoolean())
            }

            // Integer
            valueStr.toLongOrNull() != null -> {
                TomlValue.Integer(valueStr.toLong())
            }

            // Float
            valueStr.toDoubleOrNull() != null -> {
                TomlValue.Float(valueStr.toDouble())
            }

            else -> {
                null
            }
        }
    }

    /**
     * Parse an array value from a line.
     * Supports format: key = ["item1", "item2", ...]
     */
    private fun parseArrayValue(line: String): List<String> {
        val arrayRegex = """\[(.*?)\]""".toRegex()
        val match = arrayRegex.find(line) ?: return emptyList()

        val content = match.groupValues[1]
        if (content.isBlank()) return emptyList()

        // Split by comma and remove quotes
        return content
            .split(',')
            .map { it.trim().trim('"', '\'') }
            .filter { it.isNotEmpty() }
    }

    /**
     * Build a key = value line with proper formatting.
     */
    private fun buildValueLine(
        key: String,
        value: TomlValue,
        indent: String,
        comment: String?,
    ): String {
        val valueStr =
            when (value) {
                is TomlValue.String -> {
                    "\"${value.value}\""
                }

                is TomlValue.Integer -> {
                    value.value.toString()
                }

                is TomlValue.Float -> {
                    value.value.toString()
                }

                is TomlValue.Boolean -> {
                    value.value.toString()
                }

                is TomlValue.Array -> {
                    val items = value.items.joinToString(", ") { "\"$it\"" }
                    "[$items]"
                }

                is TomlValue.InlineTable -> {
                    val pairs =
                        value.pairs.entries.joinToString(", ") { (k, v) ->
                            "$k = ${buildValueString(v)}"
                        }
                    "{ $pairs }"
                }
            }

        val base = "$indent$key = $valueStr"
        return if (comment != null) {
            "$base  $comment"
        } else {
            base
        }
    }

    /**
     * Build a value string for inline table entries.
     */
    private fun buildValueString(value: TomlValue): String =
        when (value) {
            is TomlValue.String -> "\"${value.value}\""
            is TomlValue.Integer -> value.value.toString()
            is TomlValue.Float -> value.value.toString()
            is TomlValue.Boolean -> value.value.toString()
            is TomlValue.Array -> value.toString()
            is TomlValue.InlineTable -> value.toString()
        }

    /**
     * Extract inline comment from a line.
     * Returns the comment (including #) or null if no comment.
     */
    private fun extractInlineComment(line: String): String? {
        // Find # that's not inside a string
        var inString = false
        var escapeNext = false

        line.forEachIndexed { index, char ->
            when {
                escapeNext -> {
                    escapeNext = false
                }

                char == '\\' -> {
                    escapeNext = true
                }

                char == '"' -> {
                    inString = !inString
                }

                char == '#' && !inString -> {
                    return line.substring(index)
                }
            }
        }

        return null
    }
}
