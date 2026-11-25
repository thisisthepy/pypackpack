package org.thisisthepy.python.multiplatform.packpack.bundle

import org.thisisthepy.python.multiplatform.packpack.compile.backend.external.ndkHostTag
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `docs/SPEC.md` *Android runtime libraries* (#172): the DT_NEEDED reader and the NDK runtime lookup that
 * `toolchain` uses to put `libc++_shared.so` into an APK's jniLibs. CI has no NDK, so the ELF files are
 * written byte by byte here and the NDK is a directory with the layout the lookup reads.
 */
class AndroidRuntimeLibrariesTest {
    private val root: File = Files.createTempDirectory("android-runtime-libs").toFile()

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun readsTheNeededEntriesOfAnElfSharedObject() {
        val so = File(root, "ext.so").apply { writeBytes(elfSharedObject(listOf("libc++_shared.so", "libm.so", "libpython3.15t.so"))) }

        assertEquals(listOf("libc++_shared.so", "libm.so", "libpython3.15t.so"), elfNeededLibraries(so))
    }

    @Test
    fun aFileThatIsNotElfReadsAsNotElf() {
        val text = File(root, "fake.so").apply { writeText("not an ELF file") }
        val empty = File(root, "empty.so").apply { writeBytes(ByteArray(0)) }

        assertNull(elfNeededLibraries(text))
        assertNull(elfNeededLibraries(empty))
    }

    @Test
    fun aPayloadWhoseExtensionNeedsTheSharedCxxRuntimeGetsTheNdksCopyForItsTriple() {
        val payload = payloadWith("numpy/_core/_multiarray_umath.so" to listOf("libc++_shared.so", "libc.so"))
        val ndk = fakeNdk()

        val result = androidRuntimeLibraries(payload, "aarch64-linux-android", ndkRoot = ndk)

        val libraries = result.getOrThrow()
        assertEquals(1, libraries.size)
        assertEquals("libc++_shared.so", libraries.single().name)
        assertEquals(File(ndkLibDir(ndk, "aarch64-linux-android"), "libc++_shared.so"), libraries.single().source)
        assertEquals("arm64-v8a", libraries.single().abi)
        assertEquals(listOf("numpy/_core/_multiarray_umath.so"), libraries.single().neededBy)
    }

    @Test
    fun theX86TargetGetsTheX86CopyAndAbi() {
        val payload = payloadWith("tokenizers/tokenizers.so" to listOf("libc++_shared.so"))
        val ndk = fakeNdk()

        val library = androidRuntimeLibraries(payload, "x86_64-linux-android", ndkRoot = ndk).getOrThrow().single()

        assertEquals(File(ndkLibDir(ndk, "x86_64-linux-android"), "libc++_shared.so"), library.source)
        assertEquals("x86_64", library.abi)
    }

    @Test
    fun aPayloadWhereNothingNeedsItGetsNothingAndNeedsNoNdk() {
        val payload = payloadWith("regex/_regex.so" to listOf("libc.so", "libpython3.15t.so"))
        File(payload, "module.py").writeText("x = 1\n")

        assertEquals(emptyList(), androidRuntimeLibraries(payload, "aarch64-linux-android", ndkRoot = null).getOrThrow())
    }

    @Test
    fun aPayloadThatAlreadyCarriesTheLibraryGetsNothing() {
        val payload = payloadWith("pkg/ext.so" to listOf("libc++_shared.so"))
        File(payload, "pkg/libc++_shared.so").writeBytes(elfSharedObject(emptyList()))

        assertEquals(emptyList(), androidRuntimeLibraries(payload, "aarch64-linux-android", ndkRoot = null).getOrThrow())
    }

    @Test
    fun aPayloadThatNeedsItWithoutAnNdkFailsNamingTheLibraryAndTheExtension() {
        val payload = payloadWith("numpy/_core/_multiarray_umath.so" to listOf("libc++_shared.so"))

        val error = androidRuntimeLibraries(payload, "aarch64-linux-android", ndkRoot = null).exceptionOrNull()

        val message = error?.message.orEmpty()
        assertTrue("libc++_shared.so" in message, message)
        assertTrue("numpy/_core/_multiarray_umath.so" in message, message)
        assertTrue("ANDROID_NDK_HOME" in message, message)
    }

    @Test
    fun anNdkWithoutTheLibraryForTheTripleFailsNamingThePathItLookedAt() {
        val payload = payloadWith("pkg/ext.so" to listOf("libc++_shared.so"))
        val ndk = File(root, "empty-ndk").apply { mkdirs() }

        val message = androidRuntimeLibraries(payload, "aarch64-linux-android", ndkRoot = ndk).exceptionOrNull()?.message.orEmpty()

        assertTrue(ndkLibDir(ndk, "aarch64-linux-android").path in message, message)
    }

    @Test
    fun aNonAndroidTargetIsRefused() {
        val payload = payloadWith("pkg/ext.so" to listOf("libc++_shared.so"))

        val message = androidRuntimeLibraries(payload, "aarch64-apple-darwin", ndkRoot = fakeNdk()).exceptionOrNull()?.message.orEmpty()

        assertTrue("aarch64-apple-darwin" in message, message)
    }

    @Test
    fun theNdkIsFoundTheWayTheCrossBuildFindsIt() {
        val sdk = File(root, "sdk")
        val older = File(sdk, "ndk/26.3.11579264/toolchains/llvm/prebuilt").apply { mkdirs() }.parentFile.parentFile.parentFile
        val newer = File(sdk, "ndk/27.1.12297006/toolchains/llvm/prebuilt").apply { mkdirs() }.parentFile.parentFile.parentFile

        assertEquals(newer, androidNdkRoot(mapOf("ANDROID_HOME" to sdk.path)))
        assertEquals(older, androidNdkRoot(mapOf("ANDROID_NDK_HOME" to older.path, "ANDROID_HOME" to sdk.path)))
        assertNull(androidNdkRoot(emptyMap()))
    }

    private fun payloadWith(vararg extensions: Pair<String, List<String>>): File {
        val payload = File(root, "payload").apply { mkdirs() }
        extensions.forEach { (path, needed) ->
            File(payload, path).apply { parentFile.mkdirs() }.writeBytes(elfSharedObject(needed))
        }
        return payload
    }

    private fun ndkLibDir(ndk: File, triple: String): File =
        File(ndk, "toolchains/llvm/prebuilt/${ndkHostTag()}/sysroot/usr/lib/$triple")

    private fun fakeNdk(): File {
        val ndk = File(root, "ndk")
        listOf("aarch64-linux-android", "x86_64-linux-android").forEach { triple ->
            File(ndkLibDir(ndk, triple).apply { mkdirs() }, "libc++_shared.so").writeBytes(elfSharedObject(emptyList()))
        }
        return ndk
    }

    /**
     * A minimal little-endian ELF64 `ET_DYN`: the header, two program headers (one `PT_LOAD` mapping the
     * whole file at address 0x1000, one `PT_DYNAMIC`), the dynamic array (`DT_NEEDED` entries, `DT_STRTAB`,
     * `DT_NULL`) and the string table. No section headers, as after a full strip: the loader reads
     * program headers, and so does the reader under test.
     */
    private fun elfSharedObject(needed: List<String>): ByteArray {
        val base = 0x1000L
        val headerSize = 64
        val phSize = 56
        val dynOffset = headerSize + 2 * phSize
        val dynEntries = needed.size + 2
        val strOffset = dynOffset + dynEntries * 16
        val strings = ByteArrayOutputStream().apply { write(0) }
        val nameOffsets = needed.map { name -> strings.size().also { strings.write(name.toByteArray()); strings.write(0) } }
        val total = strOffset + strings.size()
        val buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1, 1, 0))
        buf.position(16)
        buf.putShort(3) // ET_DYN
        buf.putShort(183) // EM_AARCH64
        buf.putInt(1)
        buf.putLong(0) // e_entry
        buf.putLong(headerSize.toLong()) // e_phoff
        buf.putLong(0) // e_shoff
        buf.putInt(0)
        buf.putShort(headerSize.toShort())
        buf.putShort(phSize.toShort())
        buf.putShort(2) // e_phnum
        buf.putShort(64)
        buf.putShort(0)
        buf.putShort(0)
        // PT_LOAD
        buf.putInt(1); buf.putInt(5); buf.putLong(0); buf.putLong(base); buf.putLong(base)
        buf.putLong(total.toLong()); buf.putLong(total.toLong()); buf.putLong(0x1000)
        // PT_DYNAMIC
        buf.putInt(2); buf.putInt(6); buf.putLong(dynOffset.toLong()); buf.putLong(base + dynOffset); buf.putLong(base + dynOffset)
        buf.putLong(dynEntries * 16L); buf.putLong(dynEntries * 16L); buf.putLong(8)
        nameOffsets.forEach { offset -> buf.putLong(1); buf.putLong(offset.toLong()) }
        buf.putLong(5); buf.putLong(base + strOffset)
        buf.putLong(0); buf.putLong(0)
        buf.put(strings.toByteArray())
        return buf.array()
    }
}
