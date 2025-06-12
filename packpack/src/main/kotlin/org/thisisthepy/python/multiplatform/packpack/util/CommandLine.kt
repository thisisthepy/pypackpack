package org.thisisthepy.python.multiplatform.packpack.util

import kotlinx.coroutines.runBlocking
import org.thisisthepy.python.multiplatform.packpack.dependency.frontend.BaseInterface
import org.thisisthepy.python.multiplatform.packpack.dependency.middleware.environment.DevEnv
import org.thisisthepy.python.multiplatform.packpack.dependency.middleware.environment.CrossEnv
import org.thisisthepy.python.multiplatform.packpack.di.KoinInitializer
import java.io.File

/**
 * Main entry point for CLI
 */
fun main(args: Array<String>) {
    // Initialize Koin DI container
    if (!KoinInitializer.isInitialized()) {
        KoinInitializer.initialize()
    }
    
    // Add shutdown hook to clean up Koin
    Runtime.getRuntime().addShutdownHook(Thread {
        KoinInitializer.shutdown()
    })
    
    if (args.isEmpty()) {
        printHelp()
        return
    }
    
    val command = args[0].lowercase()
    
    when (command) {
        "help", "--help", "-h" -> printHelp()
        "version", "--version", "-v" -> printVersion()
        "init" -> handleInit(args)
        "python" -> handlePython(args)
        "package" -> handlePackage(args)
        "target" -> handleTarget(args)
        "add" -> handleAddDependency(args)
        "remove" -> handleRemoveDependency(args)
        "sync" -> handleSyncDependency(args)
        "tree" -> handleTreeDependency(args)
        else -> {
            // Check if it's a package command
            val packageCommands = listOf("add", "remove", "sync", "tree")
            val subcommand = if (args.size > 1) args[1].lowercase() else ""
            
            if (subcommand in packageCommands) {
                // Parse as: pypackpack <package> <command> [args...]
                val newArgs = args.toMutableList()
                newArgs.add(0, "package") // Insert 'package' before the package name
                handlePackageCommand(newArgs.toTypedArray())
            } else {
                val availableCommands = listOf("help", "version", "init", "python", "package", "target", "add", "remove", "sync", "tree")
                ErrorHandler.unknownCommand(command, availableCommands)
            }
        }
    }
}

/**
 * Print help information
 */
fun printHelp() {
    val processPath = ProcessHandle.current().info().command().orElse("Unknown")
    var processName = File(processPath).name
    if (processName == "java" || processName == "javaw" || processName == "javaw.exe" || processName == "java.exe") {
        processName = "pypackpack"
    }

    println("""
        PyPackPack (PPP) - Python Multi-Platform Build & Deploy System

        Usage: $processName <command> [options]

        Commands:
            init init [<project name>] [<python version>]         Initialize a new project (default: python 3.11)
            package add <package name>                            Add a new package to the project
            package remove <package name>                         Remove a package from the project
            target add <target name> [<package name>]             Add target platform to a package (default: all)
            target remove <target name> [<package name>]          Remove target platform from a package (default: all)
            
            add <dependencies> [args]                             Add dependency to development environment
            remove <dependencies> [args]                          Remove dependency from development environment
            sync [args]                                           Synchronize dependencies in development environment
            tree [args]                                           Show dependency tree in development environment
            <package name> add <dependencies> [args]              Add dependency to a package
            <package name> remove <dependencies> [args]           Remove dependency from a package
            <package name> sync [args]                            Synchronize dependencies in a package
            <package name> tree [args]                            Show dependency tree in a package
            
            python use <python version>                           Switch to a different Python version
            python list                                           List available Python versions
            python find <python version>                          Find a specific Python version
            python install <python version>                       Install a specific Python version
            python uninstall <python version>                     Uninstall a specific Python version
            
            help, --help, -h                                      Show this help message
            version, --version, -v                                Show version information

        Examples:
            $processName init my_project 3.11
            $processName python use 3.13
            $processName package add my_package
            $processName target add windows my_package
            $processName add requests==2.25.1
            $processName remove requests
    """.trimIndent())
}

/**
 * Print version information
 */
