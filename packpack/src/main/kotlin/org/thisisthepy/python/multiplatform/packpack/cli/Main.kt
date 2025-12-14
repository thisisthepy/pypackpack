package org.thisisthepy.python.multiplatform.packpack.cli

import org.thisisthepy.python.multiplatform.packpack.cli.internal.ErrorHandler
import org.thisisthepy.python.multiplatform.packpack.config.PackPackConfig
import org.thisisthepy.python.multiplatform.packpack.dependency.frontend.BaseInterface
import org.thisisthepy.python.multiplatform.packpack.dependency.middleware.BaseInterface as MiddlewareInterface

/** Global CLI context holding shared instances */
internal object CliContext {
    private val frontend: BaseInterface by lazy { BaseInterface.create("cli").apply { initialize() } }
    val middleware: MiddlewareInterface by lazy { frontend.getMiddleware() }
}

/** Main entry point for CLI */
fun main(args: Array<String>) {
    // Initialize global configuration
    PackPackConfig.initialize()

    if (args.isEmpty()) {
        printHelp()
        return
    }

    when (val command = args[0].lowercase()) {
        "help", "--help", "-h" -> {
            printHelp()
        }

        "version", "--version", "-v" -> {
            printVersion()
        }

        "init" -> {
            handleInit(args)
        }

        "python" -> {
            handlePython(args)
        }

        "package" -> {
            handlePackage(args)
        }

        "target" -> {
            handleTarget(args)
        }

        "add" -> {
            handleAddDependency(args)
        }

        "remove" -> {
            handleRemoveDependency(args)
        }

        "sync" -> {
            handleSyncDependency(args)
        }

        "tree" -> {
            handleTreeDependency(args)
        }

        else -> {
            // Check if it's a package command
            val packageCommands = listOf("add", "remove", "sync", "tree")
            val subcommand = if (args.size > 1) args[1].lowercase() else ""

            if (subcommand in packageCommands) {
                // Parse as: pypackpack <package> <command> [args...]
                val newArgs = args.toMutableList()
                newArgs.add(0, "package") // Insert 'package' before the package name
                handlePackageCommand(newArgs.toTypedArray())
            } else {
                val availableCommands =
                    listOf(
                        "help",
                        "version",
                        "init",
                        "python",
                        "package",
                        "target",
                        "add",
                        "remove",
                        "sync",
                        "tree",
                    )
                ErrorHandler.unknownCommand(command, availableCommands)
            }
        }
    }
}