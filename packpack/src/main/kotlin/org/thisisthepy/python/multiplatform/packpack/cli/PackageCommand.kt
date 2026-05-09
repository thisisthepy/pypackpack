package org.thisisthepy.python.multiplatform.packpack.cli

import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.arguments.*
import com.github.ajalt.clikt.parameters.options.*

class PackageCommand : CliktCommand(name = "package") {
    override fun help(context: Context) = "Manage PyPackPack packages within the project."

    init {
        subcommands(PackageAddCommand(), PackageRemoveCommand(), PackageSyncCommand(), PackageTreeCommand())
    }

    override fun run() = Unit
}

class PackageAddCommand : BaseDependencyCommand(name = "add") {
    override fun help(context: Context) = "Add a new package to the workspace."

    val packageName by argument(help = "Package name")
    val path by option("--path", help = "Workspace directory path").default("")

    override fun run() {
        val middleware = requireMiddleware()
        runResultCommand(
            progressMessage = "Creating package '$packageName'...",
            failurePrefix = "Failed to create package",
        ) {
            middleware.addPackage(packageName, path.ifEmpty { null }, getExtraArgs().ifEmpty { null })
        }
    }
}

class PackageRemoveCommand : BaseDependencyCommand(name = "remove") {
    override fun help(context: Context) = "Remove a package from the workspace."

    val packageName by argument(help = "Package name")
    val path by option("--path", help = "Workspace directory path").default("")

    override fun run() {
        val middleware = requireMiddleware()
        runResultCommand(
            progressMessage = "Removing package '$packageName'...",
            failurePrefix = "Failed to remove package",
        ) {
            middleware.removePackage(packageName, path.ifEmpty { null })
        }
    }
}

class PackageSyncCommand : BaseDependencyCommand(name = "sync") {
    override fun help(context: Context) = "Synchronize dependencies for a specific package."

    val packageName by argument(help = "Package name")
    val targets by option("--target", help = "Target platforms (--target windows linux macos)").varargValues().default(emptyList())

    override fun run() {
        val middleware = requireMiddleware()
        runBooleanCommand(
            progressMessage = "Synchronizing dependencies for package '$packageName'...",
            failureMessage = "Failed to synchronize dependencies for package '$packageName'",
            successMessage = "Successfully synchronized dependencies for package '$packageName'",
        ) {
            middleware.syncDependencies(packageName, targets, getExtraArgs().ifEmpty { null })
        }
    }
}

class PackageTreeCommand : BaseDependencyCommand(name = "tree") {
    override fun help(context: Context) = "Show the dependency tree for a specific package."

    val packageName by argument(help = "Package name")
    val targets by option("--target", help = "Target platforms (--target windows linux macos)").varargValues().default(emptyList())

    override fun run() {
        val middleware = requireMiddleware()
        if (!middleware.showDependencyTree(packageName, targets, getExtraArgs().ifEmpty { null })) {
            throw PrintMessage("Failed to show dependency tree for package '$packageName'", statusCode = 1)
        }
    }
}