fun printVersion() {
    println("PyPackPack version 0.1.0")
    
    // Check UV version
    val frontend = BaseInterface.create("cli")
    frontend.initialize()
    val middleware = frontend.getMiddleware()
    val backend = middleware.getBackend()
    
    runBlocking {
        if (backend.isToolInstalled()) {
            val result = backend.executeCommand(listOf(backend.toString(), "--version"))
            if (result.success) {
                ErrorHandler.showInfo("UV version: ${result.output.trim()}")
            } else {
                ErrorHandler.showWarning("UV is installed but version check failed")
            }
        } else {
            ErrorHandler.toolNotFound("UV")
        }
    }
}

/**
 * Handle init command
 */
fun handleInit(args: Array<String>) {
    val projectName = if (args.size > 1 && !args[1].startsWith("-")) args[1] else null
    val pythonVersion = if (args.size > 2 && !args[2].startsWith("-")) args[2] else null
    
    val frontend = BaseInterface.create("cli")
    frontend.initialize()
    val middleware = frontend.getMiddleware()
    val devEnv = DevEnv()
    devEnv.initialize(middleware.getBackend())
    
    val result = runWithProgressBlocking("Initializing project...") { indicator ->
        devEnv.initProject(projectName, pythonVersion)
    }
    
    if (!result) {
        ErrorHandler.operationFailed(
            operation = "initialize project",
            suggestions = listOf(
                "Check if you have write permissions in the current directory",
                "Verify UV is installed: pypackpack version",
                "Make sure the directory is not already a PyPackPack project",
                "Try a different project name or Python version"
            )
        )
    } else {
        val projectDisplayName = projectName ?: "current directory"
        val versionDisplayName = pythonVersion ?: "default Python version"
        ErrorHandler.showSuccess("Successfully initialized project '$projectDisplayName' with $versionDisplayName")
    }
}

/**
 * Handle python command
 */
