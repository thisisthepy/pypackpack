package org.thisisthepy.python.multiplatform.packpack.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import org.thisisthepy.python.multiplatform.packpack.utils.Platforms

class TargetCommand : CliktCommand(name = "target") {
    override fun help(context: Context) = "Manage target platforms for packages."

    init {
        subcommands(TargetListCommand(), TargetAddCommand(), TargetRemoveCommand())
    }

    override fun run() = Unit
}

class TargetListCommand : CliktCommand(name = "list") {
    override fun help(context: Context) = "List all supported target platforms."

    override fun run() {
        echo("Possible values:")
        Platforms.SUPPORTED_TARGETS.forEach { target ->
            echo("  - $target")
        }
    }
}

class TargetAddCommand : CliktCommand(name = "add") {
    override fun help(context: Context) = "Add target platforms to a package."

    val targets by argument(help = "Target platform names").multiple(required = true)
    val path by option("--path", help = "Package directory path").default("")

    override fun run() {
        val middleware = requireMiddleware()
        runResultCommand(
            progressMessage = "Adding targets: ${targets.joinToString(", ")}...",
            failurePrefix = "Failed to add targets",
        ) {
            middleware.addTargets(targets, path.ifEmpty { null })
        }
    }
}

class TargetRemoveCommand : CliktCommand(name = "remove") {
    override fun help(context: Context) = "Remove target platforms from a package."

    val targets by argument(help = "Target platform names").multiple(required = true)
    val path by option("--path", help = "Package directory path").default("")

    override fun run() {
        val middleware = requireMiddleware()
        runResultCommand(
            progressMessage = "Removing targets: ${targets.joinToString(", ")}...",
            failurePrefix = "Failed to remove targets",
        ) {
            middleware.removeTargets(targets, path.ifEmpty { null })
        }
    }
}
