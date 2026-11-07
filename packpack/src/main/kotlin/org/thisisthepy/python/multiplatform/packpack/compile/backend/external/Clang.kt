package org.thisisthepy.python.multiplatform.packpack.compile.backend.external

import org.thisisthepy.python.multiplatform.packpack.compile.extension.CompileProcessResult
import org.thisisthepy.python.multiplatform.packpack.compile.extension.runCompileProcess
import java.io.File

/**
 * Desktop C compiler adapter (`Clang`) for the native compile slot (`docs/design/compile-slot.md` §7, #20):
 * - macOS (`aarch64-apple-darwin`, `x86_64-apple-darwin`): `xcrun clang` with `-arch arm64` or `-arch x86_64`,
 *   linked with `-bundle -undefined dynamic_lookup` and `ZERO_AR_DATE=1`.
 * - Linux (`x86_64-unknown-linux-gnu`, `aarch64-unknown-linux-gnu` at host arch): `clang` or `cc`,
 *   linked with `-shared -fPIC`.
 */
open class Clang(
    private val locateCompiler: (family: String) -> List<String>? = ::defaultLocateCompiler,
    private val locateStrip: (family: String) -> List<String>? = ::defaultLocateStrip,
    private val versionProbe: (compilerCmd: List<String>) -> String? = ::defaultVersionProbe,
    val run: (command: List<String>, workingDir: File, env: Map<String, String>) -> CompileProcessResult = ::runCompileProcess,
) {
    /** Returns the base compiler command for [family] (`"macos"` or `"linux"`), or `null` when missing. */
    fun compilerFor(family: String): List<String>? = locateCompiler(family)

    /** Returns the compiler version string (first non-blank line of `--version`). */
    fun versionOf(compilerCmd: List<String>): String =
        versionProbe(compilerCmd)?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
            ?: compilerCmd.joinToString(" ")

    /** Builds the `-c` compile command for one translation unit. */
    fun compileCommand(
        compilerCmd: List<String>,
        canonicalTarget: String,
        family: String,
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
            addAll(compilerCmd)
            add("-c")
            if (family == "macos") {
                add("-arch")
                add(if (canonicalTarget.startsWith("aarch64")) "arm64" else "x86_64")
            }
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

    /** Builds the link command that turns [objects] into [outputFile]. */
    fun linkCommand(
        compilerCmd: List<String>,
        canonicalTarget: String,
        family: String,
        objects: List<File>,
        linkFlags: List<String>,
        outputFile: File,
    ): List<String> =
        buildList {
            addAll(compilerCmd)
            if (family == "macos") {
                add("-arch")
                add(if (canonicalTarget.startsWith("aarch64")) "arm64" else "x86_64")
                add("-bundle")
                add("-undefined")
                add("dynamic_lookup")
            } else {
                add("-shared")
                add("-fPIC")
            }
            objects.forEach { obj -> add(obj.path) }
            addAll(linkFlags)
            add("-o")
            add(outputFile.path)
        }

    /** Builds the strip command for `release` builds, or `null` if no strip tool is available. */
    fun stripCommand(
        family: String,
        outputFile: File,
    ): List<String>? {
        val strip = locateStrip(family) ?: return null
        return buildList {
            addAll(strip)
            if (family == "macos") {
                add("-x")
            } else {
                add("--strip-unneeded")
            }
            add(outputFile.path)
        }
    }

    companion object {
        private fun defaultLocateCompiler(family: String): List<String>? =
            if (family == "macos") {
                listOf("xcrun", "clang").takeIf { defaultVersionProbe(it) != null }
                    ?: listOf("clang").takeIf { defaultVersionProbe(it) != null }
            } else {
                listOf("clang").takeIf { defaultVersionProbe(it) != null }
                    ?: listOf("cc").takeIf { defaultVersionProbe(it) != null }
            }

        private fun defaultLocateStrip(family: String): List<String>? =
            if (family == "macos") {
                listOf("xcrun", "strip").takeIf { runs(it + "-V") || File("/usr/bin/xcrun").isFile }
                    ?: listOf("strip").takeIf { File("/usr/bin/strip").isFile }
            } else {
                listOf("strip").takeIf { defaultVersionProbe(it) != null }
            }

        private fun runs(cmd: List<String>): Boolean =
            runCompileProcess(cmd, File("."), emptyMap()).exitCode == 0

        private fun defaultVersionProbe(cmd: List<String>): String? {
            val res = runCompileProcess(cmd + "--version", File("."), emptyMap())
            return res.output.takeIf { res.exitCode == 0 }
        }
    }
}
