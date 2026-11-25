package org.thisisthepy.python.multiplatform.packpack.bundle

import org.thisisthepy.python.multiplatform.packpack.compile.backend.external.CargoEnvironment
import org.thisisthepy.python.multiplatform.packpack.compile.backend.external.ndkHostTag
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The NDK runtime libraries an Android payload needs at load time (`docs/SPEC.md` *Android runtime
 * libraries*, #172).
 *
 * An extension built with NDK clang that uses C++ lists `libc++_shared.so` in DT_NEEDED. Neither a wheel
 * nor the Android CPython carries it, and an app that does not ship it gets whatever copy the device has
 * (a Galaxy Z Flip4 picked an old `/vendor/lib64` one and failed on a missing symbol). The NDK's own
 * guidance is one shared libc++ per app, so `toolchain` puts the NDK's copy into the APK's jniLibs for
 * each ABI; this file says which libraries, from which NDK.
 */
data class AndroidRuntimeLibrary(
    /** The library's file name, as DT_NEEDED spells it: `libc++_shared.so`. */
    val name: String,
    /** The NDK's copy for the payload's triple. */
    val source: File,
    /** The Android ABI directory it goes under in an APK (`lib/<abi>/`): `arm64-v8a`, `x86_64`. */
    val abi: String,
    /** Payload-relative paths of the `.so` files that need it, sorted, for the log line. */
    val neededBy: List<String>,
)

/** NDK libraries an app has to ship itself when something needs them. Only the shared C++ runtime today. */
val NDK_SHIPPED_RUNTIME_LIBRARIES: List<String> = listOf("libc++_shared.so")

/** The Android ABI name of a canonical Android triple, or `null` for any other target. */
fun androidAbi(canonicalTarget: String): String? =
    when (canonicalTarget) {
        "aarch64-linux-android" -> "arm64-v8a"
        "x86_64-linux-android" -> "x86_64"
        else -> null
    }

/**
 * The NDK the cross build uses: `ANDROID_NDK_HOME`, else the newest `<ANDROID_HOME or ANDROID_SDK_ROOT>/ndk/<version>`
 * (`CargoEnvironment.locateNdk`, which `NDK` and `MesonCross` call). Reading it the same way is what makes
 * the shipped `libc++_shared.so` the one the jit-built wheels were linked against.
 */
fun androidNdkRoot(environment: Map<String, String> = System.getenv()): File? = CargoEnvironment.locateNdk(environment)

/**
 * The NDK runtime libraries that the `.so` files under [payloadDir] need and that [payloadDir] does not
 * carry itself, each resolved to [ndkRoot]'s copy for [canonicalTarget].
 *
 * Nothing needed is an empty list, whether or not an NDK exists. Something needed with [ndkRoot] `null`,
 * or an NDK without the file, is a failed `Result` naming the library, the `.so` that needs it and where
 * it was looked for.
 */
fun androidRuntimeLibraries(
    payloadDir: File,
    canonicalTarget: String,
    ndkRoot: File? = androidNdkRoot(),
): Result<List<AndroidRuntimeLibrary>> {
    val abi = androidAbi(canonicalTarget)
        ?: return Result.failure(IllegalArgumentException("'$canonicalTarget' is not an Android target; it has no NDK runtime libraries."))
    val sharedObjects = payloadDir.walkTopDown().filter { it.isFile && it.name.endsWith(".so") }.toList()
    val carried = sharedObjects.map { it.name }.toSet()
    val neededBy = sortedMapOf<String, MutableList<String>>()
    sharedObjects.forEach { so ->
        elfNeededLibraries(so).orEmpty()
            .filter { it in NDK_SHIPPED_RUNTIME_LIBRARIES && it !in carried }
            .forEach { name -> neededBy.getOrPut(name) { mutableListOf() }.add(so.relativeTo(payloadDir).invariantSeparatorsPath) }
    }
    if (neededBy.isEmpty()) return Result.success(emptyList())
    return runCatching {
        neededBy.map { (name, users) ->
            val usersText = users.sorted().joinToString(", ")
            if (ndkRoot == null) {
                throw IllegalStateException(
                    "$usersText need $name, which an Android app has to ship itself, and no Android NDK was found " +
                        "to take it from. Set ANDROID_NDK_HOME, or install an NDK under ANDROID_HOME's ndk directory " +
                        "(the one the cross build uses).",
                )
            }
            val libDir = File(ndkRoot, "toolchains/llvm/prebuilt/${ndkHostTag()}/sysroot/usr/lib/$canonicalTarget")
            val source = File(libDir, name)
            if (!source.isFile) {
                throw IllegalStateException("$usersText need $name, and the NDK at $ndkRoot has no $source.")
            }
            AndroidRuntimeLibrary(name, source, abi, users.sorted())
        }
    }
}

/**
 * The DT_NEEDED entries of an ELF shared object, in file order, or `null` when [file] is not a readable
 * ELF64 file.
 *
 * Read from the program headers, as the loader does (`PT_DYNAMIC` for the dynamic array, `DT_STRTAB`
 * mapped through the `PT_LOAD` that contains it), so a fully stripped file without section headers
 * reads the same. Every Android target this library knows is 64-bit.
 */
fun elfNeededLibraries(file: File): List<String>? =
    runCatching { RandomAccessFile(file, "r").use { readNeeded(it) } }.getOrNull()

private fun readNeeded(raf: RandomAccessFile): List<String>? {
    val ident = ByteArray(16)
    if (raf.length() < 64) return null
    raf.readFully(ident)
    if (ident[0] != 0x7f.toByte() || ident[1] != 'E'.code.toByte() || ident[2] != 'L'.code.toByte() || ident[3] != 'F'.code.toByte()) return null
    if (ident[4] != 2.toByte()) return null
    val order = if (ident[5] == 1.toByte()) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
    val header = read(raf, 0, 64, order)
    val phOff = header.getLong(0x20)
    val phEntSize = header.getShort(0x36).toInt() and 0xffff
    val phNum = header.getShort(0x38).toInt() and 0xffff
    if (phEntSize < 56 || phNum == 0) return null

    data class Segment(val type: Int, val offset: Long, val vaddr: Long, val fileSize: Long)
    val segments = (0 until phNum).map { i ->
        val ph = read(raf, phOff + i.toLong() * phEntSize, 56, order)
        Segment(ph.getInt(0), ph.getLong(8), ph.getLong(0x10), ph.getLong(0x20))
    }
    val dynamic = segments.firstOrNull { it.type == PT_DYNAMIC } ?: return emptyList()
    val entries = read(raf, dynamic.offset, dynamic.fileSize.toInt(), order)
    val needed = mutableListOf<Long>()
    var strtab: Long? = null
    var i = 0
    while (i + 16 <= entries.capacity()) {
        val tag = entries.getLong(i)
        val value = entries.getLong(i + 8)
        if (tag == DT_NULL) break
        if (tag == DT_NEEDED) needed += value
        if (tag == DT_STRTAB) strtab = value
        i += 16
    }
    if (needed.isEmpty()) return emptyList()
    val tableAddress = strtab ?: return null
    val load = segments.firstOrNull { it.type == PT_LOAD && tableAddress >= it.vaddr && tableAddress < it.vaddr + it.fileSize } ?: return null
    val tableOffset = tableAddress - load.vaddr + load.offset
    return needed.map { readString(raf, tableOffset + it) }
}

private fun read(raf: RandomAccessFile, offset: Long, size: Int, order: ByteOrder): ByteBuffer {
    require(offset >= 0 && size >= 0 && offset + size <= raf.length()) { "ELF read past the end of the file" }
    val bytes = ByteArray(size)
    raf.seek(offset)
    raf.readFully(bytes)
    return ByteBuffer.wrap(bytes).order(order)
}

private fun readString(raf: RandomAccessFile, offset: Long): String {
    raf.seek(offset)
    val out = java.io.ByteArrayOutputStream()
    while (true) {
        val b = raf.read()
        require(b >= 0) { "ELF string runs past the end of the file" }
        if (b == 0) break
        out.write(b)
    }
    return out.toString(Charsets.UTF_8)
}

private const val PT_LOAD = 1
private const val PT_DYNAMIC = 2
private const val DT_NULL = 0L
private const val DT_NEEDED = 1L
private const val DT_STRTAB = 5L
