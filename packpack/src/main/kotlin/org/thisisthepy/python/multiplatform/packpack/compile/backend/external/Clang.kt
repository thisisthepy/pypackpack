package org.thisisthepy.python.multiplatform.packpack.compile.backend.external

import org.thisisthepy.python.multiplatform.packpack.compile.extension.CompileProcessResult
import org.thisisthepy.python.multiplatform.packpack.compile.extension.runCompileProcess
import java.io.File

/**
 * Desktop C compiler adapter (`Clang`) for the native compile slot (`docs/design/compile-slot.md` §7, #20):
 * - macOS (`aarch64-apple-darwin`, `x86_64-apple-darwin`): `xcrun clang` with `-arch arm64` or `-arch x86_64`,
 *   linked with `-bundle -undefined dynamic_lookup` and `ZERO_AR_DATE=1`.
 * - Linux at host arch: `clang` or `cc`, linked with `-shared -fPIC`. Linux targets of another
 *   architecture or libc (#155): `zig cc -target <zig triple>` (ziglang through `uv tool run`, as the Cargo
 *   backend's `maturin --zig`), glibc 2.17 for gnu (manylinux_2_17), 2.27 for riscv64.
 * - Windows, on a machine of the target's architecture only (no Windows cross toolchain, owner decision
 *   2026-11-19): LLVM `clang --target=<triple>`, linked `-shared` against `python3XXt.lib` into a `.pyd`.
 * - iOS (`XCode.kt`'s role, on a macOS host): `xcrun --sdk iphoneos|iphonesimulator clang -target
 *   <arch>-apple-ios13.0[-simulator]`, linked `-dynamiclib -F <libDir> -framework Python` as CPython's own
 *   iOS extensions are.
 */
open class Clang(
    private val locateCompiler: (family: String) -> List<String>? = ::defaultLocateCompiler,
    private val locateTargetCompiler: (canonicalTarget: String) -> List<String>? = ::defaultLocateTargetCompiler,
    private val locateStrip: (family: String) -> List<String>? = ::defaultLocateStrip,
    private val versionProbe: (compilerCmd: List<String>) -> String? = ::defaultVersionProbe,
    val run: (command: List<String>, workingDir: File, env: Map<String, String>) -> CompileProcessResult = ::runCompileProcess,
) {
    /** Returns the base compiler command for [family] (`"macos"` or `"linux"`), or `null` when missing. */
    fun compilerFor(family: String): List<String>? = locateCompiler(family)

    /**
     * The compiler for a target that is not the host's own macOS/Linux build: iOS (`xcrun --sdk ... clang`),
     * Windows (`clang`), or a Linux cross target (`zig cc -target ...`); `null` when missing.
     */
    fun compilerForTarget(canonicalTarget: String): List<String>? = locateTargetCompiler(canonicalTarget)

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
            addAll(targetFlags(canonicalTarget, family))
            if (buildType == "release") {
                add("-O2")
                add("-DNDEBUG")
            } else {
                add("-O0")
                add("-g")
            }
            add("-ffp-contract=off")
            add("-fno-fast-math")
            if (family != "windows") add("-fPIC")
            // Windows' pyconfig.h leaves the free-threaded switch to the build backend (#155).
            if (family == "windows") add("-DPy_GIL_DISABLED=1")
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
        libDir: File? = null,
        libraryName: String? = null,
    ): List<String> =
        buildList {
            addAll(compilerCmd)
            addAll(targetFlags(canonicalTarget, family))
            when (family) {
                "macos" -> {
                    add("-bundle")
                    add("-undefined")
                    add("dynamic_lookup")
                }
                "ios" -> {
                    add("-dynamiclib")
                    add("-F${requireNotNull(libDir) { "iOS links Python.framework: libDir is required." }.path}")
                    add("-framework")
                    add("Python")
                }
                "windows" -> {
                    add("-shared")
                    add("-L${requireNotNull(libDir) { "Windows links python3XXt.lib: libDir is required." }.path}")
                    add("-l${requireNotNull(libraryName) { "Windows links python3XXt.lib: libraryName is required." }.substringBeforeLast(".lib")}")
                }
                else -> {
                    add("-shared")
                    add("-fPIC")
                }
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
        if (family == "windows") return null
        val strip = locateStrip(if (family == "ios") "macos" else family) ?: return null
        return buildList {
            addAll(strip)
            if (family == "macos" || family == "ios") {
                add("-x")
            } else {
                add("--strip-unneeded")
            }
            add(outputFile.path)
        }
    }

    /** `-arch` on macOS, `-target` for iOS, `--target` for Windows; zig cross targets carry theirs in the command. */
    private fun targetFlags(canonicalTarget: String, family: String): List<String> =
        when (family) {
            "macos" -> listOf("-arch", if (canonicalTarget.startsWith("aarch64")) "arm64" else "x86_64")
            "ios" -> listOf("-target", iosClangTarget(canonicalTarget))
            "windows" -> listOf("--target=$canonicalTarget")
            else -> emptyList()
        }

    companion object {
        /** The iOS deployment target the slot builds for, as the Cargo backend does. */
        const val IOS_MIN_VERSION = IOS_DEPLOYMENT_TARGET

        /** `zig cc -target` for each Linux cross target (glibc floor 2.17, manylinux_2_17; riscv64 starts at 2.27). */
        val ZIG_LINUX_TARGETS: Map<String, String> = mapOf(
            "x86_64-unknown-linux-gnu" to "x86_64-linux-gnu.2.17",
            "aarch64-unknown-linux-gnu" to "aarch64-linux-gnu.2.17",
            "x86_64-unknown-linux-musl" to "x86_64-linux-musl",
            "aarch64-unknown-linux-musl" to "aarch64-linux-musl",
            "riscv64-unknown-linux" to "riscv64-linux-gnu.2.27",
        )

        /** clang's `-target` for an iOS canonical target. */
        fun iosClangTarget(canonicalTarget: String): String =
            when (canonicalTarget) {
                "arm64-apple-ios" -> "arm64-apple-ios$IOS_MIN_VERSION"
                "arm64-apple-ios-simulator" -> "arm64-apple-ios$IOS_MIN_VERSION-simulator"
                "x86_64-apple-ios-simulator" -> "x86_64-apple-ios$IOS_MIN_VERSION-simulator"
                else -> throw IllegalArgumentException("Not an iOS target: $canonicalTarget")
            }

        private fun defaultLocateTargetCompiler(canonicalTarget: String): List<String>? =
            when {
                canonicalTarget.endsWith("-apple-ios") ->
                    listOf("xcrun", "--sdk", "iphoneos", "clang").takeIf { defaultVersionProbe(it) != null }
                canonicalTarget.endsWith("-apple-ios-simulator") ->
                    listOf("xcrun", "--sdk", "iphonesimulator", "clang").takeIf { defaultVersionProbe(it) != null }
                canonicalTarget.endsWith("-pc-windows-msvc") ->
                    listOf("clang").takeIf { defaultVersionProbe(it) != null }
                canonicalTarget in ZIG_LINUX_TARGETS ->
                    (listOf("uv", "tool", "run", "--from", "ziglang", "python", "-m", "ziglang", "cc", "-target", ZIG_LINUX_TARGETS.getValue(canonicalTarget)))
                        .takeIf { defaultVersionProbe(it) != null }
                else -> null
            }

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
