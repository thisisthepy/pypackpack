package org.thisisthepy.python.multiplatform.packpack.dependency.frontend

import org.thisisthepy.python.multiplatform.packpack.utils.*
import org.thisisthepy.python.multiplatform.packpack.compile.frontend.*
import org.thisisthepy.python.multiplatform.packpack.deploy.frontend.*

import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.arguments.*
import com.github.ajalt.clikt.parameters.options.*

class DynamicPackageCommand(
    private val packageName: String,
    private val operation: String,
) : BaseDependencyCommand(name = operation) {
    init {
        configureCliTerminal()
    }

    override fun help(context: Context) =
        """
        Perform $operation on package '$packageName'

        Any passthrough option for the underlying `uv` call (e.g. --dev, --extra-index-url) must
        be given before --target: since --target greedily consumes space-separated values, a
        passthrough flag placed after it would otherwise be swallowed as another target name.
        """.trimIndent()

    // Unrecognized `--flag [value]` tokens are routed into rawArgs instead of erroring, so they
    // can be forwarded to the underlying `uv` call. See parsePassthroughArgs' doc comment.
    override val treatUnknownOptionsAsArgs: Boolean = true

    val rawArgs by argument(help = "Dependencies, plus any passthrough options for the underlying `uv` call").multiple()
    val targets by option("--target", help = "Target platforms (--target windows linux macos)").varargValues().default(emptyList())

    override fun run() {
        val middleware = currentContext.findOrSetObject { createCliMiddleware() }
        
        // Fix greedy vararg swallowing passthrough flags
        val actualTargets = targets.takeWhile { !it.startsWith("-") }
        val swallowedArgs = targets.dropWhile { !it.startsWith("-") }
        val allRawArgs = rawArgs + swallowedArgs
        
        val (dependencies, extraArgs) = parsePassthroughArgs(allRawArgs)
        val forwardedExtraArgs = extraArgs.ifEmpty { null }

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
                    middleware.addDependencies(packageName, dependencies, actualTargets, forwardedExtraArgs)
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
                    middleware.removeDependencies(packageName, dependencies, actualTargets, forwardedExtraArgs)
                }
            }

            "sync" -> {
                if (dependencies.isNotEmpty()) {
                    throw PrintMessage("Unexpected argument(s): ${dependencies.joinToString(", ")}", statusCode = 1)
                }
                runBooleanCommand(
                    progressMessage = "Synchronizing dependencies for package '$packageName'...",
                    failureMessage = "Failed to synchronize dependencies for package '$packageName'",
                    successMessage = "Successfully synchronized dependencies for package '$packageName'",
                ) {
                    middleware.syncDependencies(packageName, actualTargets, forwardedExtraArgs)
                }
            }

            "tree" -> {
                if (dependencies.isNotEmpty()) {
                    throw PrintMessage("Unexpected argument(s): ${dependencies.joinToString(", ")}", statusCode = 1)
                }
                if (!middleware.showDependencyTree(packageName, actualTargets, forwardedExtraArgs)) {
                    throw PrintMessage("Failed to show dependency tree for package '$packageName'", statusCode = 1)
                }
            }

            else -> {
                throw PrintMessage("Unknown operation: $operation", statusCode = 1)
            }
        }
    }
}