fun handlePython(args: Array<String>) {
    if (args.size < 2) {
        ErrorHandler.missingArgument(
            argumentName = "subcommand",
            command = "python",
            example = "pypackpack python use 3.11"
        )
        return
    }
    
    val subcommand = args[1].lowercase()
    
    val frontend = BaseInterface.create("cli")
    frontend.initialize()
    val middleware = frontend.getMiddleware()
    val devEnv = DevEnv()
    devEnv.initialize(middleware.getBackend())
    
    when (subcommand) {
        "use" -> {
            if (args.size < 3) {
                ErrorHandler.missingArgument(
                    argumentName = "python version",
                    command = "python use",
                    example = "pypackpack python use 3.11"
                )
                return
            }
            val pythonVersion = args[2]
            
            // Validate Python version format
            if (!ErrorHandler.validatePythonVersion(pythonVersion)) {
                ErrorHandler.invalidArgument(
                    argumentName = "python version",
                    value = pythonVersion,
                    expectedFormat = "X.Y or X.Y.Z (e.g., 3.11 or 3.11.5)"
                )
                return
            }
            
            val success = runWithProgressBlocking("Changing Python version to $pythonVersion...") { indicator ->
                devEnv.changePythonVersion(pythonVersion)
            }
            
            if (!success) {
                ErrorHandler.operationFailed(
                    operation = "change Python version to $pythonVersion",
                    suggestions = listOf(
                        "Check if the Python version is available: pypackpack python find $pythonVersion",
                        "Install the Python version first: pypackpack python install $pythonVersion",
                        "Verify UV is installed and working: pypackpack version"
                    )
                )
            } else {
                ErrorHandler.showSuccess("Changed Python version to $pythonVersion")
            }
        }
        "list" -> {
            val success = runWithProgressBlocking("Fetching available Python versions...") { indicator ->
                devEnv.listPythonVersions()
            }
            
            if (!success) {
                ErrorHandler.operationFailed(
                    operation = "list Python versions",
                    suggestions = listOf(
                        "Verify UV is installed: pypackpack version",
                        "Check your internet connection",
                        "Try running: uv python list"
                    )
                )
            }
        }
        "find" -> {
            if (args.size < 3) {
                ErrorHandler.missingArgument(
                    argumentName = "python version",
                    command = "python find",
                    example = "pypackpack python find 3.11"
                )
                return
            }
            val pythonVersion = args[2]
            
            // Validate Python version format
            if (!ErrorHandler.validatePythonVersion(pythonVersion)) {
                ErrorHandler.invalidArgument(
                    argumentName = "python version",
                    value = pythonVersion,
                    expectedFormat = "X.Y or X.Y.Z (e.g., 3.11 or 3.11.5)"
                )
                return
            }
            
            if (!devEnv.findPythonVersion(pythonVersion)) {
                ErrorHandler.operationFailed(
                    operation = "find Python version $pythonVersion",
                    suggestions = listOf(
                        "Try a different version format (e.g., 3.11 instead of 3.11.0)",
                        "Check available versions: pypackpack python list",
                        "Install the version: pypackpack python install $pythonVersion"
                    )
                )
            }
        }
        "install" -> {
            if (args.size < 3) {
                ErrorHandler.missingArgument(
                    argumentName = "python version",
                    command = "python install",
                    example = "pypackpack python install 3.11"
                )
                return
            }
            val pythonVersion = args[2]
            
            // Validate Python version format
            if (!ErrorHandler.validatePythonVersion(pythonVersion)) {
                ErrorHandler.invalidArgument(
                    argumentName = "python version",
                    value = pythonVersion,
                    expectedFormat = "X.Y or X.Y.Z (e.g., 3.11 or 3.11.5)"
                )
                return
            }
            
            val success = runWithProgressBlocking("Installing Python $pythonVersion...") { indicator ->
                devEnv.installPythonVersion(pythonVersion)
            }
            
            if (!success) {
                ErrorHandler.operationFailed(
                    operation = "install Python version $pythonVersion",
                    suggestions = listOf(
                        "Check your internet connection",
                        "Verify UV is installed: pypackpack version",
                        "Check available versions: pypackpack python list",
                        "Try running manually: uv python install $pythonVersion"
                    )
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
                    example = "pypackpack python uninstall 3.11"
                )
                return
            }
            val pythonVersion = args[2]
            
            // Validate Python version format
            if (!ErrorHandler.validatePythonVersion(pythonVersion)) {
                ErrorHandler.invalidArgument(
                    argumentName = "python version",
                    value = pythonVersion,
                    expectedFormat = "X.Y or X.Y.Z (e.g., 3.11 or 3.11.5)"
                )
                return
            }
            
            val success = runWithProgressBlocking("Uninstalling Python $pythonVersion...") { indicator ->
                devEnv.uninstallPythonVersion(pythonVersion)
            }
            
            if (!success) {
                ErrorHandler.operationFailed(
                    operation = "uninstall Python version $pythonVersion",
                    suggestions = listOf(
                        "Check if the version is installed: pypackpack python list",
                        "Verify UV is installed: pypackpack version",
                        "Try running manually: uv python uninstall $pythonVersion"
                    )
                )
            } else {
                ErrorHandler.showSuccess("Successfully uninstalled Python $pythonVersion")
            }
        }
        else -> {
            val availableSubcommands = listOf("use", "list", "find", "install", "uninstall")
            ErrorHandler.unknownCommand("python $subcommand", availableSubcommands.map { "python $it" })
        }
    }
}

/**
 * Handle package command
 */
fun handlePackage(args: Array<String>) {
    if (args.size < 2) {
        ErrorHandler.missingArgument(
            argumentName = "subcommand",
            command = "package",
            example = "pypackpack package add my_package"
        )
        return
    }
    
    val subcommand = args[1].lowercase()
    
    when (subcommand) {
        "add" -> {
            if (args.size < 3) {
                ErrorHandler.missingArgument(
                    argumentName = "package name",
                    command = "package add",
                    example = "pypackpack package add my_package"
                )
                return
            }
            val packageName = args[2]
            
            // Validate package name
            if (!ErrorHandler.validatePackageName(packageName)) {
                ErrorHandler.invalidArgument(
                    argumentName = "package name",
                    value = packageName,
                    expectedFormat = "Valid Python package name (letters, numbers, underscores, starting with letter)"
                )
                return
            }
            
            val frontend = BaseInterface.create("cli")
            frontend.initialize()
            val middleware = frontend.getMiddleware()
            val crossEnv = CrossEnv()
            crossEnv.initialize(middleware.getBackend())
            
            val success = runWithProgressBlocking("Adding package '$packageName'...") { indicator ->
                crossEnv.addPackage(packageName)
            }
            
            if (!success) {
                ErrorHandler.operationFailed(
                    operation = "add package '$packageName'",
                    suggestions = listOf(
                        "Check if you're in a PyPackPack project directory",
                        "Verify the package name doesn't already exist",
                        "Make sure you have write permissions in the current directory"
                    )
                )
            } else {
                ErrorHandler.showSuccess("Successfully added package '$packageName'")
            }
        }
        "remove" -> {
            if (args.size < 3) {
                ErrorHandler.missingArgument(
                    argumentName = "package name",
                    command = "package remove",
                    example = "pypackpack package remove my_package"
                )
                return
            }
            val packageName = args[2]
            
            val frontend = BaseInterface.create("cli")
            frontend.initialize()
            val middleware = frontend.getMiddleware()
            val crossEnv = CrossEnv()
            crossEnv.initialize(middleware.getBackend())
            
            val success = runWithProgressBlocking("Removing package '$packageName'...") { indicator ->
                crossEnv.removePackage(packageName)
            }
            
            if (!success) {
                ErrorHandler.operationFailed(
                    operation = "remove package '$packageName'",
                    suggestions = listOf(
                        "Check if the package exists in the project",
                        "Verify you're in a PyPackPack project directory",
                        "Make sure you have write permissions"
                    )
                )
            } else {
                ErrorHandler.showSuccess("Successfully removed package '$packageName'")
            }
        }
        else -> {
            // Handle package-specific commands
            if (args.size < 3) {
                ErrorHandler.missingArgument(
                    argumentName = "command arguments",
                    command = "package",
                    example = "pypackpack package add my_package"
                )
                return
            }
            
            handlePackageCommand(args)
        }
    }
}

