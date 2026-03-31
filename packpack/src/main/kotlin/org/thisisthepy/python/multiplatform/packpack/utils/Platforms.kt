package org.thisisthepy.python.multiplatform.packpack.utils

/**
 * Central registry for supported target platforms.
 *
 * Distinguishes between:
 * - **Target**: A specific build target triple (e.g., `x86_64-pc-windows-msvc`).
 * - **Platform Family**: The general OS family (e.g., `windows`, `linux`).
 */
object Platforms {
    data class TargetDescriptor(
        val canonicalTarget: String,
        val family: String,
        val markerSystem: String,
        val markerMachine: String,
    )

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
    fun getPlatformFamily(target: String): String = describeTarget(target).family

    /**
     * Describe a target in terms shared across platform layout and dependency markers.
     */
    fun describeTarget(target: String): TargetDescriptor {
        val canonical = normalizeTarget(target) ?: target
        return when {
            canonical.contains("windows") ->
                TargetDescriptor(
                    canonicalTarget = canonical,
                    family = "windows",
                    markerSystem = "Windows",
                    markerMachine = markerMachine(canonical),
                )

            canonical.contains("android") ->
                TargetDescriptor(
                    canonicalTarget = canonical,
                    family = "android",
                    markerSystem = "Android",
                    markerMachine = markerMachine(canonical),
                )

            canonical.contains("apple-ios") || canonical.contains("ios") ->
                TargetDescriptor(
                    canonicalTarget = canonical,
                    family = "ios",
                    markerSystem = "iOS",
                    markerMachine = markerMachine(canonical),
                )

            canonical.startsWith("wasm") || canonical.contains("pyodide") || canonical.contains("emscripten") ->
                TargetDescriptor(
                    canonicalTarget = canonical,
                    family = "wasm",
                    markerSystem = "Emscripten",
                    markerMachine = markerMachine(canonical),
                )

            canonical.contains("apple-darwin") || canonical.contains("darwin") ->
                TargetDescriptor(
                    canonicalTarget = canonical,
                    family = "macos",
                    markerSystem = "Darwin",
                    markerMachine = markerMachine(canonical),
                )

            canonical.contains("manylinux") || canonical.contains("linux") ->
                TargetDescriptor(
                    canonicalTarget = canonical,
                    family = "linux",
                    markerSystem = "Linux",
                    markerMachine = markerMachine(canonical),
                )

            else -> throw IllegalArgumentException("Unsupported target metadata: $target")
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

    fun suggestTargets(
        input: String,
        limit: Int = 3,
    ): List<String> {
        val query = input.trim().lowercase()
        if (query.isEmpty()) {
            return emptyList()
        }

        val familyMatches =
            sort(
                SUPPORTED_TARGETS.filter { target ->
                    target.lowercase().contains(query) || getPlatformFamily(target).lowercase() == query
                },
            )
        if (familyMatches.isNotEmpty()) {
            return familyMatches.take(limit)
        }

        return SUPPORTED_TARGETS
            .asSequence()
            .map { it to levenshteinDistance(query, it.lowercase()) }
            .filter { it.second <= maxOf(2, query.length / 2) }
            .sortedBy { it.second }
            .take(limit)
            .map { it.first }
            .toList()
    }

    fun unsupportedTargetMessage(
        target: String,
        label: String = "target",
    ): String {
        val suggestions = suggestTargets(target)
        val suggestionText =
            if (suggestions.isEmpty()) {
                ""
            } else {
                " Did you mean: ${suggestions.joinToString(", ")}?"
            }

        return "Unsupported $label: $target.$suggestionText Must be one of $SUPPORTED_TARGETS"
    }

    fun normalizeTargetsOrThrow(
        targets: List<String>,
        label: String = "target",
    ): List<String> =
        targets
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { target ->
                normalizeTarget(target)
                    ?: throw IllegalArgumentException(unsupportedTargetMessage(target, label))
            }
            .distinct()

    fun normalizeTargetsOrThrow(
        targets: List<String>?,
        defaultTargets: List<String>,
        label: String = "target",
    ): List<String> {
        val source = if (targets.isNullOrEmpty()) defaultTargets else targets
        return normalizeTargetsOrThrow(source, label)
    }

    private fun levenshteinDistance(
        s1: String,
        s2: String,
    ): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }

        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j

        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                dp[i][j] =
                    if (s1[i - 1] == s2[j - 1]) {
                        dp[i - 1][j - 1]
                    } else {
                        1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
                    }
            }
        }

        return dp[s1.length][s2.length]
    }

    private fun markerMachine(target: String): String = target.substringBefore('-').replace("aarch64", "arm64")
}
