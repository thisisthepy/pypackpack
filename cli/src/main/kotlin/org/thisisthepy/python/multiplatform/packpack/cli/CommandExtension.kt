package org.thisisthepy.python.multiplatform.packpack.cli

import com.github.ajalt.clikt.core.*
import com.github.ajalt.mordant.rendering.TextColors.*
import com.github.ajalt.mordant.terminal.Terminal
import org.thisisthepy.python.multiplatform.packpack.dependency.middleware.MiddlewareInterface

private const val DEFAULT_TERMINAL_WIDTH = 100

internal fun CliktCommand.configureCliTerminal() {
    configureContext {
        terminal = Terminal(width = terminalWidth())
    }
}

private fun terminalWidth(): Int =
    listOfNotNull(
        System.getenv("PYPACKPACK_TERMINAL_WIDTH"),
        System.getenv("COLUMNS"),
    ).firstNotNullOfOrNull { value ->
        value.toIntOrNull()?.takeIf { it >= 40 }
    } ?: DEFAULT_TERMINAL_WIDTH

internal fun CliktCommand.requireMiddleware(): MiddlewareInterface = currentContext.findObject<MiddlewareInterface>()!!

/**
 * Result of splitting a raw passthrough token stream (collected via
 * `override val treatUnknownOptionsAsArgs = true` plus an `argument().multiple()`) into the plain
 * positional values and the `--flag [value]` style options meant to be forwarded verbatim to the
 * underlying tool (`uv`), which already accepts an arbitrary `extraArgs` map and turns each entry
 * into `--key [value]` (see `UVBackend.appendOptions`).
 *
 * Assumption (documented here because the CLI-passthrough shape is not pinned down by
 * docs/SPEC.md beyond a list of example flag names): every flag every earlier draft of the spec
 * named for `add`/`remove`/`sync`/`tree` (`--dev`, `--editable`, `--no-sync`, `--upgrade`,
 * `--reinstall`, `--refresh`, `--frozen`, `--locked`, `--preview`, `--raw-sources`, `--quiet`,
 * `--verbose`) is boolean in the real `uv` CLI, so a token starting with `--` defaults to being a
 * bare flag (empty value). Only [VALUE_TAKING_PASSTHROUGH_FLAGS] consume the following token as
 * their value. Defaulting to "boolean" rather than "peek at the next token" avoids swallowing a
 * positional argument that happens to follow a boolean flag (e.g. `add requests --dev numpy` must
 * keep `numpy` as a dependency, not become the value of `--dev`).
 */
private val VALUE_TAKING_PASSTHROUGH_FLAGS =
    setOf(
        "extra-index-url",
        "index-url",
        "index-strategy",
        "python",
        "resolution",
    )

internal data class ParsedPassthroughArgs(
    val positionals: List<String>,
    val extraArgs: Map<String, String>,
)

internal fun parsePassthroughArgs(tokens: List<String>): ParsedPassthroughArgs {
    val positionals = mutableListOf<String>()
    val extraArgs = mutableMapOf<String, String>()
    var i = 0
    while (i < tokens.size) {
        val token = tokens[i]
        if (token.startsWith("--") && token.length > 2) {
            val key = token.removePrefix("--")
            if (key in VALUE_TAKING_PASSTHROUGH_FLAGS) {
                val next =
                    tokens.getOrNull(i + 1)
                        ?: throw IllegalArgumentException("Option --$key requires a value")
                extraArgs[key] = next
                i += 2
            } else {
                extraArgs[key] = ""
                i += 1
            }
        } else {
            positionals.add(token)
            i += 1
        }
    }
    return ParsedPassthroughArgs(positionals, extraArgs)
}

internal fun CliktCommand.runBooleanCommand(
    progressMessage: String,
    failureMessage: String,
    successMessage: String? = null,
    action: () -> Boolean,
) {
    val success = currentContext.terminal.runWithProgress(progressMessage) { action() }
    if (!success) {
        throw PrintMessage(failureMessage, statusCode = 1)
    }
    if (successMessage != null) {
        echo(successMessage)
    }
}

internal fun CliktCommand.runResultCommand(
    progressMessage: String,
    failurePrefix: String,
    onSuccess: (String) -> Unit = { echo(it) },
    action: () -> Result<String>,
) {
    val result = currentContext.terminal.runWithProgress(progressMessage) { action() }
    result
        .onSuccess(onSuccess)
        .onFailure {
            throw PrintMessage("$failurePrefix: ${it.message}", statusCode = 1)
        }
}

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
