package org.thisisthepy.python.multiplatform.packpack.utils.toml

/**
 * A style-preserving TOML editor for manipulating TOML documents.
 *
 * This editor provides operations for reading and modifying TOML documents while
 * preserving comments, whitespace, and original formatting.
 *
 * Features:
 * - Preserves inline comments and block comments
 * - Maintains original indentation and whitespace
 * - Type-safe value operations via TomlValue sealed class
 * - Array manipulation with optional custom sorting
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
        val endLine: Int, // End line of value (inclusive, same as lineIndex for single-line)
        val keyComments: List<String>, // Comments above the key
        val inlineComment: String?, // Comment on the same line as key = value
    )

    // ===== Public API: Table Operations =====

    /**
     * Check if a table exists.
     *
     * @param path Dotted table path (e.g., "database.config", "server.settings")
     * @return true if table exists, false otherwise
     */
    fun hasTable(path: String): Boolean = findTable(path) != null

    /**
     * Create a new table.
     *
     * @param path Dotted table path (e.g., "database.config", "server.settings")
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
     * @param path Dotted table path (e.g., "app.metadata", "build.options")
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
     * @param tablePath Dotted table path (e.g., "database.config", "server.settings")
     * @param key Key name within the table
     * @return TomlValue if found, null otherwise
     */
    fun getValue(
        tablePath: String,
        key: String,
    ): TomlValue? {
        val tableInfo = findTable(tablePath) ?: return null
        val keyInfo = findKey(tableInfo, key) ?: return null
        return parseValue(key, keyInfo.lineIndex, keyInfo.endLine)
    }

    /**
     * Set a value in a table.
     *
     * @param tablePath Dotted table path (e.g., "database.config", "server.settings")
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
            // Delete continuation lines first (reverse order)
            for (i in keyInfo.endLine downTo keyInfo.lineIndex + 1) {
                lines.removeAt(i)
            }

            // Update existing key
            val line = lines[keyInfo.lineIndex]
            val indent = line.takeWhile { it.isWhitespace() }

            // Handle multi-line arrays
            if (value is TomlValue.Array && value.format is ArrayFormat.MultiLine) {
                val multiLineArray = buildMultiLineArray(value.items, value.format)
                val arrayLines = multiLineArray.split("\n")

                // Replace first line with key = [
                lines[keyInfo.lineIndex] = "$indent$key = ${arrayLines[0]}"

                // Insert remaining array lines
                arrayLines.drop(1).forEachIndexed { index, arrayLine ->
                    lines.add(keyInfo.lineIndex + 1 + index, indent + arrayLine)
                }
            } else {
                // Single-line value
                lines[keyInfo.lineIndex] = buildValueLine(key, value, indent, keyInfo.inlineComment)
            }
        } else {
            // Add new key at end of table
            if (value is TomlValue.Array && value.format is ArrayFormat.MultiLine) {
                val multiLineArray = buildMultiLineArray(value.items, value.format)
                val arrayLines = multiLineArray.split("\n")

                // Add key = [
                lines.add(tableInfo.endLine + 1, "$key = ${arrayLines[0]}")

                // Add remaining array lines
                arrayLines.drop(1).forEach { arrayLine ->
                    lines.add(tableInfo.endLine + 1 + lines.size - tableInfo.endLine - 1, arrayLine)
                }
            } else {
                // Single-line value
                val newLine = buildValueLine(key, value, "", null)
                lines.add(tableInfo.endLine + 1, newLine)
            }
        }
    }

    /**
     * Delete a key from a table.
     *
     * @param tablePath Dotted table path (e.g., "project.info", "app.metadata")
     * @param key Key name to delete
     */
    fun deleteKey(
        tablePath: String,
        key: String,
    ) {
        val tableInfo = findTable(tablePath) ?: return
        val keyInfo = findKey(tableInfo, key) ?: return

        // Delete key comments, key line, and value continuation lines
        val deleteStart = keyInfo.lineIndex - keyInfo.keyComments.size
        val deleteEnd = keyInfo.endLine // Use endLine instead of lineIndex

        for (i in deleteEnd downTo deleteStart) {
            lines.removeAt(i)
        }
    }

    // ===== Public API: Array Convenience Methods =====

    /**
     * Get a string array from a table.
     *
     * @param tablePath Dotted table path (e.g., "app.metadata", "build.options")
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
     * @param tablePath Dotted table path (e.g., "app.metadata", "build.options")
     * @param key Key name within the table
     * @param items List of strings to set
     * @param sorter Optional function to sort the items before setting
     * @param format Array format (default: single-line, or specify multi-line format)
     */
    fun setArray(
        tablePath: String,
        key: String,
        items: List<String>,
        sorter: ((List<String>) -> List<String>)? = null,
        format: ArrayFormat = ArrayFormat.SingleLine,
    ) {
        val sortedItems = sorter?.invoke(items) ?: items
        setValue(tablePath, key, TomlValue.Array(sortedItems, format))
    }

    /**
     * Add items to a string array in a table.
     *
     * If the array doesn't exist, it will be created.
     * Duplicates are automatically removed.
     *
     * @param tablePath Dotted table path (e.g., "server.settings", "project.info")
     * @param key Key name within the table
     * @param items Items to add
     * @param sorter Optional function to sort the final array
     * @param format Array format (preserves existing format if not specified)
     */
    fun addToArray(
        tablePath: String,
        key: String,
        vararg items: String,
        sorter: ((List<String>) -> List<String>)? = null,
        format: ArrayFormat? = null,
    ) {
        val currentItems = getArray(tablePath, key).toMutableSet()
        currentItems.addAll(items)

        // Preserve existing format if not specified
        val finalFormat =
            format ?: (getValue(tablePath, key) as? TomlValue.Array)?.format
                ?: ArrayFormat.SingleLine

        setArray(tablePath, key, currentItems.toList(), sorter, finalFormat)
    }

    /**
     * Remove items from a string array in a table.
     *
     * If the item doesn't exist, it's silently ignored.
     *
     * @param tablePath Dotted table path (e.g., "database.config", "build.options")
     * @param key Key name within the table
     * @param items Items to remove
     * @param format Array format (preserves existing format if not specified)
     */
    fun removeFromArray(
        tablePath: String,
        key: String,
        vararg items: String,
        format: ArrayFormat? = null,
    ) {
        val currentItems = getArray(tablePath, key).toMutableSet()
        currentItems.removeAll(items.toSet())

        // Preserve existing format if not specified
        val finalFormat =
            format ?: (getValue(tablePath, key) as? TomlValue.Array)?.format
                ?: ArrayFormat.SingleLine

        setArray(tablePath, key, currentItems.toList(), format = finalFormat)
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
     * Find a table by its dotted path (e.g., "database.config", "server.settings").
     * Returns table information including location and associated comments.
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
                val inlineComment = extractInlineComment(line)

                // Determine endLine based on value type
                val endLine =
                    if (line.contains("= [")) {
                        // Potential array - find closing bracket
                        findArrayEndLine(i, tableInfo.endLine) ?: i
                    } else {
                        // Single-line value
                        i
                    }

                return KeyInfo(
                    lineIndex = i,
                    endLine = endLine,
                    keyComments = keyComments.toList(),
                    inlineComment = inlineComment,
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
     * Parse a value from a key = value line range.
     */
    private fun parseValue(
        key: String,
        startLine: Int,
        endLine: Int,
    ): TomlValue? {
        val firstLine = lines[startLine]
        val keyRegex = """${Regex.escape(key)}\s*=\s*(.+?)(?:\s*#.*)?$""".toRegex()
        val match = keyRegex.find(firstLine) ?: return null
        val valueStr = match.groupValues[1].trim()

        return when {
            // Array: ["item1", "item2"] or multi-line [...]
            valueStr.startsWith("[") -> {
                val items = parseArrayValue(startLine, endLine)
                val format = detectArrayFormat(startLine, endLine)
                TomlValue.Array(items, format)
            }

            // String: "value"
            valueStr.startsWith("\"") && valueStr.endsWith("\"") -> {
                TomlValue.String(valueStr.trim('\"'))
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
     * Parse an array value from line range.
     * Supports multi-line arrays with comments and trailing commas.
     */
    private fun parseArrayValue(
        startLine: Int,
        endLine: Int,
    ): List<String> {
        // Combine lines and strip comments
        val contentBuilder = StringBuilder()
        for (i in startLine..endLine) {
            val line = lines[i]
            val withoutComment = stripInlineComment(line)
            contentBuilder.append(withoutComment).append(" ")
        }

        val fullContent = contentBuilder.toString()

        // Extract content between [ and ]
        val arrayRegex = """\[(.*?)\]""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val match = arrayRegex.find(fullContent) ?: return emptyList()

        val content = match.groupValues[1]
        if (content.isBlank()) return emptyList()

        // Split by comma and remove quotes
        return content
            .split(',')
            .map { it.trim().trim('\"', '\'') }
            .filter { it.isNotEmpty() }
    }

    /**
     * Build a multi-line array string with proper formatting.
     *
     * @param items Array items to format
     * @param format Multi-line format specification
     * @return Multi-line array string (without key)
     */
    private fun buildMultiLineArray(
        items: List<String>,
        format: ArrayFormat.MultiLine,
    ): String {
        val lines = mutableListOf<String>()

        // Opening bracket
        lines.add("[")

        // Array items
        items.forEachIndexed { index, item ->
            val isLast = index == items.size - 1
            val itemLine =
                if (isLast && format.trailingComma) {
                    "${format.indentation}\"$item\","
                } else if (!isLast) {
                    "${format.indentation}\"$item\","
                } else {
                    "${format.indentation}\"$item\""
                }
            lines.add(itemLine)
        }

        // Closing bracket
        lines.add("]")

        return lines.joinToString("\n")
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

    /**
     * Strip inline comment from a line.
     * Preserves # characters inside strings.
     */
    private fun stripInlineComment(line: String): String {
        val comment = extractInlineComment(line)
        return if (comment != null) {
            line.substringBefore(comment)
        } else {
            line
        }
    }

    /**
     * Check if a position in text is inside a quoted string.
     * Handles escape sequences.
     */
    private fun isInsideString(
        text: String,
        position: Int,
    ): Boolean {
        var inString = false
        var escapeNext = false

        for (i in 0 until position) {
            when {
                escapeNext -> escapeNext = false
                text[i] == '\\' -> escapeNext = true
                text[i] == '"' -> inString = !inString
            }
        }

        return inString
    }

    /**
     * Find the line where an array value ends (closing bracket).
     *
     * @param startLine Line index where array starts (key = [...])
     * @param tableEndLine Last line of the table (boundary limit)
     * @return Line index of closing ], or null if malformed
     */
    private fun findArrayEndLine(
        startLine: Int,
        tableEndLine: Int,
    ): Int? {
        val firstLine = lines[startLine]

        // Check if array closes on same line
        val valueStart = firstLine.indexOf('=')
        if (valueStart == -1) return null

        val afterEquals = firstLine.substring(valueStart + 1)
        val openBracket = afterEquals.indexOf('[')
        if (openBracket == -1) return null

        // Track bracket depth for nested arrays
        var depth = 0
        var inString = false
        var escapeNext = false

        // Process from opening [ to end of line
        val startContent = afterEquals.substring(openBracket)
        for (char in startContent) {
            when {
                escapeNext -> escapeNext = false
                char == '\\' -> escapeNext = true
                char == '"' -> inString = !inString
                !inString && char == '[' -> depth++
                !inString && char == ']' -> {
                    depth--
                    if (depth == 0) return startLine // Closed on same line
                }
            }
        }

        // Scan subsequent lines for closing bracket
        for (lineIndex in (startLine + 1)..tableEndLine) {
            val line = lines[lineIndex]

            for (char in line) {
                when {
                    escapeNext -> escapeNext = false
                    char == '\\' -> escapeNext = true
                    char == '"' -> inString = !inString
                    !inString && char == '[' -> depth++
                    !inString && char == ']' -> {
                        depth--
                        if (depth == 0) return lineIndex
                    }
                }
            }

            // If we hit another table header, array is malformed
            if (line.trim().startsWith("[") && !inString) {
                return null
            }
        }

        // Array not closed within table
        return null
    }

    /**
     * Detect the formatting style of a multi-line array.
     *
     * @param startLine Line index where array starts
     * @param endLine Line index where array ends
     * @return ArrayFormat describing the style
     */
    private fun detectArrayFormat(
        startLine: Int,
        endLine: Int,
    ): ArrayFormat {
        // Single-line array
        if (startLine == endLine) {
            return ArrayFormat.SingleLine
        }

        // Multi-line array - detect indentation and trailing comma
        var indentation = ""
        var hasTrailingComma = false

        // Find first array item line to detect indentation
        for (i in (startLine + 1)..endLine) {
            val line = lines[i]
            val trimmed = line.trim()

            // Skip empty lines and comments
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

            // Check if this is the closing bracket
            if (trimmed == "]" || trimmed.startsWith("]")) break

            // This is an item line - extract indentation
            indentation = line.takeWhile { it.isWhitespace() }
            break
        }

        // Check for trailing comma on the line before closing bracket
        for (i in endLine downTo startLine) {
            val line = lines[i]
            val trimmed = line.trim()

            // Skip the closing bracket line
            if (trimmed == "]" || trimmed.startsWith("]")) continue

            // Skip empty lines and comments
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

            // Check if this line ends with comma (ignoring inline comments)
            val withoutComment = stripInlineComment(line).trim()
            if (withoutComment.endsWith(",")) {
                hasTrailingComma = true
            }
            break
        }

        return ArrayFormat.MultiLine(
            indentation = indentation,
            trailingComma = hasTrailingComma,
        )
    }
}
