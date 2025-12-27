package org.thisisthepy.python.multiplatform.packpack.cli

import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.arguments.*
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.*
import kotlinx.coroutines.runBlocking
import org.thisisthepy.python.multiplatform.packpack.config.PackPackConfig
import org.thisisthepy.python.multiplatform.packpack.dependency.frontend.BaseInterface
import org.thisisthepy.python.multiplatform.packpack.util.Platforms
import org.thisisthepy.python.multiplatform.packpack.dependency.middleware.BaseInterface as MiddlewareInterface

class PyPackPackCommand : CliktCommand(name = "pypackpack") {
    override fun help(context: Context) =
        """
        PyPackPack (PPP) - A multiplatform solution to distribute python project.
        
        Use 'pypackpack <command> --help' for more information about a command.
        
        You can also use: pypackpack <package_name> <command> [args] 
        to execute commands on specific packages.
        """.trimIndent()

    override fun aliases() =
        mapOf(
            "ppp" to listOf("pypackpack"),
        )

    init {
        subcommands(
            InitCommand(),
            PythonCommand(),
            PackageCommand(),
            TargetCommand(),
            AddCommand(),
            RemoveCommand(),
            SyncCommand(),
            TreeCommand(),
            VersionCommand(),
        )
    }

    override fun run() {
        PackPackConfig.initialize()
        currentContext.obj = BaseInterface.create("cli").apply { initialize() }.getMiddleware()
    }
}

class VersionCommand : CliktCommand(name = "version") {
    override fun help(context: Context) = "Show version information"

    override fun aliases() =
        mapOf(
            "v" to listOf("version"),
            "--version" to listOf("version"),
            "-v" to listOf("version"),
        )

    override fun run() {
        echo("PyPackPack version 0.1.0")
        val middleware = currentContext.findObject<MiddlewareInterface>()!!
        val backend = middleware.getBackend()

        runBlocking {
            if (backend.isToolInstalled()) {
                backend
                    .getVersion()
                    .onSuccess { version ->
                        echo("UV version: ${version.trim()}")
                    }.onFailure {
                        echo("UV is installed but version check failed", err = true)
                    }
            } else {
                echo("UV is not installed", err = true)
            }
        }
    }
}

class InitCommand : CliktCommand(name = "init") {
    override fun help(context: Context) =
        """
        Initialize a new PyPackPack project.
        
        Creates a pyproject.toml file with basic project structure and configuration.
        """.trimIndent()

    val projectName by argument(help = "The name of the project").optional()
    val pythonVersion by argument(help = "Python version to use (default: 3.13)").default("3.13")

    override fun run() {
        val middleware = currentContext.findObject<MiddlewareInterface>()!!
        val term = currentContext.terminal

        val success =
            term.runWithProgress("Initializing project...") {
                middleware.initProject(projectName, pythonVersion)
            }

        if (!success) {
            throw PrintMessage("Failed to initialize project", statusCode = 1)
        } else {
            echo("Successfully initialized project.")
        }
    }
}

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
        val middleware = currentContext.findObject<MiddlewareInterface>()!!
        val term = currentContext.terminal

        val success =
            term.runWithProgress("Adding dependencies: ${dependencies.joinToString(", ")}...") {
                middleware.addDependencies(null, dependencies, null, getExtraArgs().ifEmpty { null })
            }

        if (!success) {
            throw PrintMessage("Failed to add dependencies", statusCode = 1)
        } else {
            echo("Successfully added dependencies: ${dependencies.joinToString(", ")}")
        }
    }
}

class RemoveCommand : BaseDependencyCommand(name = "remove") {
    override fun help(context: Context) = "Remove dependencies from the development environment."

    val dependencies by argument(help = "Dependencies to remove").multiple(required = true)

    override fun run() {
        val middleware = currentContext.findObject<MiddlewareInterface>()!!
        val term = currentContext.terminal

        val success =
            term.runWithProgress("Removing dependencies: ${dependencies.joinToString(", ")}...") {
                middleware.removeDependencies(null, dependencies, null, getExtraArgs().ifEmpty { null })
            }

        if (!success) {
            throw PrintMessage("Failed to remove dependencies", statusCode = 1)
        } else {
            echo("Successfully removed dependencies: ${dependencies.joinToString(", ")}")
        }
    }
}

class SyncCommand : BaseDependencyCommand(name = "sync") {
    override fun help(context: Context) = "Synchronize dependencies based on pyproject.toml."

