package org.thisisthepy.python.multiplatform.packpack.utils.toml

import kotlin.test.Test
import kotlin.test.assertEquals

class TomlEditorTest {
    @Test
    fun getArrayPreservesTrailingSingleQuoteInsideArrayStringValue() {
        // `uv add --marker "..."` persists PEP 508 marker clauses whose value is itself
        // single-quoted, e.g. `sys_platform == 'win32'`, right up against the array string's own
        // closing double quote: `"... sys_platform == 'win32'"`. `parseArrayValue`'s old
        // `.trim('"', '\'')` call strips *every* leading/trailing char that is a quote of either
        // kind, not just the outer wrapping pair, so it also ate the `'` that closes `'win32'`.
        // That silently truncated every dependency marker CrossEnv read back from disk.
        val content =
            """
            [project]
            name = "core"
            dependencies = [
                "requests>=2.34.2 ; platform_machine == 'x86_64' and sys_platform == 'win32'",
            ]
            """.trimIndent()

        val entries = TomlEditor(content).getArray("project", "dependencies")

        assertEquals(
            listOf("requests>=2.34.2 ; platform_machine == 'x86_64' and sys_platform == 'win32'"),
            entries,
        )
    }

    @Test
    fun getArrayStillUnwrapsPlainQuotedStrings() {
        val content =
            """
            [tool.ppp.dependencies]
            platforms = ["windows", "linux"]
            """.trimIndent()

        val entries = TomlEditor(content).getArray("tool.ppp.dependencies", "platforms")

        assertEquals(listOf("windows", "linux"), entries)
    }

    @Test
    fun getValueParsesInlineTableAndGetInlineTableArrayParsesAuthors() {
        val content =
            """
            [project]
            name = "core"
            readme = { file = "README.md", content-type = "text/markdown" }
            license = { text = "MIT" }
            authors = [
                { name = "thisisthepy", email = "dev@thisisthepy.org" },
                { name = "Alice" },
            ]
            dependencies = ["pkg[extra]>=1.0", "openvino; (sys_platform == 'win32')"]
            """.trimIndent()

        val editor = TomlEditor(content)
        val readme = editor.getValue("project", "readme") as TomlValue.InlineTable
        assertEquals(TomlValue.String("README.md"), readme.pairs["file"])
        assertEquals(TomlValue.String("text/markdown"), readme.pairs["content-type"])

        val license = editor.getValue("project", "license") as TomlValue.InlineTable
        assertEquals(TomlValue.String("MIT"), license.pairs["text"])

        assertEquals(
            listOf(
                mapOf("name" to "thisisthepy", "email" to "dev@thisisthepy.org"),
                mapOf("name" to "Alice"),
            ),
            editor.getInlineTableArray("project", "authors"),
        )
        assertEquals(
            listOf("pkg[extra]>=1.0", "openvino; (sys_platform == 'win32')"),
            editor.getArray("project", "dependencies"),
        )
    }

    @Test
    fun getTableKeysPreservesDeclarationOrderWithMultiLineArrays() {
        val content =
            """
            [project.optional-dependencies]
            cpu = []
            npu = [
                "openvino; sys_platform == 'linux'",
            ]
            dev = ["pytest>=8.0"]
            """.trimIndent()

        val editor = TomlEditor(content)
        assertEquals(listOf("cpu", "npu", "dev"), editor.getTableKeys("project.optional-dependencies"))
        assertEquals(emptyList(), editor.getArray("project.optional-dependencies", "cpu"))
        assertEquals(listOf("openvino; sys_platform == 'linux'"), editor.getArray("project.optional-dependencies", "npu"))
        assertEquals(listOf("pytest>=8.0"), editor.getArray("project.optional-dependencies", "dev"))
    }
}
