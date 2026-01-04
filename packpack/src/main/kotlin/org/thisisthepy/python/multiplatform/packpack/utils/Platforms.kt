package org.thisisthepy.python.multiplatform.packpack.utils

/**
 * Central registry for supported target platforms.
 *
 * Distinguishes between:
 * - **Target**: A specific build target triple (e.g., `x86_64-pc-windows-msvc`).
 * - **Platform Family**: The general OS family (e.g., `windows`, `linux`).
 */
object Platforms {
    /**
     * List of all supported targets and aliases for display purposes.
     * Used by `pypackpack target list`.
     */
    val SUPPORTED_TARGETS: List<String> =
        listOf(
            // Aliases
            "windows",
            "linux",
            "macos",
            // Windows
            "x86_64-pc-windows-msvc",
            "aarch64-pc-windows-msvc",
            "i686-pc-windows-msvc",
            // Linux
            "x86_64-unknown-linux-gnu",
            "aarch64-unknown-linux-gnu",
            "x86_64-unknown-linux-musl",
            "aarch64-unknown-linux-musl",
            "riscv64-unknown-linux",
            // macOS
            "x86_64-apple-darwin",
            "aarch64-apple-darwin",
            // Manylinux
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
            // Android
            "aarch64-linux-android",
            "x86_64-linux-android",
            // Wasm
            "wasm32-pyodide2024",
            // iOS
            "arm64-apple-ios",
            "arm64-apple-ios-simulator",
            "x86_64-apple-ios-simulator",
        )

    /** Canonical target triples. */
    private val VALID_TARGETS: Set<String> =
        SUPPORTED_TARGETS
            .filterNot { it == "windows" || it == "linux" || it == "macos" }
            .toSet()

    /** Mapping from aliases/legacy names to canonical target triples. */
    private val TARGET_ALIASES: Map<String, String> =
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
     * Normalize a user-provided target string to a canonical target triple.
     * Returns null when the target is not supported.
     */
    fun normalizeTarget(target: String): String? {
        val normalized = TARGET_ALIASES[target] ?: target
        return if (normalized in VALID_TARGETS) normalized else null
    }

    /**
     * Detect the host machine's target triple.
     */
    fun detectHostTarget(
        osName: String = System.getProperty("os.name"),
        osArch: String = System.getProperty("os.arch"),
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

        return normalizeTarget(rawTarget)
            ?: throw UnsupportedOperationException("Unsupported platform target: $rawTarget")
    }

    /**
     * Get the platform family directory name for a given target.
     * (Used for `package/src/<dir>` layout.)
     */
    fun getPlatformFamily(target: String): String {
        val canonical = normalizeTarget(target) ?: target
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
    
    /**
     * Sort platforms by SUPPORTED_TARGETS order.
     * 
     * Platforms are sorted according to their position in SUPPORTED_TARGETS.
     * Platforms not found in SUPPORTED_TARGETS are placed at the end.
     * 
     * @param platforms Collection of platform names to sort
     * @return Sorted list of platforms
     */
    fun sort(platforms: Collection<String>): List<String> {
        val orderMap = SUPPORTED_TARGETS
            .withIndex()
            .associate { it.value to it.index }
        
        return platforms.sortedBy { orderMap[it] ?: Int.MAX_VALUE }
    }
}