    override fun run() {
        val middleware = currentContext.findObject<MiddlewareInterface>()!!
        val term = currentContext.terminal

        val success =
            term.runWithProgress("Synchronizing dependencies...") {
                middleware.syncDependencies(null, null, getExtraArgs().ifEmpty { null })
            }

        if (!success) {
            throw PrintMessage("Failed to synchronize dependencies", statusCode = 1)
        } else {
            echo("Successfully synchronized dependencies")
        }
    }
}

class TreeCommand : BaseDependencyCommand(name = "tree") {
    override fun help(context: Context) = "Show the dependency tree for the development environment."

    override fun run() {
        val middleware = currentContext.findObject<MiddlewareInterface>()!!

        if (!middleware.showDependencyTree(null, null, getExtraArgs().ifEmpty { null })) {
            throw PrintMessage("Failed to show dependency tree", statusCode = 1)
        }
    }
}

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

        val middleware = currentContext.findObject<MiddlewareInterface>()!!
        val term = currentContext.terminal
        val devEnv = middleware.getDevEnv()

        val success =
            term.runWithProgress("Changing Python version to $version...") {
                devEnv.changePythonVersion(version)
            }

        if (!success) {
            throw PrintMessage("Failed to change Python version to $version", statusCode = 1)
        } else {
            echo("Changed Python version to $version")
        }
    }
}

class PythonListCommand : CliktCommand(name = "list") {
    override fun help(context: Context) = "List all Python versions available via UV."

    override fun run() {
        val middleware = currentContext.findObject<MiddlewareInterface>()!!
        val term = currentContext.terminal
        val devEnv = middleware.getDevEnv()

        val success =
            term.runWithProgress("Fetching available Python versions...") {
                devEnv.listPythonVersions()
            }

        if (!success) {
            throw PrintMessage("Failed to list Python versions", statusCode = 1)
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

        val middleware = currentContext.findObject<MiddlewareInterface>()!!
        val devEnv = middleware.getDevEnv()

        if (!devEnv.findPythonVersion(version)) {
            throw PrintMessage("Failed to find Python version $version", statusCode = 1)
        }
    }
}

class PythonInstallCommand : CliktCommand(name = "install") {
    override fun help(context: Context) = "Install a specific Python version via UV."

    val version by argument(help = "Python version to install")

    override fun run() {
        if (!validatePythonVersion(version)) {
            throw PrintMessage("Invalid python version format. Expected X.Y or X.Y.Z", statusCode = 1)
        }

        val middleware = currentContext.findObject<MiddlewareInterface>()!!
        val term = currentContext.terminal
        val devEnv = middleware.getDevEnv()

        val success =
            term.runWithProgress("Installing Python $version...") {
                devEnv.installPythonVersion(version)
            }

        if (!success) {
            throw PrintMessage("Failed to install Python version $version", statusCode = 1)
        } else {
            echo("Successfully installed Python $version")
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

        val middleware = currentContext.findObject<MiddlewareInterface>()!!
        val term = currentContext.terminal
        val devEnv = middleware.getDevEnv()

        val success =
            term.runWithProgress("Uninstalling Python $version...") {
                devEnv.uninstallPythonVersion(version)
            }

        if (!success) {
            throw PrintMessage("Failed to uninstall Python version $version", statusCode = 1)
        } else {
            echo("Successfully uninstalled Python $version")
        }
    }
}

class PackageCommand : CliktCommand(name = "package") {
    override fun help(context: Context) = "Manage PyPackPack packages within the project."

    init {
        subcommands(PackageAddCommand(), PackageRemoveCommand(), PackageSyncCommand(), PackageTreeCommand())
    }

    override fun run() = Unit
}

class PackageAddCommand : BaseDependencyCommand(name = "add") {
    override fun help(context: Context) =
        """
        Add a new package to the project, or add dependencies to an existing package.
        
        If no dependencies are specified, creates a new package.
        If dependencies are specified, adds them to the package.
        """.trimIndent()

    val packageName by argument(help = "Package name")
    val dependencies by argument(help = "Dependencies to add to the package").multiple()
    val targets by option("--target", help = "Target platforms (comma-separated)").split(",")

