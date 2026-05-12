package org.thisisthepy.python.multiplatform.packpack.cli

import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.arguments.*
import com.github.ajalt.clikt.parameters.options.*

class DynamicPackageCommand(
    private val packageName: String,
    private val operation: String,
) : BaseDependencyCommand(name = operation) {
    override fun help(context: Context) = "Perform $operation on package '$packageName'"

    val dependencies by argument(help = "Dependencies").multiple()
    val targets by option("--target", help = "Target platforms (--target windows linux macos)").varargValues().default(emptyList())

    override fun run() {
        val middleware = currentContext.findOrSetObject { createCliMiddleware() }

        when (operation) {
            "add" -> {
                if (dependencies.isEmpty()) {
                    throw PrintMessage("No dependencies specified for package '$packageName'", statusCode = 1)
                }
                runBooleanCommand(
                    progressMessage = "Adding dependencies to package '$packageName'...",
                    failureMessage = "Failed to add dependencies to package '$packageName'",
                    successMessage = "Successfully added dependencies to package '$packageName'",
                ) {
                    middleware.addDependencies(packageName, dependencies, targets, null)
                }
            }

            "remove" -> {
                if (dependencies.isEmpty()) {
                    throw PrintMessage("No dependencies specified for package '$packageName'", statusCode = 1)
                }
                runBooleanCommand(
                    progressMessage = "Removing dependencies from package '$packageName'...",
                    failureMessage = "Failed to remove dependencies from package '$packageName'",
                    successMessage = "Successfully removed dependencies from package '$packageName'",
                ) {
                    middleware.removeDependencies(packageName, dependencies, targets, null)
                }
            }

            "sync" -> {
                runBooleanCommand(
                    progressMessage = "Synchronizing dependencies for package '$packageName'...",
                    failureMessage = "Failed to synchronize dependencies for package '$packageName'",
                    successMessage = "Successfully synchronized dependencies for package '$packageName'",
                ) {
                    middleware.syncDependencies(packageName, targets, null)
                }
            }

            "tree" -> {
                if (!middleware.showDependencyTree(packageName, targets, null)) {
                    throw PrintMessage("Failed to show dependency tree for package '$packageName'", statusCode = 1)
                }
            }

            else -> {
                throw PrintMessage("Unknown operation: $operation", statusCode = 1)
            }
        }
    }
}
