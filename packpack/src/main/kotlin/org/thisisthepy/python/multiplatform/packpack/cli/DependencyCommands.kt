package org.thisisthepy.python.multiplatform.packpack.cli

import org.thisisthepy.python.multiplatform.packpack.cli.internal.*

fun handleAddDependency(args: Array<String>) {
    if (args.size < 2) {
        ErrorHandler.missingArgument(
            argumentName = "dependency name",
            command = "add",
            example = "pypackpack add requests==2.25.1",
        )
        return
    }

    val parsed = parseDependenciesAndExtraArgs(args, startIndex = 1)
    val dependencies = parsed.dependencies
    val extraArgs = parsed.extraArgs

    if (dependencies.isEmpty()) {
        ErrorHandler.showError(
            type = ErrorHandler.ErrorType.VALIDATION_ERROR,
            message = "No dependencies specified",
            suggestions =
                listOf(
                    "Provide at least one dependency name",
                    "Example: pypackpack add requests numpy",
                ),
        )
        return
    }

    val success =
        runWithProgressBlocking("Adding dependencies: ${dependencies.joinToString(", ")}...") { _ ->
            CliContext.middleware.addDependencies(null, dependencies, null, extraArgs.ifEmpty { null })
        }

    if (!success) {
        ErrorHandler.operationFailed(
            operation = "add dependencies: ${dependencies.joinToString(", ")}",
            suggestions =
                listOf(
                    "Check your internet connection",
                    "Verify the dependency names are correct",
                    "Make sure you're in a PyPackPack project directory",
                    "Try running: pypackpack sync",
                ),
        )
    } else {
        ErrorHandler.showSuccess(
            "Successfully added dependencies: ${dependencies.joinToString(", ")}",
        )
    }
}

fun handleRemoveDependency(args: Array<String>) {
    if (args.size < 2) {
        ErrorHandler.missingArgument(
            argumentName = "dependency name",
            command = "remove",
            example = "pypackpack remove requests",
        )
        return
    }

    val parsed = parseDependenciesAndExtraArgs(args, startIndex = 1)
    val dependencies = parsed.dependencies
    val extraArgs = parsed.extraArgs

    if (dependencies.isEmpty()) {
        ErrorHandler.showError(
            type = ErrorHandler.ErrorType.VALIDATION_ERROR,
            message = "No dependencies specified",
            suggestions =
                listOf(
                    "Provide at least one dependency name",
                    "Example: pypackpack remove requests numpy",
                ),
        )
        return
    }

    val success =
        runWithProgressBlocking(
            "Removing dependencies: ${dependencies.joinToString(", ")}...",
        ) { _ ->
            CliContext.middleware.removeDependencies(null, dependencies, null, extraArgs.ifEmpty { null })
        }

    if (!success) {
        ErrorHandler.operationFailed(
            operation = "remove dependencies: ${dependencies.joinToString(", ")}",
            suggestions =
                listOf(
                    "Check if the dependencies are installed",
                    "Verify the dependency names are correct",
                    "Make sure you're in a PyPackPack project directory",
                    "Try running: pypackpack tree",
                ),
        )
    } else {
        ErrorHandler.showSuccess(
            "Successfully removed dependencies: ${dependencies.joinToString(", ")}",
        )
    }
}

fun handleSyncDependency(args: Array<String>) {
    val extraArgs =
        parseExtraArgsOnly(args, startIndex = 1) { arg ->
            ErrorHandler.showWarning("Unexpected argument ignored: $arg")
        }

    val success =
        runWithProgressBlocking("Synchronizing dependencies...") { _ ->
            CliContext.middleware.syncDependencies(null, null, extraArgs.ifEmpty { null })
        }

    if (!success) {
        ErrorHandler.operationFailed(
            operation = "synchronize dependencies",
            suggestions =
                listOf(
                    "Check your internet connection",
                    "Verify pyproject.toml exists and is valid",
                    "Make sure you're in a PyPackPack project directory",
                    "Try running: pypackpack add <dependency> to add missing dependencies",
                ),
        )
    } else {
        ErrorHandler.showSuccess("Successfully synchronized dependencies")
    }
}

fun handleTreeDependency(args: Array<String>) {
    val extraArgs =
        parseExtraArgsOnly(args, startIndex = 1) { arg ->
            ErrorHandler.showWarning("Unexpected argument ignored: $arg")
        }

    if (!CliContext.middleware.showDependencyTree(null, null, extraArgs.ifEmpty { null })) {
        ErrorHandler.operationFailed(
            operation = "show dependency tree",
            suggestions =
                listOf(
                    "Make sure you're in a PyPackPack project directory",
                    "Verify dependencies are installed: pypackpack sync",
                    "Check if pyproject.toml exists and is valid",
                ),
        )
    }
}
