package org.thisisthepy.python.multiplatform.packpack.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional

class PythonCommand : CliktCommand(name = "python") {
    override fun help(context: Context) = "Manage Python versions using UV."

    init {
        subcommands(
            PythonUseCommand(),
            PythonListCommand(),
            PythonFindCommand(),
            PythonInstallCommand(),
            PythonUninstallCommand(),
        )
    }

    override fun run() = Unit
}

class PythonUseCommand : CliktCommand(name = "use") {
    override fun help(context: Context) = "Switch the project to a different Python version."

    val version by argument(help = "Python version (e.g., 3.13 or 3.13.1)")

    override fun run() {
        if (!validatePythonVersion(version)) {
            throw PrintMessage("Invalid python version format. Expected X.Y or X.Y.Z", statusCode = 1)
        }

        val middleware = requireMiddleware()
        runBooleanCommand(
            progressMessage = "Changing Python version to $version...",
            failureMessage = "Failed to change Python version to $version",
            successMessage = "Changed Python version to $version",
        ) {
            middleware.changePythonVersion(version)
        }
    }
}

class PythonListCommand : CliktCommand(name = "list") {
    override fun help(context: Context) = "List all Python versions available via UV."

    override fun run() {
        val middleware = requireMiddleware()
        runBooleanCommand(
            progressMessage = "Fetching available Python versions...",
            failureMessage = "Failed to list Python versions",
        ) {
            middleware.listPythonVersions()
        }
    }
}

class PythonFindCommand : CliktCommand(name = "find") {
    override fun help(context: Context) = "Find a specific Python version."

    val version by argument(help = "Python version to find")

    override fun run() {
        if (!validatePythonVersion(version)) {
            throw PrintMessage("Invalid python version format. Expected X.Y or X.Y.Z", statusCode = 1)
        }

        val middleware = requireMiddleware()
        if (!middleware.findPythonVersion(version)) {
            throw PrintMessage("Failed to find Python version $version", statusCode = 1)
        }
    }
}

class PythonInstallCommand : CliktCommand(name = "install") {
    override fun help(context: Context) = "Install a specific Python version via UV."

    val version by argument(help = "Python version to install")
    val targetPlatform by argument(help = "Target platform for the Python version (Default: Host Platform)").optional()

    override fun run() {
        if (!validatePythonVersion(version)) {
            throw PrintMessage("Invalid python version format. Expected X.Y or X.Y.Z", statusCode = 1)
        }

        val middleware = requireMiddleware()
        runBooleanCommand(
            progressMessage = "Installing Python $version...",
            failureMessage = "Failed to install Python version $version",
            successMessage = "Successfully installed Python $version",
        ) {
            middleware.installPythonVersion(version, targetPlatform)
        }
    }
}

class PythonUninstallCommand : CliktCommand(name = "uninstall") {
    override fun help(context: Context) = "Uninstall a specific Python version."

    val version by argument(help = "Python version to uninstall")

    override fun run() {
        if (!validatePythonVersion(version)) {
            throw PrintMessage("Invalid python version format. Expected X.Y or X.Y.Z", statusCode = 1)
        }

        val middleware = requireMiddleware()
        runBooleanCommand(
            progressMessage = "Uninstalling Python $version...",
            failureMessage = "Failed to uninstall Python version $version",
            successMessage = "Successfully uninstalled Python $version",
        ) {
            middleware.uninstallPythonVersion(version)
        }
    }
}
