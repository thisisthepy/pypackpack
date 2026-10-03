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
}
