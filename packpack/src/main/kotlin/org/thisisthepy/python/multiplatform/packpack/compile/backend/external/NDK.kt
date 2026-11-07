package org.thisisthepy.python.multiplatform.packpack.compile.backend.external

import org.thisisthepy.python.multiplatform.packpack.compile.extension.CompileProcessResult
import org.thisisthepy.python.multiplatform.packpack.compile.extension.runCompileProcess
import java.io.File

/**
 * Android NDK wrapper (`NDK`) for the native compile slot (`docs/design/compile-slot.md` §7, #20).
 *
 * Builds `aarch64-linux-android` extensions with `<ndk>/toolchains/llvm/prebuilt/<host>/bin/aarch64-linux-android<api>-clang`,
 * linking `libpython` (`-shared -fPIC -L<libDir> -l<libpython>`), because Bionic does not resolve
 * undefined symbols from the host process.
 */
open class NDK(
    private val locateNdk: () -> File? = { CargoEnvironment.locateNdk(System.getenv()) },
    private val versionProbe: (compilerBin: File) -> String? = ::defaultVersionProbe,
    val run: (command: List<String>, workingDir: File, env: Map<String, String>) -> CompileProcessResult = ::runCompileProcess,
) {
    fun ndkRoot(): File? = locateNdk()

    fun compilerBinary(
        ndkRoot: File,
        canonicalTarget: String,
        minSdk: Int?,
    ): File {
        val api = minSdk ?: ANDROID_API_LEVEL
        val bin = File(ndkRoot, "toolchains/llvm/prebuilt/${ndkHostTag()}/bin")
        val suffix = if (System.getProperty("os.name").lowercase().contains("win")) ".cmd" else ""
        return File(bin, "$canonicalTarget$api-clang$suffix")
    }

    fun stripBinary(ndkRoot: File): File? {
        val bin = File(ndkRoot, "toolchains/llvm/prebuilt/${ndkHostTag()}/bin")
        val exe = if (System.getProperty("os.name").lowercase().contains("win")) "llvm-strip.exe" else "llvm-strip"
        return File(bin, exe).takeIf { it.isFile }
    }

    fun versionOf(compilerBin: File): String =
        versionProbe(compilerBin)?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
            ?: compilerBin.name

    fun compileCommand(
        compilerBin: File,
        buildType: String,
        workingDir: File,
        includeDir: File,
        includeDirs: List<File>,
        defines: Map<String, String?>,
        cFlags: List<String>,
        source: File,
        objectFile: File,
        depFile: File,
    ): List<String> =
        buildList {
            add(compilerBin.path)
            add("-c")
            if (buildType == "release") {
                add("-O2")
                add("-DNDEBUG")
            } else {
                add("-O0")
                add("-g")
            }
            add("-ffp-contract=off")
            add("-fno-fast-math")
            add("-fPIC")
            add("-ffile-prefix-map=${workingDir.canonicalFile.path}=.")
            add("-MMD")
            add("-MF")
            add(depFile.path)
            add("-I${includeDir.canonicalFile.path}")
            includeDirs.forEach { dir -> add("-I${dir.canonicalFile.path}") }
            defines.forEach { (k, v) -> add(if (v == null) "-D$k" else "-D$k=$v") }
            addAll(cFlags)
            add(source.path)
            add("-o")
            add(objectFile.path)
        }

    fun linkCommand(
        compilerBin: File,
        objects: List<File>,
        libDir: File,
        libraryName: String,
        linkFlags: List<String>,
        outputFile: File,
    ): List<String> {
        val linkName =
            when {
                libraryName.startsWith("lib") && libraryName.endsWith(".so") ->
                    "-l${libraryName.removePrefix("lib").removeSuffix(".so")}"
                libraryName.startsWith("-l") -> libraryName
                else -> File(libDir.canonicalFile, libraryName).path
            }
        return buildList {
            add(compilerBin.path)
            add("-shared")
            add("-fPIC")
            objects.forEach { obj -> add(obj.path) }
            add("-L${libDir.canonicalFile.path}")
            add(linkName)
            addAll(linkFlags)
            add("-o")
            add(outputFile.path)
        }
    }

    companion object {
        private fun defaultVersionProbe(compilerBin: File): String? {
            if (!compilerBin.isFile) return null
            val res = runCompileProcess(listOf(compilerBin.path, "--version"), File("."), emptyMap())
            return res.output.takeIf { res.exitCode == 0 }
        }
    }
}
