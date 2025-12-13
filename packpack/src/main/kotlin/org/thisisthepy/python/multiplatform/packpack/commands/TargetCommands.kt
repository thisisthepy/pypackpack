package org.thisisthepy.python.multiplatform.packpack.commands

import org.thisisthepy.python.multiplatform.packpack.util.CliContext
import org.thisisthepy.python.multiplatform.packpack.util.ErrorHandler

/** Handle target command */
fun handleTarget(args: Array<String>) {
    if (args.size < 2) {
        ErrorHandler.missingArgument(
            argumentName = "subcommand",
            command = "target",
            example = "pypackpack target add windows my_package",
        )
        return
    }

    val subcommand = args[1].lowercase()

    when (subcommand) {
        "list" -> {
            // List all supported target platforms
            val supportedTargets =
                listOf(
                    "android_21_arm64",
                    "android_21_x86_64",
                    "windows_amd64",
                    "macos_arm64",
                    "macos_x86_64",
                    "linux_amd64",
                )

            println("Supported target platforms:")
            supportedTargets.forEach { target -> println("  - $target") }
            println()
            println("Usage examples:")
            println("  pypackpack target add windows_amd64 my_package")
            println("  pypackpack target add android_21_arm64 my_package")
            println("  pypackpack target add linux_amd64 my_package")
        }

        "add" -> {
            if (args.size < 3) {
                ErrorHandler.missingArgument(
                    argumentName = "target name",
                    command = "target add",
                    example = "pypackpack target add windows my_package",
                )
                return
            }
            val targetName = args[2]
            val packageName = if (args.size > 3) args[3] else null

            if (packageName == null) {
                ErrorHandler.missingArgument(
                    argumentName = "package name",
                    command = "target add",
                    example = "pypackpack target add windows my_package",
                )
                return
            }

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

            if (!crossEnv.addTargetPlatform(packageName, listOf(targetName))) {
                ErrorHandler.operationFailed(
                    operation = "add target '$targetName' to package '$packageName'",
                    suggestions =
                        listOf(
                            "Check if the target '$targetName' is supported: pypackpack target list",
                            "Verify the package '$packageName' exists in the project",
                            "Make sure you have write permissions",
                        ),
                )
            } else {
                ErrorHandler.showSuccess(
                    "Successfully added target '$targetName' to package '$packageName'",
                )
            }
        }

        "remove" -> {
            if (args.size < 3) {
                ErrorHandler.missingArgument(
                    argumentName = "target name",
                    command = "target remove",
                    example = "pypackpack target remove windows my_package",
                )
                return
            }
            val targetName = args[2]
            val packageName = if (args.size > 3) args[3] else null

            if (packageName == null) {
                ErrorHandler.missingArgument(
                    argumentName = "package name",
                    command = "target remove",
                    example = "pypackpack target remove windows my_package",
                )
                return
            }

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

            if (!crossEnv.removeTargetPlatform(packageName, listOf(targetName))) {
                ErrorHandler.operationFailed(
                    operation = "remove target '$targetName' from package '$packageName'",
                    suggestions =
                        listOf(
                            "Check if the target '$targetName' exists for package '$packageName'",
                            "Verify the package '$packageName' exists in the project",
                            "Make sure you have write permissions",
                        ),
                )
            } else {
                ErrorHandler.showSuccess(
                    "Successfully removed target '$targetName' from package '$packageName'",
                )
            }
        }

        else -> {
            val availableSubcommands = listOf("list", "add", "remove")
            ErrorHandler.unknownCommand(
                "target $subcommand",
                availableSubcommands.map { "target $it" },
            )
        }
    }
}
