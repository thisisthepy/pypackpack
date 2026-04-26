package org.thisisthepy.python.multiplatform.packpack.dependency.middleware.environment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TargetWheelInspectorTest {
    private val inspector = TargetWheelInspector()

    @Test
    fun `parseDependencySpec extracts normalized name`() {
        val parsed = inspector.parseDependencySpec("charset_normalizer>=3.0")

        assertEquals("charset-normalizer", parsed.normalizedName)
    }

    @Test
    fun `parseLockPackages extracts package versions and wheels`() {
        val packages =
            inspector.parseLockPackages(
                """
                version = 1

                [[package]]
                name = "requests"
                version = "2.33.0"
                wheels = [
                    { url = "https://files.pythonhosted.org/packages/56/5d/requests-2.33.0-py3-none-any.whl" },
                ]

                [[package]]
                name = "charset-normalizer"
                version = "3.4.6"
                wheels = [
                    { url = "https://files.pythonhosted.org/packages/example/charset_normalizer-3.4.6-cp312-cp312-win_amd64.whl" },
                ]
                """.trimIndent(),
            )

        assertEquals(2, packages.size)
        assertEquals("requests", packages[0].name)
        assertEquals("2.33.0", packages[0].version)
        assertEquals(
            "https://files.pythonhosted.org/packages/56/5d/requests-2.33.0-py3-none-any.whl",
            packages[0].wheelUrls.single(),
        )
    }

    @Test
    fun `matches any wheel for all targets`() {
        assertTrue(
            inspector.isWheelCompatibleWithTarget(
                "requests-2.33.0-py3-none-any.whl",
                "x86_64-pc-windows-msvc",
            ),
        )
        assertTrue(
            inspector.isWheelCompatibleWithTarget(
                "requests-2.33.0-py3-none-any.whl",
                "x86_64-unknown-linux-gnu",
            ),
        )
    }

    @Test
    fun `matches windows wheel`() {
        assertTrue(
            inspector.isWheelCompatibleWithTarget(
                "numpy-2.1.1-cp312-cp312-win_amd64.whl",
                "x86_64-pc-windows-msvc",
            ),
        )
    }

    @Test
    fun `matches linux manylinux wheel`() {
        assertTrue(
            inspector.isWheelCompatibleWithTarget(
                "numpy-2.1.1-cp312-cp312-manylinux_2_17_x86_64.manylinux2014_x86_64.whl",
                "x86_64-unknown-linux-gnu",
            ),
        )
    }

    @Test
    fun `matches mac universal2 wheel for arm64`() {
        assertTrue(
            inspector.isWheelCompatibleWithTarget(
                "orjson-3.10.0-cp312-cp312-macosx_10_15_universal2.whl",
                "aarch64-apple-darwin",
            ),
        )
    }

    @Test
    fun `does not match wrong target`() {
        assertFalse(
            inspector.isWheelCompatibleWithTarget(
                "numpy-2.1.1-cp312-cp312-win_amd64.whl",
                "x86_64-unknown-linux-gnu",
            ),
        )
    }
}
