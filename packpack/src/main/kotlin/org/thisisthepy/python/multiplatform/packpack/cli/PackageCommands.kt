package org.thisisthepy.python.multiplatform.packpack.cli

import org.thisisthepy.python.multiplatform.packpack.cli.internal.ErrorHandler
import org.thisisthepy.python.multiplatform.packpack.cli.internal.parseTargetsExtraArgsAndMaybeDependencies
import org.thisisthepy.python.multiplatform.packpack.cli.internal.runWithProgressBlocking

/** Handle package command */
fun handlePackage(args: Array<String>) {
    if (args.size < 2) {
        ErrorHandler.missingArgument(
            argumentName = "subcommand",
            command = "package",
            example = "pypackpack package add my_package",
        )
        return
    }

    val subcommand = args[1].lowercase()

    when (subcommand) {
        "add" -> {
            if (args.size < 3) {
                ErrorHandler.missingArgument(
                    argumentName = "package name",
                    command = "package add",
                    example = "pypackpack package add my_package",
                )
                return
            }
            val packageName = args[2]

            // Validate package name
            if (!ErrorHandler.validatePackageName(packageName)) {
                ErrorHandler.invalidArgument(
                    argumentName = "package name",
                    value = packageName,
                    expectedFormat =
                        "Valid Python package name (letters, numbers, underscores, starting with letter)",
                )
                return
            }

            val crossEnv = CliContext.middleware.getCrossEnv()

            val success =
                runWithProgressBlocking("Adding package '$packageName'...") { _ ->
                    crossEnv.addPackage(packageName)
                }

            if (!success) {
                ErrorHandler.operationFailed(
                    operation = "add package '$packageName'",
                    suggestions =
                        listOf(
                            "Check if you're in a PyPackPack project directory",
                            "Verify the package name doesn't already exist",
                            "Make sure you have write permissions in the current directory",
                        ),
                )
            } else {
                ErrorHandler.showSuccess("Successfully added package '$packageName'")
            }
        }

        "remove" -> {
            if (args.size < 3) {
                ErrorHandler.missingArgument(
                    argumentName = "package name",
                    command = "package remove",
                    example = "pypackpack package remove my_package",
                )
                return
            }
            val packageName = args[2]

            val crossEnv = CliContext.middleware.getCrossEnv()

            val success =
                runWithProgressBlocking("Removing package '$packageName'...") { _ ->
                    crossEnv.removePackage(packageName)
                }

            if (!success) {
                ErrorHandler.operationFailed(
                    operation = "remove package '$packageName'",
                    suggestions =
                        listOf(
                            "Check if the package exists in the project",
                            "Verify you're in a PyPackPack project directory",
                            "Make sure you have write permissions",
                        ),
                )
            } else {
                ErrorHandler.showSuccess("Successfully removed package '$packageName'")
            }
        }

        else -> {
            // Handle package-specific commands
            if (args.size < 3) {
                ErrorHandler.missingArgument(
                    argumentName = "command arguments",
                    command = "package",
                    example = "pypackpack package add my_package",
                )
                return
            }

            handlePackageCommand(args)
        }
    }
}

/** Handle package command */
fun handlePackageCommand(args: Array<String>) {
    if (args.size < 3) {
        println("Missing package command arguments")
        printHelp()
        return
    }

    val packageName = args[1]
    when (val subcommand = args[2].lowercase()) {
        "add" -> {
            if (args.size < 4) {
                println("Missing dependency name")
                return
            }

            val parsed =
                parseTargetsExtraArgsAndMaybeDependencies(
                    args,
                    startIndex = 3,
                    collectDependencies = true,
                    onUnexpectedNonTarget = { _ -> },
                )
            val dependencies = parsed.dependencies
            val targets = parsed.targets
            val extraArgs = parsed.extraArgs

            if (dependencies.isEmpty()) {
                println("No dependencies specified")
                return
            }

            if (!CliContext.middleware.addDependencies(
                    packageName,
                    dependencies,
                    targets.ifEmpty { null },
                    extraArgs.ifEmpty { null },
                )
            ) {
                println(
                    "Failed to add dependencies: ${dependencies.joinToString(", ")} to package $packageName",
                )
            }
        }

        "remove" -> {
            if (args.size < 4) {
                println("Missing dependency name")
                return
            }

            val parsed =
                parseTargetsExtraArgsAndMaybeDependencies(
                    args,
                    startIndex = 3,
                    collectDependencies = true,
                    onUnexpectedNonTarget = { _ -> },
                )
            val dependencies = parsed.dependencies
            val targets = parsed.targets
            val extraArgs = parsed.extraArgs

            if (dependencies.isEmpty()) {
                println("No dependencies specified")
                return
            }

            if (!CliContext.middleware.removeDependencies(
                    packageName,
                    dependencies,
                    targets.ifEmpty { null },
                    extraArgs.ifEmpty { null },
                )
            ) {
                println(
                    "Failed to remove dependencies: ${dependencies.joinToString(", ")} from package $packageName",
                )
            }
        }

        "sync" -> {
            val parsed =
                parseTargetsExtraArgsAndMaybeDependencies(
                    args,
                    startIndex = 3,
                    collectDependencies = false,
                    onUnexpectedNonTarget = { arg -> println("Unexpected argument: $arg") },
                )
            val targets = parsed.targets
            val extraArgs = parsed.extraArgs

            if (!CliContext.middleware.syncDependencies(
                    packageName,
                    targets.ifEmpty { null },
                    extraArgs.ifEmpty { null },
                )
            ) {
                println("Failed to synchronize dependencies for package $packageName")
            }
        }

        "tree" -> {
            val parsed =
                parseTargetsExtraArgsAndMaybeDependencies(
                    args,
                    startIndex = 3,
                    collectDependencies = false,
                    onUnexpectedNonTarget = { arg -> println("Unexpected argument: $arg") },
                )
            val targets = parsed.targets
            val extraArgs = parsed.extraArgs

            if (!CliContext.middleware.showDependencyTree(
                    packageName,
                    targets.ifEmpty { null },
                    extraArgs.ifEmpty { null },
                )
            ) {
                println("Failed to show dependency tree for package $packageName")
            }
        }

        else -> {
            println("Unknown package subcommand: $subcommand")
            printHelp()
        }
    }
}
