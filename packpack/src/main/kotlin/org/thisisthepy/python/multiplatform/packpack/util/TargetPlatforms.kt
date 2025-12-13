package org.thisisthepy.python.multiplatform.packpack.util

/**
 * Central registry for supported target platforms.
 *
 * Notes:
 * - `DISPLAY_TARGETS` defines the order shown by `pypackpack target list`.
 * - `normalizeOrNull()` maps aliases/legacy values to canonical target strings.
 */
object TargetPlatforms {
    /** Targets shown to users (in order). */
    val DISPLAY_TARGETS: List<String> =
        listOf(
            "windows",
            "linux",
            "macos",
            "x86_64-pc-windows-msvc",
            "aarch64-pc-windows-msvc",
            "i686-pc-windows-msvc",
            "x86_64-unknown-linux-gnu",
            "aarch64-apple-darwin",
            "x86_64-apple-darwin",
            "aarch64-unknown-linux-gnu",
            "aarch64-unknown-linux-musl",
            "x86_64-unknown-linux-musl",
            "riscv64-unknown-linux",
            "x86_64-manylinux2014",
            "x86_64-manylinux_2_17",
            "x86_64-manylinux_2_28",
            "x86_64-manylinux_2_31",
            "x86_64-manylinux_2_32",
            "x86_64-manylinux_2_33",
            "x86_64-manylinux_2_34",
            "x86_64-manylinux_2_35",
            "x86_64-manylinux_2_36",
            "x86_64-manylinux_2_37",
            "x86_64-manylinux_2_38",
            "x86_64-manylinux_2_39",
            "x86_64-manylinux_2_40",
            "aarch64-manylinux2014",
            "aarch64-manylinux_2_17",
            "aarch64-manylinux_2_28",
            "aarch64-manylinux_2_31",
            "aarch64-manylinux_2_32",
            "aarch64-manylinux_2_33",
            "aarch64-manylinux_2_34",
            "aarch64-manylinux_2_35",
            "aarch64-manylinux_2_36",
            "aarch64-manylinux_2_37",
            "aarch64-manylinux_2_38",
            "aarch64-manylinux_2_39",
            "aarch64-manylinux_2_40",
            "aarch64-linux-android",
            "x86_64-linux-android",
            "wasm32-pyodide2024",
            "arm64-apple-ios",
            "arm64-apple-ios-simulator",
            "x86_64-apple-ios-simulator",
        )

    /** Canonical target values we actually store/use internally. */
    private val CANONICAL_TARGETS: Set<String> =
        DISPLAY_TARGETS
            .filterNot { it == "windows" || it == "linux" || it == "macos" }
            .toSet()

    /** Backward-compatible synonyms; normalized to canonical entries. */
    private val ALIASES_TO_CANONICAL: Map<String, String> =
        mapOf(
            // Requested human-friendly aliases
            "windows" to "x86_64-pc-windows-msvc",
            "linux" to "x86_64-unknown-linux-gnu",
            "macos" to "aarch64-apple-darwin",
            // Legacy short platform strings used by older versions
            "windows_amd64" to "x86_64-pc-windows-msvc",
            "windows_arm64" to "aarch64-pc-windows-msvc",
            "macos_arm64" to "aarch64-apple-darwin",
            "macos_x86_64" to "x86_64-apple-darwin",
            "linux_amd64" to "x86_64-unknown-linux-gnu",
            "linux_arm64" to "aarch64-unknown-linux-gnu",
            // Legacy android strings
            "android_21_arm64" to "aarch64-linux-android",
            "android_21_x86_64" to "x86_64-linux-android",
            "android_24_arm64" to "aarch64-linux-android",
            "android_24_x86_64" to "x86_64-linux-android",
        )

    /**
     * Normalize a user-provided/legacy target to a canonical target string.
     * Returns null when the target is not supported.
     */
    fun normalizeOrNull(target: String): String? {
        val normalized = ALIASES_TO_CANONICAL[target] ?: target
        return if (normalized in CANONICAL_TARGETS) normalized else null
    }

    /**
     * Detect host target (canonical) from OS name and architecture.
     */
    fun detectHostTarget(
        osName: String,
        osArch: String,
    ): String {
        val os = osName.lowercase()
        val arch = osArch.lowercase()

        val isX86_64 = arch.contains("amd64") || arch.contains("x86_64")
        val isArm64 = arch.contains("aarch64") || arch.contains("arm64")
        val isX86_32 = (arch.contains("86") && !arch.contains("64")) || arch.contains("i386") || arch.contains("i686")

        val rawTarget =
            when {
                os.contains("win") -> {
                    when {
                        isArm64 -> "aarch64-pc-windows-msvc"
                        isX86_32 -> "i686-pc-windows-msvc"
                        isX86_64 -> "x86_64-pc-windows-msvc"
                        else -> throw UnsupportedOperationException("Unsupported architecture: $osArch")
                    }
                }

                os.contains("mac") || os.contains("darwin") -> {
                    when {
                        isArm64 -> "aarch64-apple-darwin"
                        isX86_64 -> "x86_64-apple-darwin"
                        else -> throw UnsupportedOperationException("Unsupported architecture: $osArch")
                    }
                }

                os.contains("linux") -> {
                    when {
                        isArm64 -> "aarch64-unknown-linux-gnu"
                        isX86_64 -> "x86_64-unknown-linux-gnu"
                        else -> throw UnsupportedOperationException("Unsupported architecture: $osArch")
                    }
                }

                else -> {
                    throw UnsupportedOperationException("Unsupported OS: $osName")
                }
            }

        return normalizeOrNull(rawTarget)
            ?: throw UnsupportedOperationException("Unsupported platform target: $rawTarget")
    }

    /** Detect host target (canonical) from current JVM system properties. */
    fun detectHostTarget(): String =
        detectHostTarget(
            osName = System.getProperty("os.name"),
            osArch = System.getProperty("os.arch"),
        )

    /**
     * Convert a canonical target identifier to source directory name.
     * (Used for `package/src/<dir>` layout.)
     */
    fun sourceDirNameForTarget(target: String): String {
        val canonical = normalizeOrNull(target) ?: target
        return when {
            canonical.contains("windows") -> "windows"
            canonical.contains("apple-darwin") || canonical.contains("darwin") -> "macos"
            canonical.contains("manylinux") || canonical.contains("linux") -> "linux"
            canonical.contains("android") -> "android"
            canonical.startsWith("wasm") || canonical.contains("pyodide") -> "wasm"
            canonical.contains("apple-ios") || canonical.contains("ios") -> "ios"
            else -> canonical.split('-', '_').firstOrNull() ?: canonical
        }
    }
}