    override fun run() {
        val middleware = currentContext.findObject<MiddlewareInterface>()!!
        val term = currentContext.terminal

        if (dependencies.isEmpty()) {
            // Add new package
            if (!validatePackageName(packageName)) {
                throw PrintMessage("Invalid package name format", statusCode = 1)
            }

            val crossEnv = middleware.getCrossEnv()
            val success =
                term.runWithProgress("Adding package '$packageName'...") {
                    crossEnv.addPackage(packageName)
                }

            if (!success) {
                throw PrintMessage("Failed to add package '$packageName'", statusCode = 1)
            } else {
                echo("Successfully added package '$packageName'")
            }
        } else {
            // Add dependency to package
            val success =
                term.runWithProgress("Adding dependencies to package '$packageName'...") {
                    middleware.addDependencies(packageName, dependencies, targets, getExtraArgs().ifEmpty { null })
                }

            if (!success) {
                throw PrintMessage("Failed to add dependencies to package '$packageName'", statusCode = 1)
            } else {
                echo("Successfully added dependencies to package '$packageName'")
            }
        }
    }
}

class PackageRemoveCommand : BaseDependencyCommand(name = "remove") {
    override fun help(context: Context) =
        """
        Remove a package from the project, or remove dependencies from a package.
        
        If no dependencies are specified, removes the entire package.
        If dependencies are specified, removes them from the package.
        """.trimIndent()

    val packageName by argument(help = "Package name")
    val dependencies by argument(help = "Dependencies to remove from the package").multiple()
    val targets by option("--target", help = "Target platforms (comma-separated)").split(",")

    override fun run() {
        val middleware = currentContext.findObject<MiddlewareInterface>()!!
        val term = currentContext.terminal

        if (dependencies.isEmpty()) {
            // Remove package
            val crossEnv = middleware.getCrossEnv()
            val success =
                term.runWithProgress("Removing package '$packageName'...") {
                    crossEnv.removePackage(packageName)
                }

            if (!success) {
                throw PrintMessage("Failed to remove package '$packageName'", statusCode = 1)
            } else {
                echo("Successfully removed package '$packageName'")
            }
        } else {
            // Remove dependency from package
            val success =
                term.runWithProgress("Removing dependencies from package '$packageName'...") {
                    middleware.removeDependencies(packageName, dependencies, targets, getExtraArgs().ifEmpty { null })
                }

            if (!success) {
                throw PrintMessage("Failed to remove dependencies from package '$packageName'", statusCode = 1)
            } else {
                echo("Successfully removed dependencies from package '$packageName'")
            }
        }
    }
}

class PackageSyncCommand : BaseDependencyCommand(name = "sync") {
    override fun help(context: Context) = "Synchronize dependencies for a specific package."

    val packageName by argument(help = "Package name")
    val targets by option("--target", help = "Target platforms (comma-separated)").split(",")

    override fun run() {
        val middleware = currentContext.findObject<MiddlewareInterface>()!!
        val term = currentContext.terminal

        val success =
            term.runWithProgress("Synchronizing dependencies for package '$packageName'...") {
                middleware.syncDependencies(packageName, targets, getExtraArgs().ifEmpty { null })
            }

        if (!success) {
            throw PrintMessage("Failed to synchronize dependencies for package '$packageName'", statusCode = 1)
        } else {
            echo("Successfully synchronized dependencies for package '$packageName'")
        }
    }
}

class PackageTreeCommand : BaseDependencyCommand(name = "tree") {
    override fun help(context: Context) = "Show the dependency tree for a specific package."

    val packageName by argument(help = "Package name")
    val targets by option("--target", help = "Target platforms (comma-separated)").split(",")

    override fun run() {
        val middleware = currentContext.findObject<MiddlewareInterface>()!!

        if (!middleware.showDependencyTree(packageName, targets, getExtraArgs().ifEmpty { null })) {
            throw PrintMessage("Failed to show dependency tree for package '$packageName'", statusCode = 1)
        }
    }
}

class TargetCommand : CliktCommand(name = "target") {
    override fun help(context: Context) = "Manage target platforms for packages."

    init {
        subcommands(TargetListCommand(), TargetAddCommand(), TargetRemoveCommand())
    }

    override fun run() = Unit
}

class TargetListCommand : CliktCommand(name = "list") {
    override fun help(context: Context) = "List all supported target platforms."

    override fun run() {
        echo("Possible values:")
        Platforms.SUPPORTED_TARGETS.forEach { target ->
            echo("  - $target")
        }
    }
}

class TargetAddCommand : CliktCommand(name = "add") {
    override fun help(context: Context) = "Add a target platform to a package."

    val targetName by argument(help = "Target platform name")
    val packageName by argument(help = "Package name")

