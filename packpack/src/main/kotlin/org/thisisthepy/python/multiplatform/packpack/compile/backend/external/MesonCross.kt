package org.thisisthepy.python.multiplatform.packpack.compile.backend.external

import java.io.File

/**
 * A Meson cross build for a target that is not the host (docs/SPEC.md *Package build*, #155): the target's
 * CPython ([TargetPython]) and the compilers the native slot uses for it. Meson cannot introspect a target
 * interpreter it cannot run, so the generated `meson.build` names the headers and `EXT_SUFFIX` itself
 * ([Meson.makeMesonBuild] with a cross) instead of using `import('python')`.
 */
data class MesonCross(
    val target: TargetPython,
    val c: List<String>,
    val cpp: List<String>,
    val ar: List<String>,
    val system: String,
    val subsystem: String?,
    val cpuFamily: String,
) {
    /** The Meson cross file (`--cross-file`). */
    fun crossFileText(): String =
        buildString {
            appendLine("[binaries]")
            appendLine("c = ${array(c)}")
            appendLine("cpp = ${array(cpp)}")
            appendLine("ar = ${array(ar)}")
            appendLine()
            appendLine("[host_machine]")
            appendLine("system = ${quote(system)}")
            subsystem?.let { appendLine("subsystem = ${quote(it)}") }
            appendLine("cpu_family = ${quote(cpuFamily)}")
            appendLine("cpu = ${quote(cpuFamily)}")
            appendLine("endian = 'little'")
        }

    private fun array(items: List<String>) = items.joinToString(", ", "[", "]") { quote(it) }

    private fun quote(text: String) = "'" + text.replace("\\", "\\\\").replace("'", "\\'") + "'"

    companion object {
        /**
         * The cross build for [canonicalTarget], or `null` when it is the host's own (a native Meson build).
         * Linux cross targets use `zig cc` (as the native slot does), Android the NDK's clang for API
         * [ANDROID_API_LEVEL], iOS `xcrun --sdk ... clang`. Windows has no cross toolchain (owner decision
         * 2026-11-19), so a Windows target that is not the host is refused.
         */
        fun forTarget(
            packageDir: File,
            canonicalTarget: String,
            hostTarget: String,
            ndk: NDK = NDK(),
        ): MesonCross? {
            if (canonicalTarget == hostTarget) return null
            require(!canonicalTarget.endsWith("-pc-windows-msvc")) {
                "Windows extensions build only on a $canonicalTarget machine (no Windows cross toolchain, owner decision 2026-11-19)."
            }
            val python = locateTargetPython(packageDir, canonicalTarget)
            val cpu = if (canonicalTarget.startsWith("x86_64")) "x86_64" else "aarch64"
            return when {
                canonicalTarget in ANDROID_DIRS -> {
                    val root = ndk.ndkRoot() ?: throw IllegalStateException(
                        "No Android NDK found for $canonicalTarget. Set ANDROID_NDK_HOME, or install one under ANDROID_HOME's ndk directory.",
                    )
                    val cc = ndk.compilerBinary(root, canonicalTarget, null)
                    val bin = cc.parentFile
                    MesonCross(python, listOf(cc.path), listOf(cc.path.replace("-clang", "-clang++")), listOf(File(bin, "llvm-ar").path), "android", null, cpu)
                }
                canonicalTarget in IOS_TARGETS -> {
                    val sdk = if (canonicalTarget.endsWith("-simulator")) "iphonesimulator" else "iphoneos"
                    val flags = listOf("-target", Clang.iosClangTarget(canonicalTarget))
                    MesonCross(
                        python,
                        listOf("xcrun", "--sdk", sdk, "clang") + flags,
                        listOf("xcrun", "--sdk", sdk, "clang++") + flags,
                        listOf("xcrun", "--sdk", sdk, "ar"),
                        "darwin",
                        if (sdk == "iphoneos") "ios" else "ios-simulator",
                        cpu,
                    )
                }
                else -> {
                    val zigTarget = Clang.ZIG_LINUX_TARGETS[LINUX_CROSS_TARGETS[canonicalTarget]?.rustTriple ?: canonicalTarget]
                        ?: throw IllegalStateException("No cross compiler for $canonicalTarget.")
                    val zig = listOf("uv", "tool", "run", "--from", "ziglang", "python", "-m", "ziglang")
                    MesonCross(
                        python,
                        zig + listOf("cc", "-target", zigTarget),
                        zig + listOf("c++", "-target", zigTarget),
                        zig + "ar",
                        "linux",
                        null,
                        if (zigTarget.startsWith("riscv64")) "riscv64" else cpu,
                    )
                }
            }
        }
    }
}