/**
 * Handle target command
 */
fun handleTarget(args: Array<String>) {
    if (args.size < 2) {
        ErrorHandler.missingArgument(
            argumentName = "subcommand",
            command = "target",
            example = "pypackpack target add windows my_package"
        )
        return
    }
    
    val subcommand = args[1].lowercase()
    
    when (subcommand) {
        "add" -> {
            if (args.size < 3) {
                ErrorHandler.missingArgument(
                    argumentName = "target name",
                    command = "target add",
                    example = "pypackpack target add windows my_package"
                )
                return
            }
            val targetName = args[2]
            val packageName = if (args.size > 3) args[3] else null
            
            if (packageName == null) {
                ErrorHandler.missingArgument(
                    argumentName = "package name",
                    command = "target add",
                    example = "pypackpack target add windows my_package"
                )
                return
            }
            
            // Validate package name
            if (!ErrorHandler.validatePackageName(packageName)) {
                ErrorHandler.invalidArgument(
                    argumentName = "package name",
                    value = packageName,
                    expectedFormat = "Valid Python package name (letters, numbers, underscores, starting with letter)"
                )
                return
            }
            
            val frontend = BaseInterface.create("cli")
            frontend.initialize()
            val middleware = frontend.getMiddleware()
            val crossEnv = CrossEnv()
            crossEnv.initialize(middleware.getBackend())
            
            if (!crossEnv.addTargetPlatform(packageName, listOf(targetName))) {
                ErrorHandler.operationFailed(
                    operation = "add target '$targetName' to package '$packageName'",
                    suggestions = listOf(
                        "Check if the package '$packageName' exists in the project",
                        "Verify you're in a PyPackPack project directory",
                        "Supported targets: windows, linux, macos, android, ios",
                        "Make sure you have write permissions"
                    )
                )
            } else {
                ErrorHandler.showSuccess("Successfully added target '$targetName' to package '$packageName'")
            }
        }
        "remove" -> {
            if (args.size < 3) {
                ErrorHandler.missingArgument(
                    argumentName = "target name",
                    command = "target remove",
                    example = "pypackpack target remove windows my_package"
                )
                return
            }
            val targetName = args[2]
            val packageName = if (args.size > 3) args[3] else null
            
            if (packageName == null) {
                ErrorHandler.missingArgument(
                    argumentName = "package name",
                    command = "target remove",
                    example = "pypackpack target remove windows my_package"
                )
                return
            }
            
            // Validate package name
            if (!ErrorHandler.validatePackageName(packageName)) {
                ErrorHandler.invalidArgument(
                    argumentName = "package name",
                    value = packageName,
                    expectedFormat = "Valid Python package name (letters, numbers, underscores, starting with letter)"
                )
                return
            }
            
            val frontend = BaseInterface.create("cli")
            frontend.initialize()
            val middleware = frontend.getMiddleware()
            val crossEnv = CrossEnv()
            crossEnv.initialize(middleware.getBackend())
            
            if (!crossEnv.removeTargetPlatform(packageName, listOf(targetName))) {
                ErrorHandler.operationFailed(
                    operation = "remove target '$targetName' from package '$packageName'",
                    suggestions = listOf(
                        "Check if the target '$targetName' exists for package '$packageName'",
                        "Verify the package '$packageName' exists in the project",
                        "Make sure you have write permissions"
                    )
                )
            } else {
                ErrorHandler.showSuccess("Successfully removed target '$targetName' from package '$packageName'")
            }
        }
        else -> {
            val availableSubcommands = listOf("add", "remove")
            ErrorHandler.unknownCommand("target $subcommand", availableSubcommands.map { "target $it" })
        }
    }
}