    override fun run() {
        if (!validatePackageName(packageName)) {
            throw PrintMessage("Invalid package name format", statusCode = 1)
        }

        val middleware = currentContext.findObject<MiddlewareInterface>()!!
        val crossEnv = middleware.getCrossEnv()

        if (!crossEnv.addTarget(packageName, listOf(targetName))) {
            throw PrintMessage("Failed to add target '$targetName' to package '$packageName'", statusCode = 1)
        } else {
            echo("Successfully added target '$targetName' to package '$packageName'")
        }
    }
}

class TargetRemoveCommand : CliktCommand(name = "remove") {
    override fun help(context: Context) = "Remove a target platform from a package."

    val targetName by argument(help = "Target platform name")
    val packageName by argument(help = "Package name")

    override fun run() {
        if (!validatePackageName(packageName)) {
            throw PrintMessage("Invalid package name format", statusCode = 1)
        }

        val middleware = currentContext.findObject<MiddlewareInterface>()!!
        val crossEnv = middleware.getCrossEnv()

        if (!crossEnv.removeTarget(packageName, listOf(targetName))) {
            throw PrintMessage("Failed to remove target '$targetName' from package '$packageName'", statusCode = 1)
        } else {
            echo("Successfully removed target '$targetName' from package '$packageName'")
        }
    }
}

/**
 * Dynamic command for package-specific operations: pypackpack <package_name> <operation> <args>
 */
class DynamicPackageCommand(
    private val packageName: String,
    private val operation: String,
) : BaseDependencyCommand(name = operation) {
    override fun help(context: Context) = "Perform $operation on package '$packageName'"

    val dependencies by argument(help = "Dependencies").multiple()
    val targets by option("--target", help = "Target platforms (comma-separated)").split(",")

    override fun run() {
        val middleware =
            currentContext.findOrSetObject {
                PackPackConfig.initialize()
                BaseInterface.create("cli").apply { initialize() }.getMiddleware()
            }
        val term = currentContext.terminal

        when (operation) {
            "add" -> {
                if (dependencies.isEmpty()) {
                    throw PrintMessage("No dependencies specified for package '$packageName'", statusCode = 1)
                }
                val success =
                    term.runWithProgress("Adding dependencies to package '$packageName'...") {
                        middleware.addDependencies(packageName, dependencies, targets, getExtraArgs().ifEmpty { null })
                    }
                if (!success) {
                    throw PrintMessage("Failed to add dependencies to package '$packageName'", statusCode = 1)
                } else {
                    echo("Successfully added dependencies to package '$packageName'")
                }
            }

            "remove" -> {
                if (dependencies.isEmpty()) {
                    throw PrintMessage("No dependencies specified for package '$packageName'", statusCode = 1)
                }
                val success =
                    term.runWithProgress("Removing dependencies from package '$packageName'...") {
                        middleware.removeDependencies(packageName, dependencies, targets, getExtraArgs().ifEmpty { null })
                    }
                if (!success) {
                    throw PrintMessage("Failed to remove dependencies from package '$packageName'", statusCode = 1)
                } else {
                    echo("Successfully removed dependencies from package '$packageName'")
                }
            }

            "sync" -> {
                val success =
                    term.runWithProgress("Synchronizing dependencies for package '$packageName'...") {
                        middleware.syncDependencies(packageName, targets, getExtraArgs().ifEmpty { null })
                    }
                if (!success) {
                    throw PrintMessage("Failed to synchronize dependencies for package '$packageName'", statusCode = 1)
                } else {
                    echo("Successfully synchronized dependencies for package '$packageName'")
                }
            }

            "tree" -> {
                if (!middleware.showDependencyTree(packageName, targets, getExtraArgs().ifEmpty { null })) {
                    throw PrintMessage("Failed to show dependency tree for package '$packageName'", statusCode = 1)
                }
            }

            else -> {
                throw PrintMessage("Unknown operation: $operation", statusCode = 1)
            }
        }
    }
}

fun main(args: Array<String>) {
    // Check if this is a dynamic package command: pypackpack <package_name> <command> [args]
    if (args.size >= 2) {
        val knownCommands =
            setOf(
                "init",
                "python",
                "package",
                "target",
                "add",
                "remove",
                "sync",
                "tree",
                "version",
                "help",
                "--help",
                "-h",
                "v",
                "-v",
                "--version",
            )
        val firstArg = args[0]
        val secondArg = args[1]

        // If first arg is not a known command and second arg is a package operation
        if (firstArg !in knownCommands && secondArg in setOf("add", "remove", "sync", "tree")) {
            // This is a dynamic package command
            val packageName = firstArg
            val remainingArgs = args.drop(2)

            return DynamicPackageCommand(packageName, secondArg).main(remainingArgs)
        }
    }

    // Normal command processing
    PyPackPackCommand().main(args)
}
