package org.thisisthepy.python.multiplatform.packpack.dependency.frontend

import org.thisisthepy.python.multiplatform.packpack.utils.*
import org.thisisthepy.python.multiplatform.packpack.compile.frontend.*
import org.thisisthepy.python.multiplatform.packpack.deploy.frontend.*

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
            middleware.addPackage(packageName, path.ifEmpty { null }, null)
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

    // Unrecognized `--flag [value]` tokens are routed into rawArgs instead of erroring, so they
    // can be forwarded to the underlying `uv` call. See parsePassthroughArgs' doc comment.
    override val treatUnknownOptionsAsArgs: Boolean = true

    val packageName by argument(help = "Package name")
    val targets by option("--target", help = "Target platforms (--target windows linux macos)").varargValues().default(emptyList())
    val rawArgs by argument(help = "Passthrough options for `uv sync` (must come before --target)").multiple()

    override fun run() {
        val middleware = requireMiddleware()
        
        // Fix greedy vararg swallowing passthrough flags
        val actualTargets = targets.takeWhile { !it.startsWith("-") }
        val swallowedArgs = targets.dropWhile { !it.startsWith("-") }
        val allRawArgs = rawArgs + swallowedArgs
        
        val (positionals, extraArgs) = parsePassthroughArgs(allRawArgs)
        if (positionals.isNotEmpty()) {
            throw PrintMessage("Unexpected argument(s): ${positionals.joinToString(", ")}", statusCode = 1)
        }
        runBooleanCommand(
            progressMessage = "Synchronizing dependencies for package '$packageName'...",
            failureMessage = "Failed to synchronize dependencies for package '$packageName'",
            successMessage = "Successfully synchronized dependencies for package '$packageName'",
        ) {
            middleware.syncDependencies(packageName, actualTargets, extraArgs.ifEmpty { null })
        }
    }
}

class PackageTreeCommand : BaseDependencyCommand(name = "tree") {
    override fun help(context: Context) = "Show the dependency tree for a specific package."

    // Unrecognized `--flag [value]` tokens are routed into rawArgs instead of erroring, so they
    // can be forwarded to the underlying `uv` call. See parsePassthroughArgs' doc comment.
    override val treatUnknownOptionsAsArgs: Boolean = true

    val packageName by argument(help = "Package name")
    val targets by option("--target", help = "Target platforms (--target windows linux macos)").varargValues().default(emptyList())
    val rawArgs by argument(help = "Passthrough options for `uv tree` (must come before --target)").multiple()

    override fun run() {
        val middleware = requireMiddleware()
        
        // Fix greedy vararg swallowing passthrough flags
        val actualTargets = targets.takeWhile { !it.startsWith("-") }
        val swallowedArgs = targets.dropWhile { !it.startsWith("-") }
        val allRawArgs = rawArgs + swallowedArgs
        
        val (positionals, extraArgs) = parsePassthroughArgs(allRawArgs)
        if (positionals.isNotEmpty()) {
            throw PrintMessage("Unexpected argument(s): ${positionals.joinToString(", ")}", statusCode = 1)
        }
        if (!middleware.showDependencyTree(packageName, actualTargets, extraArgs.ifEmpty { null })) {
            throw PrintMessage("Failed to show dependency tree for package '$packageName'", statusCode = 1)
        }
    }
}
