package org.thisisthepy.python.multiplatform.packpack.cli

import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.arguments.*
import com.github.ajalt.clikt.parameters.options.*

abstract class BaseDependencyCommand(
    name: String,
) : CliktCommand(name = name) {
    val dev by option("--dev", help = "Install as development dependency").flag()
    val editable by option("--editable", help = "Install in editable mode").flag()
    val noSync by option("--no-sync", help = "Skip synchronization").flag()
    val noCache by option("--no-cache", help = "Disable cache").flag()
    val quiet by option("--quiet", help = "Suppress output").flag()
    val verbose by option("--verbose", help = "Enable verbose output").flag()
    val upgrade by option("--upgrade", help = "Upgrade dependencies").flag()
    val reinstall by option("--reinstall", help = "Reinstall dependencies").flag()
    val refresh by option("--refresh", help = "Refresh cache").flag()
    val frozen by option("--frozen", help = "Use frozen lockfile").flag()
    val locked by option("--locked", help = "Use locked dependencies").flag()
    val preview by option("--preview", help = "Enable preview features").flag()
    val rawSources by option("--raw-sources", help = "Use raw sources").flag()

    protected fun getExtraArgs(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        if (dev) map["dev"] = ""
        if (editable) map["editable"] = ""
        if (noSync) map["no-sync"] = ""
        if (noCache) map["no-cache"] = ""
        if (quiet) map["quiet"] = ""
        if (verbose) map["verbose"] = ""
        if (upgrade) map["upgrade"] = ""
        if (reinstall) map["reinstall"] = ""
        if (refresh) map["refresh"] = ""
        if (frozen) map["frozen"] = ""
        if (locked) map["locked"] = ""
        if (preview) map["preview"] = ""
        if (rawSources) map["raw-sources"] = ""
        return map
    }
}

class AddCommand : BaseDependencyCommand(name = "add") {
    override fun help(context: Context) =
        """
        Add dependencies to the development environment.

        Dependencies can be specified with version constraints (e.g., requests>=2.25.1).
        """.trimIndent()

    val dependencies by argument(help = "Dependencies to add").multiple(required = true)

    override fun run() {
        val middleware = requireMiddleware()
        runBooleanCommand(
            progressMessage = "Adding dependencies: ${dependencies.joinToString(", ")}...",
            failureMessage = "Failed to add dependencies",
            successMessage = "Successfully added dependencies: ${dependencies.joinToString(", ")}",
        ) {
            middleware.addDependencies(null, dependencies, null, getExtraArgs().ifEmpty { null })
        }
    }
}

class RemoveCommand : BaseDependencyCommand(name = "remove") {
    override fun help(context: Context) = "Remove dependencies from the development environment."

    val dependencies by argument(help = "Dependencies to remove").multiple(required = true)

    override fun run() {
        val middleware = requireMiddleware()
        runBooleanCommand(
            progressMessage = "Removing dependencies: ${dependencies.joinToString(", ")}...",
            failureMessage = "Failed to remove dependencies",
            successMessage = "Successfully removed dependencies: ${dependencies.joinToString(", ")}",
        ) {
            middleware.removeDependencies(null, dependencies, null, getExtraArgs().ifEmpty { null })
        }
    }
}

class SyncCommand : BaseDependencyCommand(name = "sync") {
    override fun help(context: Context) = "Synchronize dependencies based on pyproject.toml."

    override fun run() {
        val middleware = requireMiddleware()
        runBooleanCommand(
            progressMessage = "Synchronizing dependencies...",
            failureMessage = "Failed to synchronize dependencies",
            successMessage = "Successfully synchronized dependencies",
        ) {
            middleware.syncDependencies(null, null, getExtraArgs().ifEmpty { null })
        }
    }
}

class TreeCommand : BaseDependencyCommand(name = "tree") {
    override fun help(context: Context) = "Show the dependency tree for the development environment."

    val targets by option("--target", help = "Target platforms (comma-separated)").split(",").default(emptyList())

    override fun run() {
        val middleware = requireMiddleware()
        if (!middleware.showDependencyTree(null, targets.ifEmpty { null }, getExtraArgs().ifEmpty { null })) {
            throw PrintMessage("Failed to show dependency tree", statusCode = 1)
        }
    }
}