/**
 * Handle add dependency command
 */
fun handleAddDependency(args: Array<String>) {
    if (args.size < 2) {
        ErrorHandler.missingArgument(
            argumentName = "dependency name",
            command = "add",
            example = "pypackpack add requests==2.25.1"
        )
        return
    }
    
    val dependencies = mutableListOf<String>()
    val extraArgs = mutableMapOf<String, String>()
    
    var i = 1
    while (i < args.size) {
        val arg = args[i]
        if (arg.startsWith("--")) {
            // Handle flag with value
            val flagName = arg.substring(2)
            if (i + 1 < args.size && !args[i + 1].startsWith("--")) {
                extraArgs[flagName] = args[i + 1]
                i += 2
            } else {
                extraArgs[flagName] = ""
                i++
            }
        } else {
            // Regular dependency
            dependencies.add(arg)
            i++
        }
    }
    
    if (dependencies.isEmpty()) {
        ErrorHandler.showError(
            type = ErrorHandler.ErrorType.VALIDATION_ERROR,
            message = "No dependencies specified",
            suggestions = listOf("Provide at least one dependency name", "Example: pypackpack add requests numpy")
        )
        return
    }
    
    val frontend = BaseInterface.create("cli")
    frontend.initialize()
    val middleware = frontend.getMiddleware()
    
    val success = runWithProgressBlocking("Adding dependencies: ${dependencies.joinToString(", ")}...") { indicator ->
        middleware.addDependencies(null, dependencies, null, extraArgs.ifEmpty { null })
    }
    
    if (!success) {
        ErrorHandler.operationFailed(
            operation = "add dependencies: ${dependencies.joinToString(", ")}",
            suggestions = listOf(
                "Check your internet connection",
                "Verify the dependency names are correct",
                "Make sure you're in a PyPackPack project directory",
                "Try running: pypackpack sync"
            )
        )
    } else {
        ErrorHandler.showSuccess("Successfully added dependencies: ${dependencies.joinToString(", ")}")
    }
}

/**
 * Handle remove dependency command
 */
fun handleRemoveDependency(args: Array<String>) {
    if (args.size < 2) {
        ErrorHandler.missingArgument(
            argumentName = "dependency name",
            command = "remove",
            example = "pypackpack remove requests"
        )
        return
    }
    
    val dependencies = mutableListOf<String>()
    val extraArgs = mutableMapOf<String, String>()
    
    var i = 1
    while (i < args.size) {
        val arg = args[i]
        if (arg.startsWith("--")) {
            // Handle flag with value
            val flagName = arg.substring(2)
            if (i + 1 < args.size && !args[i + 1].startsWith("--")) {
                extraArgs[flagName] = args[i + 1]
                i += 2
            } else {
                extraArgs[flagName] = ""
                i++
            }
        } else {
            // Regular dependency
            dependencies.add(arg)
            i++
        }
    }
    
    if (dependencies.isEmpty()) {
        ErrorHandler.showError(
            type = ErrorHandler.ErrorType.VALIDATION_ERROR,
            message = "No dependencies specified",
            suggestions = listOf("Provide at least one dependency name", "Example: pypackpack remove requests numpy")
        )
        return
    }
    
    val frontend = BaseInterface.create("cli")
    frontend.initialize()
    val middleware = frontend.getMiddleware()
    
    val success = runWithProgressBlocking("Removing dependencies: ${dependencies.joinToString(", ")}...") { indicator ->
        middleware.removeDependencies(null, dependencies, null, extraArgs.ifEmpty { null })
    }
    
    if (!success) {
        ErrorHandler.operationFailed(
            operation = "remove dependencies: ${dependencies.joinToString(", ")}",
            suggestions = listOf(
                "Check if the dependencies are installed",
                "Verify the dependency names are correct",
                "Make sure you're in a PyPackPack project directory",
                "Try running: pypackpack tree"
            )
        )
    } else {
        ErrorHandler.showSuccess("Successfully removed dependencies: ${dependencies.joinToString(", ")}")
    }
}

