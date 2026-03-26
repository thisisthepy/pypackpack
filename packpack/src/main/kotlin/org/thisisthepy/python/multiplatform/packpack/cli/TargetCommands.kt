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
    private data class SectionRule(
        val title: String,
        val matcher: (String) -> Boolean,
    )

    private val sectionRules =
        listOf(
            SectionRule("Aliases") { it in setOf("windows", "linux", "macos") },
            SectionRule("Windows") { it.contains("windows") && it != "windows" },
            SectionRule("Linux") { (it.contains("unknown-linux") || it.contains("manylinux")) && it != "linux" },
            SectionRule("macOS") { it.contains("apple-darwin") && it != "macos" },
            SectionRule("Android") { it.contains("android") },
            SectionRule("Wasm") { it.contains("wasm") || it.contains("pyodide") },
            SectionRule("iOS") { it.contains("apple-ios") },
        )

    override fun help(context: Context) = "List all supported target platforms."

    override fun run() {
        echo("Supported target platforms")
        echo()

        targetSections().forEach { (header, targets) ->
            if (targets.isEmpty()) {
                return@forEach
            }

            echo("$header:")
            targets.forEach { target ->
                echo("  - $target")
            }
            echo()
        }
    }

    private fun targetSections(): List<Pair<String, List<String>>> {
        val targets = Platforms.SUPPORTED_TARGETS
        return sectionRules.map { rule ->
            rule.title to targets.filter(rule.matcher)
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
