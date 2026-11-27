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
    val dirName = targetPythonDirName(canonicalTarget)
        ?: throw IllegalStateException("pypackpack installs no CPython for $canonicalTarget, so it cannot cross-build C extensions for it.")
    val dir = findTargetDir(packageDir, dirName) ?: throw IllegalStateException(
        "No Python for $canonicalTarget under a '$dirName' directory above $packageDir. " +
            "Run `pypackpack python install 3.15t $canonicalTarget` first.",
    )
    return targetPythonIn(dir, canonicalTarget)
}

/**
 * [canonicalTarget]'s CPython in [installDir], as `installPython(version, target, installDir)` lays it out:
 * Android `prefix/{include,lib}`, iOS `Python.xcframework/<slice>`, Windows `include/` and `libs/python3XYt.lib`
 * (its `EXT_SUFFIX` is `.cp3XYt-win_<arch>.pyd`; the Windows build has no `_sysconfigdata`), and otherwise
 * (macOS, Linux) `include/python3.Xt` and `lib/python3.Xt/_sysconfigdata*.py`. toolchain's
 * `compileLevel = "native"` reads its runtime through this too.
 */
fun targetPythonIn(installDir: File, canonicalTarget: String): TargetPython {
    fun missing(what: String): Nothing =
        throw IllegalStateException("The CPython for $canonicalTarget in $installDir has no $what.")

    if (canonicalTarget in ANDROID_DIRS) {
        val prefix = File(installDir, "prefix").takeIf { it.isDirectory } ?: installDir
        val lib = File(prefix, "lib")
        val include = headerDir(File(prefix, "include")) ?: missing("include/python3.Xt/Python.h")
        val suffix = extSuffix(lib)
        val ldLibrary = readSysconfigValue(lib, "LDLIBRARY") ?: missing("_sysconfigdata naming its LDLIBRARY")
        return TargetPython(
            canonicalTarget,
            include,
            suffix,
            listOf("-L${lib.path}", "-l${ldLibrary.removePrefix("lib").removeSuffix(".so")}"),
            lib,
            ldLibrary,
        )
    }
    IOS_TARGETS[canonicalTarget]?.let { ios ->
        val slice = File(installDir, "Python.xcframework/${ios.slice}").takeIf { it.isDirectory } ?: missing("Python.xcframework/${ios.slice}")
        val headers = File(slice, "Python.framework/Headers").takeIf { File(it, "Python.h").isFile } ?: missing("Python.framework/Headers/Python.h")
        return TargetPython(canonicalTarget, headers, extSuffix(File(slice, ios.libDir)), listOf("-F${slice.path}", "-framework", "Python"), slice)
    }
    if (canonicalTarget.endsWith("-pc-windows-msvc")) {
        val home = installDir.walkTopDown().maxDepth(2).firstOrNull { it.isFile && it.name.equals("python.exe", ignoreCase = true) }?.parentFile
            ?: missing("python.exe")
        val include = File(home, "include").takeIf { File(it, "Python.h").isFile } ?: missing("include/Python.h")
        val lib = File(home, "libs").listFiles { f -> Regex("""python3\d+t\.lib""").matches(f.name) }?.singleOrNull()
            ?: missing("libs/python3XYt.lib")
        val digits = lib.name.removePrefix("python").removeSuffix("t.lib")
        val arch = when {
            canonicalTarget.startsWith("aarch64") -> "win_arm64"
            canonicalTarget.startsWith("i686") -> "win32"
            else -> "win_amd64"
        }
        return TargetPython(canonicalTarget, include, ".cp${digits}t-$arch.pyd", listOf("-L${lib.parentFile.path}", "-l${lib.nameWithoutExtension}"), lib.parentFile, lib.name)
    }
    val root = listOf(installDir, File(installDir, "python"), File(installDir, "install")).firstOrNull { headerDir(File(it, "include")) != null }
        ?: missing("include/python3.Xt/Python.h")
    return TargetPython(canonicalTarget, headerDir(File(root, "include"))!!, extSuffix(File(root, "lib")), emptyList())
}

/** `include/python3.Xt` (the free-threaded headers) under [include]. */
private fun headerDir(include: File): File? =
    include.listFiles { f -> f.isDirectory && Regex("""python3\.\d+t""").matches(f.name) && File(f, "Python.h").isFile }
        ?.minByOrNull { it.name }

private fun extSuffix(lib: File): String =
    readSysconfigValue(lib, "EXT_SUFFIX")?.takeIf { it.isNotBlank() }
        ?: throw IllegalStateException("The Python in $lib has no _sysconfigdata naming its EXT_SUFFIX.")

/** The directory `installPython` lays [canonicalTarget]'s CPython out in (`android_arm64`, ...); null for a target it has none for. */
fun targetPythonDirName(canonicalTarget: String): String? =
    ANDROID_DIRS[canonicalTarget]
        ?: IOS_TARGETS[canonicalTarget]?.installDirName
        ?: LINUX_CROSS_TARGETS[canonicalTarget]?.installDirName
        ?: "wasm32_emscripten".takeIf { canonicalTarget == "wasm32-emscripten" }

/** The nearest `<targetPythonDirName>` at or above [packageDir] that holds [canonicalTarget]'s CPython, if any. */
fun findTargetPythonDir(packageDir: File, canonicalTarget: String): File? =
    targetPythonDirName(canonicalTarget)?.let { findTargetDir(packageDir, it) }