/**
 * Handle sync dependency command
 */
fun handleSyncDependency(args: Array<String>) {
    val extraArgs = mutableMapOf<String, String>()
    
    var i = 1
    while (i < args.size) {
        val arg = args[i]
        if (arg.startsWith("--")) {
            // Handle flag with value
            val flagName = arg.substring(2)
            if (i + 1 < args.size && !args[i + 1].startsWith("--")) {
                extraArgs[flagName] = args[i + 1]
                i += 2
            } else {
                extraArgs[flagName] = ""
                i++
            }
        } else {
            // Unexpected argument
            ErrorHandler.showWarning("Unexpected argument ignored: $arg")
            i++
        }
    }
    
    val frontend = BaseInterface.create("cli")
    frontend.initialize()
    val middleware = frontend.getMiddleware()
    
    val success = runWithProgressBlocking("Synchronizing dependencies...") { indicator ->
        middleware.syncDependencies(null, null, extraArgs.ifEmpty { null })
    }
    
    if (!success) {
        ErrorHandler.operationFailed(
            operation = "synchronize dependencies",
            suggestions = listOf(
                "Check your internet connection",
                "Verify pyproject.toml exists and is valid",
                "Make sure you're in a PyPackPack project directory",
                "Try running: pypackpack add <dependency> to add missing dependencies"
            )
        )
    } else {
        ErrorHandler.showSuccess("Successfully synchronized dependencies")
    }
}

/**
 * Handle tree dependency command
 */
fun handleTreeDependency(args: Array<String>) {
    val extraArgs = mutableMapOf<String, String>()
    
    var i = 1
    while (i < args.size) {
        val arg = args[i]
        if (arg.startsWith("--")) {
            // Handle flag with value
            val flagName = arg.substring(2)
            if (i + 1 < args.size && !args[i + 1].startsWith("--")) {
                extraArgs[flagName] = args[i + 1]
                i += 2
            } else {
                extraArgs[flagName] = ""
                i++
            }
        } else {
            // Unexpected argument
            ErrorHandler.showWarning("Unexpected argument ignored: $arg")
            i++
        }
    }
    
    val frontend = BaseInterface.create("cli")
    frontend.initialize()
    val middleware = frontend.getMiddleware()
    
    if (!middleware.showDependencyTree(null, null, extraArgs.ifEmpty { null })) {
        ErrorHandler.operationFailed(
            operation = "show dependency tree",
            suggestions = listOf(
                "Make sure you're in a PyPackPack project directory",
                "Verify dependencies are installed: pypackpack sync",
                "Check if pyproject.toml exists and is valid"
            )
        )
    }
}

/**
 * Handle package command
 */
