package org.thisisthepy.python.multiplatform.packpack.dependency.frontend

import org.thisisthepy.python.multiplatform.packpack.utils.*
import org.thisisthepy.python.multiplatform.packpack.compile.frontend.*
import org.thisisthepy.python.multiplatform.packpack.deploy.frontend.*

import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.arguments.*
import com.github.ajalt.clikt.parameters.options.*

abstract class BaseDependencyCommand(
    name: String,
) : CliktCommand(name = name)

class AddCommand : BaseDependencyCommand(name = "add") {
    override fun help(context: Context) =
        """
        Add dependencies to the development environment.

        Dependencies can be specified with version constraints (e.g., requests>=2.25.1).
        Any `--flag [value]` not recognized above (e.g. --dev, --editable, --upgrade) is passed
        through verbatim to the underlying `uv add` call.
        """.trimIndent()

    // Unrecognized `--flag [value]` tokens are routed into rawArgs instead of erroring, so they
    // can be forwarded to the underlying `uv` call. See parsePassthroughArgs' doc comment.
    override val treatUnknownOptionsAsArgs: Boolean = true

    val rawArgs by argument(help = "Dependencies to add, plus any passthrough options for `uv add`").multiple(required = true)

    override fun run() {
        val middleware = requireMiddleware()
        val (dependencies, extraArgs) = parsePassthroughArgs(rawArgs)
        if (dependencies.isEmpty()) {
            throw PrintMessage("No dependencies specified", statusCode = 1)
        }
        runBooleanCommand(
            progressMessage = "Adding dependencies: ${dependencies.joinToString(", ")}...",
            failureMessage = "Failed to add dependencies",
            successMessage = "Successfully added dependencies: ${dependencies.joinToString(", ")}",
        ) {
            middleware.addDependencies(null, dependencies, null, extraArgs.ifEmpty { null })
        }
    }
}

class RemoveCommand : BaseDependencyCommand(name = "remove") {
    override fun help(context: Context) = "Remove dependencies from the development environment."

    // Unrecognized `--flag [value]` tokens are routed into rawArgs instead of erroring, so they
    // can be forwarded to the underlying `uv` call. See parsePassthroughArgs' doc comment.
    override val treatUnknownOptionsAsArgs: Boolean = true

    val rawArgs by argument(help = "Dependencies to remove, plus any passthrough options for `uv remove`").multiple(required = true)

    override fun run() {
        val middleware = requireMiddleware()
        val (dependencies, extraArgs) = parsePassthroughArgs(rawArgs)
        if (dependencies.isEmpty()) {
            throw PrintMessage("No dependencies specified", statusCode = 1)
        }
        runBooleanCommand(
            progressMessage = "Removing dependencies: ${dependencies.joinToString(", ")}...",
            failureMessage = "Failed to remove dependencies",
            successMessage = "Successfully removed dependencies: ${dependencies.joinToString(", ")}",
        ) {
            middleware.removeDependencies(null, dependencies, null, extraArgs.ifEmpty { null })
        }
    }
}

class SyncCommand : BaseDependencyCommand(name = "sync") {
    override fun help(context: Context) = "Synchronize dependencies based on pyproject.toml."

    // Unrecognized `--flag [value]` tokens are routed into rawArgs instead of erroring, so they
    // can be forwarded to the underlying `uv` call. See parsePassthroughArgs' doc comment.
    override val treatUnknownOptionsAsArgs: Boolean = true

    val rawArgs by argument(help = "Passthrough options for `uv sync` (e.g. --frozen, --locked)").multiple()

    override fun run() {
        val middleware = requireMiddleware()
        val (positionals, extraArgs) = parsePassthroughArgs(rawArgs)
        if (positionals.isNotEmpty()) {
            throw PrintMessage("Unexpected argument(s): ${positionals.joinToString(", ")}", statusCode = 1)
        }
        runBooleanCommand(
            progressMessage = "Synchronizing dependencies...",
            failureMessage = "Failed to synchronize dependencies",
            successMessage = "Successfully synchronized dependencies",
        ) {
            middleware.syncDependencies(null, null, extraArgs.ifEmpty { null })
        }
    }
}

class TreeCommand : BaseDependencyCommand(name = "tree") {
    override fun help(context: Context) =
        """
        Show the dependency tree for the development environment.

        Any passthrough option for `uv tree` (e.g. --quiet) must be given before --target: since
        --target greedily consumes space-separated values, a passthrough flag placed after it
        would otherwise be swallowed as another target name instead of being recognized as a flag.
        """.trimIndent()

    // Unrecognized `--flag [value]` tokens are routed into rawArgs instead of erroring, so they
    // can be forwarded to the underlying `uv` call. See parsePassthroughArgs' doc comment.
    override val treatUnknownOptionsAsArgs: Boolean = true

    val targets by option("--target", help = "Target platforms (--target windows linux macos)").varargValues().default(emptyList())
    val rawArgs by argument(help = "Passthrough options for `uv tree`").multiple()

    override fun run() {
        val middleware = requireMiddleware()
        
        // Fix greedy vararg swallowing passthrough flags
        val actualTargets = targets.takeWhile { !it.startsWith("-") }
        val swallowedArgs = targets.dropWhile { !it.startsWith("-") }
        val allRawArgs = rawArgs + swallowedArgs
        
        val (positionals, extraArgs) = parsePassthroughArgs(allRawArgs)
        if (positionals.isNotEmpty()) {
            throw PrintMessage("Unexpected argument(s): ${positionals.joinToString(", ")}", statusCode = 1)
        }
        if (!middleware.showDependencyTree(null, actualTargets.ifEmpty { null }, extraArgs.ifEmpty { null })) {
            throw PrintMessage("Failed to show dependency tree", statusCode = 1)
        }
    }
}
