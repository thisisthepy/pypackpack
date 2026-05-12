package org.thisisthepy.python.multiplatform.packpack.cli

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
        """.trimIndent()

    val dependencies by argument(help = "Dependencies to add").multiple(required = true)

    override fun run() {
        val middleware = requireMiddleware()
        runBooleanCommand(
            progressMessage = "Adding dependencies: ${dependencies.joinToString(", ")}...",
            failureMessage = "Failed to add dependencies",
            successMessage = "Successfully added dependencies: ${dependencies.joinToString(", ")}",
        ) {
            middleware.addDependencies(null, dependencies, null, null)
        }
    }
}

class RemoveCommand : BaseDependencyCommand(name = "remove") {
    override fun help(context: Context) = "Remove dependencies from the development environment."

    val dependencies by argument(help = "Dependencies to remove").multiple(required = true)

    override fun run() {
        val middleware = requireMiddleware()
        runBooleanCommand(
            progressMessage = "Removing dependencies: ${dependencies.joinToString(", ")}...",
            failureMessage = "Failed to remove dependencies",
            successMessage = "Successfully removed dependencies: ${dependencies.joinToString(", ")}",
        ) {
            middleware.removeDependencies(null, dependencies, null, null)
        }
    }
}

class SyncCommand : BaseDependencyCommand(name = "sync") {
    override fun help(context: Context) = "Synchronize dependencies based on pyproject.toml."

    override fun run() {
        val middleware = requireMiddleware()
        runBooleanCommand(
            progressMessage = "Synchronizing dependencies...",
            failureMessage = "Failed to synchronize dependencies",
            successMessage = "Successfully synchronized dependencies",
        ) {
            middleware.syncDependencies(null, null, null)
        }
    }
}

class TreeCommand : BaseDependencyCommand(name = "tree") {
    override fun help(context: Context) = "Show the dependency tree for the development environment."

    val targets by option("--target", help = "Target platforms (--target windows linux macos)").varargValues().default(emptyList())

    override fun run() {
        val middleware = requireMiddleware()
        if (!middleware.showDependencyTree(null, targets.ifEmpty { null }, null)) {
            throw PrintMessage("Failed to show dependency tree", statusCode = 1)
        }
    }
}
