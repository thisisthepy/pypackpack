package org.thisisthepy.python.multiplatform.packpack.bundle

import org.junit.jupiter.api.io.TempDir
import org.thisisthepy.python.multiplatform.packpack.bundle.fat.FatWheelBundler
import org.thisisthepy.python.multiplatform.packpack.bundle.patch.WheelPatchBundler
import org.thisisthepy.python.multiplatform.packpack.bundle.resource.ResourceBundler
import org.thisisthepy.python.multiplatform.packpack.bundle.single.SingleWheelBundler
import org.thisisthepy.python.multiplatform.packpack.utils.Platforms
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BundlerInterfaceTest {
    @TempDir
    lateinit var tempDir: File

    private fun createPackageDir(name: String = "mypkg", version: String = "1.0.0"): File {
        val dir = File(tempDir, name)
        dir.mkdirs()
        File(dir, "pyproject.toml").writeText(
            """
            [project]
            name = "$name"
            version = "$version"
            """.trimIndent(),
        )
        return dir
    }

    private fun writeDestdir(
        pkg: File,
        relativePath: String,
        content: String = "print('hello')",
        target: String = Platforms.normalizeTarget("macos")!!,
        type: String = "debug",
        level: String = "instant",
    ) {
        val file = File(File(pkg, "dist/$target/$type/$level/usr/local/lib/python3.13/site-packages"), relativePath)
        file.parentFile.mkdirs()
        file.writeText(content)
    }

    @Test
    fun fromId_resolvesAllBundleTypesCaseInsensitively() {
        assertEquals(BundleType.BINARY, BundleType.fromId("binary"))
        assertEquals(BundleType.FAT, BundleType.fromId("FAT"))
        assertEquals(BundleType.SINGLE, BundleType.fromId("Single"))
        assertEquals(BundleType.PATCH, BundleType.fromId("patch"))
        assertEquals(BundleType.RESOURCE, BundleType.fromId("RESOURCE"))
        assertNull(BundleType.fromId("nonexistent"))
    }

    @Test
    fun create_instantiatesCorrectBundlerForEveryType() {
        assertTrue(BundlerInterface.create(BundleType.BINARY) is UnimplementedBundler)
        assertTrue(BundlerInterface.create(BundleType.SINGLE) is SingleWheelBundler)
        assertTrue(BundlerInterface.create(BundleType.FAT) is FatWheelBundler)
        assertTrue(BundlerInterface.create(BundleType.PATCH) is WheelPatchBundler)
        assertTrue(BundlerInterface.create(BundleType.RESOURCE) is ResourceBundler)
    }

    @Test
    fun unimplementedBundler_binaryReturnsFailureWithNotImplementedError() {
        val bundler = BundlerInterface.create(BundleType.BINARY)
        val request = BundleRequest(packageDir = tempDir, target = "macos")
        val result = bundler.bundle(request)

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertTrue(exception is NotImplementedError)
        assertTrue(exception.message.orEmpty().contains("binary"))
    }

    @Test
    fun bundler_verifiesArtifactFileContentReachesDiskAndIsReadableZip() {
        val pkg = createPackageDir("mypkg", "1.2.3")
        writeDestdir(pkg, "mypkg/__init__.py", "FOO = 42\n")

        val bundler = BundlerInterface.create(BundleType.SINGLE)
        val result = bundler.bundle(BundleRequest(packageDir = pkg, target = "macos")).getOrThrow()

        val artifact = result.artifactFile
        assertNotNull(artifact, "Single wheel bundler must produce a non-null artifact file")
        assertTrue(artifact.isFile, "Artifact file must exist on disk: ${artifact.absolutePath}")
        assertTrue(artifact.name.endsWith(".whl"), "Artifact must be a .whl file")

        ZipFile(artifact).use { zip ->
            val entries = zip.entries().asSequence().map { it.name }.toSet()
            assertTrue(entries.contains("mypkg/__init__.py"), "Zip must contain payload file")
            assertTrue(entries.contains("mypkg-1.2.3.dist-info/RECORD"), "Zip must contain RECORD")
            assertTrue(entries.contains("mypkg-1.2.3.dist-info/METADATA"), "Zip must contain METADATA")
            assertTrue(entries.contains("mypkg-1.2.3.dist-info/WHEEL"), "Zip must contain WHEEL")

            val initEntry = zip.getEntry("mypkg/__init__.py")
            val content = zip.getInputStream(initEntry).bufferedReader().readText()
            assertEquals("FOO = 42\n", content, "Payload content inside zip must match original source")
        }
    }
}
