package org.thisisthepy.python.multiplatform.packpack.cli

import com.github.ajalt.mordant.rendering.TextColors.*
import com.github.ajalt.mordant.terminal.Terminal

/**
 * Validate Python version format
 */
fun validatePythonVersion(version: String): Boolean {
    val pythonVersionRegex = Regex("^\\d+\\.\\d+(\\.\\d+)?$")
    return pythonVersionRegex.matches(version)
}

/**
 * Validate package name format
 */
fun validatePackageName(name: String): Boolean {
    // Python package naming conventions
    val packageNameRegex = Regex("^[a-zA-Z][a-zA-Z0-9_]*$")
    return packageNameRegex.matches(name) && !name.startsWith("_")
}

/**
 * Find similar commands using simple string distance
 */
fun findSimilarCommands(
    input: String,
    commands: List<String>,
): List<String> =
    commands
        .asSequence()
        .map { it to levenshteinDistance(input.lowercase(), it.lowercase()) }
        .filter { it.second <= 2 } // Only suggest if distance is 2 or less
        .sortedBy { it.second }
        .take(3) // Limit to 3 suggestions
        .map { it.first }
        .toList()

/**
 * Calculate Levenshtein distance between two strings
 */
fun levenshteinDistance(
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

/**
 * Run a block with a simple progress indicator
 */
fun <T> Terminal.runWithProgress(
    message: String,
    block: () -> T,
): T {
    // Simple progress indication
    println(gray("$message..."))
    try {
        val result = block()
        // We assume the block returns a Boolean or similar to indicate success if needed,
        // but here we just handle exceptions.
        // If the result is a Boolean and false, the caller should handle the error message.
        return result
    } catch (e: Exception) {
        println(red("Failed: ${e.message}"))
        throw e
    }
}
