package org.thisisthepy.python.multiplatform.packpack.cli

import org.thisisthepy.python.multiplatform.packpack.cli.internal.ErrorHandler
import org.thisisthepy.python.multiplatform.packpack.cli.internal.runWithProgressBlocking

/** Handle python command */
fun handlePython(args: Array<String>) {
    if (args.size < 2) {
        ErrorHandler.missingArgument(
            argumentName = "subcommand",
            command = "python",
            example = "pypackpack python use 3.13",
        )
        return
    }

    val subcommand = args[1].lowercase()
    val devEnv = CliContext.middleware.getDevEnv()

    when (subcommand) {
        "use" -> {
            if (args.size < 3) {
                ErrorHandler.missingArgument(
                    argumentName = "python version",
                    command = "python use",
                    example = "pypackpack python use 3.13",
                )
                return
            }
            val pythonVersion = args[2]

            // Validate Python version format
            if (!ErrorHandler.validatePythonVersion(pythonVersion)) {
                ErrorHandler.invalidArgument(
                    argumentName = "python version",
                    value = pythonVersion,
                    expectedFormat = "X.Y or X.Y.Z (e.g., 3.13 or 3.13.1)",
                )
                return
            }

            val success =
                runWithProgressBlocking("Changing Python version to $pythonVersion...") { _ ->
                    devEnv.changePythonVersion(pythonVersion)
                }

            if (!success) {
                ErrorHandler.operationFailed(
                    operation = "change Python version to $pythonVersion",
                    suggestions =
                        listOf(
                            "Check if the Python version is available: pypackpack python find $pythonVersion",
                            "Install the Python version first: pypackpack python install $pythonVersion",
                            "Verify UV is installed and working: pypackpack version",
                        ),
                )
            } else {
                ErrorHandler.showSuccess("Changed Python version to $pythonVersion")
            }
        }

        "list" -> {
            val success =
                runWithProgressBlocking("Fetching available Python versions...") { _ ->
                    devEnv.listPythonVersions()
                }

            if (!success) {
                ErrorHandler.operationFailed(
                    operation = "list Python versions",
                    suggestions =
                        listOf(
                            "Verify UV is installed: pypackpack version",
                            "Check your internet connection",
                            "Try running: uv python list",
                        ),
                )
            }
        }

        "find" -> {
            if (args.size < 3) {
                ErrorHandler.missingArgument(
                    argumentName = "python version",
                    command = "python find",
                    example = "pypackpack python find 3.13",
                )
                return
            }
            val pythonVersion = args[2]

            // Validate Python version format
            if (!ErrorHandler.validatePythonVersion(pythonVersion)) {
                ErrorHandler.invalidArgument(
                    argumentName = "python version",
                    value = pythonVersion,
                    expectedFormat = "X.Y or X.Y.Z (e.g., 3.13 or 3.13.1)",
                )
                return
            }

            if (!devEnv.findPythonVersion(pythonVersion)) {
                ErrorHandler.operationFailed(
                    operation = "find Python version $pythonVersion",
                    suggestions =
                        listOf(
                            "Try a different version format (e.g., 3.13 instead of 3.13.0)",
                            "Check available versions: pypackpack python list",
                            "Install the version: pypackpack python install $pythonVersion",
                        ),
                )
            }
        }

        "install" -> {
            if (args.size < 3) {
                ErrorHandler.missingArgument(
                    argumentName = "python version",
                    command = "python install",
                    example = "pypackpack python install 3.13",
                )
                return
            }
            val pythonVersion = args[2]

            // Validate Python version format
            if (!ErrorHandler.validatePythonVersion(pythonVersion)) {
                ErrorHandler.invalidArgument(
                    argumentName = "python version",
                    value = pythonVersion,
                    expectedFormat = "X.Y or X.Y.Z (e.g., 3.13 or 3.13.1)",
                )
                return
            }

            val success =
                runWithProgressBlocking("Installing Python $pythonVersion...") { _ ->
                    devEnv.installPythonVersion(pythonVersion)
                }

            if (!success) {
                ErrorHandler.operationFailed(
                    operation = "install Python version $pythonVersion",
                    suggestions =
                        listOf(
                            "Check your internet connection",
                            "Verify UV is installed: pypackpack version",
                            "Check available versions: pypackpack python list",
                            "Try running manually: uv python install $pythonVersion",
                        ),
                )
            } else {
                ErrorHandler.showSuccess("Successfully installed Python $pythonVersion")
            }
        }

        "uninstall" -> {
            if (args.size < 3) {
                ErrorHandler.missingArgument(
                    argumentName = "python version",
                    command = "python uninstall",
                    example = "pypackpack python uninstall 3.13",
                )
                return
            }
            val pythonVersion = args[2]

            // Validate Python version format
            if (!ErrorHandler.validatePythonVersion(pythonVersion)) {
                ErrorHandler.invalidArgument(
                    argumentName = "python version",
                    value = pythonVersion,
                    expectedFormat = "X.Y or X.Y.Z (e.g., 3.13 or 3.13.1)",
                )
                return
            }

            val success =
                runWithProgressBlocking("Uninstalling Python $pythonVersion...") { _ ->
                    devEnv.uninstallPythonVersion(pythonVersion)
                }

            if (!success) {
                ErrorHandler.operationFailed(
                    operation = "uninstall Python version $pythonVersion",
                    suggestions =
                        listOf(
                            "Check if the version is installed: pypackpack python list",
                            "Verify UV is installed: pypackpack version",
                            "Try running manually: uv python uninstall $pythonVersion",
                        ),
                )
            } else {
                ErrorHandler.showSuccess("Successfully uninstalled Python $pythonVersion")
            }
        }

        else -> {
            val availableSubcommands = listOf("use", "list", "find", "install", "uninstall")
            ErrorHandler.unknownCommand(
                "python $subcommand",
                availableSubcommands.map { "python $it" },
            )
        }
    }
}