fun handlePackageCommand(args: Array<String>) {
    if (args.size < 3) {
        println("Missing package command arguments")
        printHelp()
        return
    }
    
    val packageName = args[1]
    val subcommand = args[2].lowercase()
    
    when (subcommand) {
        "add" -> {
            if (args.size < 4) {
                println("Missing dependency name")
                return
            }
            
            val dependencies = mutableListOf<String>()
            val targets = mutableListOf<String>()
            val extraArgs = mutableMapOf<String, String>()
            
            var i = 3
            var inTarget = false
            
            while (i < args.size) {
                val arg = args[i]
                if (arg == "--target") {
                    inTarget = true
                    i++
                } else if (arg.startsWith("--")) {
                    // End of target list if we were parsing targets
                    inTarget = false
                    
                    // Handle flag with value
                    val flagName = arg.substring(2)
                    if (i + 1 < args.size && !args[i + 1].startsWith("--") && args[i + 1] != "--target") {
                        extraArgs[flagName] = args[i + 1]
                        i += 2
                    } else {
                        extraArgs[flagName] = ""
                        i++
                    }
                } else {
                    if (inTarget) {
                        targets.add(arg)
                    } else {
                        dependencies.add(arg)
                    }
                    i++
                }
            }
            
            if (dependencies.isEmpty()) {
                println("No dependencies specified")
                return
            }
            
            val frontend = BaseInterface.create("cli")
            frontend.initialize()
            val middleware = frontend.getMiddleware()
            
            if (!middleware.addDependencies(packageName, dependencies, targets.ifEmpty { null }, extraArgs.ifEmpty { null })) {
                println("Failed to add dependencies: ${dependencies.joinToString(", ")} to package $packageName")
            }
        }
        "remove" -> {
            if (args.size < 4) {
                println("Missing dependency name")
                return
            }
            
            val dependencies = mutableListOf<String>()
            val targets = mutableListOf<String>()
            val extraArgs = mutableMapOf<String, String>()
            
            var i = 3
            var inTarget = false
            
            while (i < args.size) {
                val arg = args[i]
                if (arg == "--target") {
                    inTarget = true
                    i++
                } else if (arg.startsWith("--")) {
                    // End of target list if we were parsing targets
                    inTarget = false
                    
                    // Handle flag with value
                    val flagName = arg.substring(2)
                    if (i + 1 < args.size && !args[i + 1].startsWith("--") && args[i + 1] != "--target") {
                        extraArgs[flagName] = args[i + 1]
                        i += 2
                    } else {
                        extraArgs[flagName] = ""
                        i++
                    }
                } else {
                    if (inTarget) {
                        targets.add(arg)
                    } else {
                        dependencies.add(arg)
                    }
                    i++
                }
            }
            
            if (dependencies.isEmpty()) {
                println("No dependencies specified")
                return
            }
            
            val frontend = BaseInterface.create("cli")
            frontend.initialize()
            val middleware = frontend.getMiddleware()
            
            if (!middleware.removeDependencies(packageName, dependencies, targets.ifEmpty { null }, extraArgs.ifEmpty { null })) {
                println("Failed to remove dependencies: ${dependencies.joinToString(", ")} from package $packageName")
            }
        }
        "sync" -> {
            val targets = mutableListOf<String>()
            val extraArgs = mutableMapOf<String, String>()
            
            var i = 3
            var inTarget = false
            
            while (i < args.size) {
                val arg = args[i]
                if (arg == "--target") {
                    inTarget = true
                    i++
                } else if (arg.startsWith("--")) {
                    // End of target list if we were parsing targets
                    inTarget = false
                    
                    // Handle flag with value
                    val flagName = arg.substring(2)
                    if (i + 1 < args.size && !args[i + 1].startsWith("--") && args[i + 1] != "--target") {
                        extraArgs[flagName] = args[i + 1]
                        i += 2
                    } else {
                        extraArgs[flagName] = ""
                        i++
                    }
                } else {
                    if (inTarget) {
                        targets.add(arg)
                    } else {
                        println("Unexpected argument: $arg")
                    }
                    i++
                }
            }
            
            val frontend = BaseInterface.create("cli")
            frontend.initialize()
            val middleware = frontend.getMiddleware()
            
            if (!middleware.syncDependencies(packageName, targets.ifEmpty { null }, extraArgs.ifEmpty { null })) {
                println("Failed to synchronize dependencies for package $packageName")
            }
        }
        "tree" -> {
            val targets = mutableListOf<String>()
            val extraArgs = mutableMapOf<String, String>()
            
            var i = 3
            var inTarget = false
            
            while (i < args.size) {
                val arg = args[i]
                if (arg == "--target") {
                    inTarget = true
                    i++
                } else if (arg.startsWith("--")) {
                    // End of target list if we were parsing targets
                    inTarget = false
                    
                    // Handle flag with value
                    val flagName = arg.substring(2)
                    if (i + 1 < args.size && !args[i + 1].startsWith("--") && args[i + 1] != "--target") {
                        extraArgs[flagName] = args[i + 1]
                        i += 2
                    } else {
                        extraArgs[flagName] = ""
                        i++
                    }
                } else {
                    if (inTarget) {
                        targets.add(arg)
                    } else {
                        println("Unexpected argument: $arg")
                    }
                    i++
                }
            }
            
            val frontend = BaseInterface.create("cli")
            frontend.initialize()
            val middleware = frontend.getMiddleware()
            
            if (!middleware.showDependencyTree(packageName, targets.ifEmpty { null }, extraArgs.ifEmpty { null })) {
                println("Failed to show dependency tree for package $packageName")
            }
        }
        else -> {
            println("Unknown package subcommand: $subcommand")
            printHelp()
        }
    }
}
