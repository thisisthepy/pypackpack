package org.thisisthepy.python.multiplatform.packpack.compile.backend.external

import java.io.File

/**
 * What a C extension for a target that is not the host needs from that target's CPython (docs/SPEC.md
 * *Package build*, Meson cross builds, #155): its headers, its `EXT_SUFFIX`, and what to link.
 *
 * @param linkArgs `-L<lib> -lpython3.15t` on Android (Bionic resolves no symbols from the host process),
 *   `-F<slice> -framework Python` on iOS, none on Linux.
 * @param libDir / [libraryName] what the native slot links (`ExtensionCompileRequest.libDir`): the lib
 *   directory and `libpython3.15t.so` on Android, the XCframework slice (holding `Python.framework`) on iOS.
 */
data class TargetPython(
    val canonicalTarget: String,
    val includeDir: File,
    val extensionSuffix: String,
    val linkArgs: List<String>,
    val libDir: File? = null,
    val libraryName: String? = null,
)

/**
 * Finds [canonicalTarget]'s CPython where `pypackpack python install <version> <target>` put it, in the
 * nearest `<installDirName>` at or above [packageDir], the layout the Cargo backend reads (`android_arm64/
 * prefix`, `linux_arm64`, `ios_arm64/Python.xcframework/<slice>`). Fails naming the install command.
 */
fun locateTargetPython(packageDir: File, canonicalTarget: String): TargetPython {
    fun missing(dir: String): Nothing =
        throw IllegalStateException(
            "No Python for $canonicalTarget under a '$dir' directory above $packageDir. " +
                "Run `pypackpack python install 3.15t $canonicalTarget` first.",
        )

    ANDROID_DIRS[canonicalTarget]?.let { dir ->
        val prefix = findTargetDir(packageDir, "$dir/prefix") ?: missing(dir)
        val lib = File(prefix, "lib")
        val include = headerDir(File(prefix, "include")) ?: missing(dir)
        val ldLibrary = readSysconfigValue(lib, "LDLIBRARY")
            ?: throw IllegalStateException("The Python in $lib has no _sysconfigdata naming its LDLIBRARY.")
        return TargetPython(
            canonicalTarget,
            include,
            extSuffix(lib),
            listOf("-L${lib.path}", "-l${ldLibrary.removePrefix("lib").removeSuffix(".so")}"),
            lib,
            ldLibrary,
        )
    }
    IOS_TARGETS[canonicalTarget]?.let { ios ->
        val slice = findTargetDir(packageDir, "${ios.installDirName}/Python.xcframework/${ios.slice}") ?: missing(ios.installDirName)
        val headers = File(slice, "Python.framework/Headers").takeIf { File(it, "Python.h").isFile } ?: missing(ios.installDirName)
        return TargetPython(canonicalTarget, headers, extSuffix(File(slice, ios.libDir)), listOf("-F${slice.path}", "-framework", "Python"), slice)
    }
    LINUX_CROSS_TARGETS[canonicalTarget]?.let { linux ->
        val root = findTargetDir(packageDir, linux.installDirName) ?: missing(linux.installDirName)
        val include = headerDir(File(root, "include")) ?: missing(linux.installDirName)
        return TargetPython(canonicalTarget, include, extSuffix(File(root, "lib")), emptyList())
    }
    throw IllegalStateException("pypackpack installs no CPython for $canonicalTarget, so it cannot cross-build C extensions for it.")
}

/** `include/python3.Xt` (the free-threaded headers) under [include]. */
private fun headerDir(include: File): File? =
    include.listFiles { f -> f.isDirectory && Regex("""python3\.\d+t""").matches(f.name) && File(f, "Python.h").isFile }
        ?.minByOrNull { it.name }

private fun extSuffix(lib: File): String =
    readSysconfigValue(lib, "EXT_SUFFIX")?.takeIf { it.isNotBlank() }
        ?: throw IllegalStateException("The Python in $lib has no _sysconfigdata naming its EXT_SUFFIX.")
