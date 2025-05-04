package org.thisisthepy.python.multiplatform.packpack.util

import kotlinx.coroutines.runBlocking
import org.thisisthepy.python.multiplatform.packpack.dependency.frontend.BaseInterface
import org.thisisthepy.python.multiplatform.packpack.dependency.middleware.environment.DevEnv
import org.thisisthepy.python.multiplatform.packpack.dependency.middleware.environment.CrossEnv
import java.io.File

/**
 * Main entry point for CLI
 */
fun main(args: Array<String>) {
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
                println("Unknown command: $command")
                printHelp()
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
                println("UV version: ${result.output.trim()}")
            } else {
                println("UV is installed but version check failed")
            }
        } else {
            println("UV is not installed")
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
    
    val result = devEnv.initProject(projectName, pythonVersion)
    if (!result) {
        println("Failed to initialize project")
    }
}

/**
 * Handle python command
 */
fun handlePython(args: Array<String>) {
    if (args.size < 2) {
        println("Missing python subcommand")
        printHelp()
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
                println("Missing Python version")
                return
            }
            val pythonVersion = args[2]
            if (!devEnv.changePythonVersion(pythonVersion)) {
                println("Failed to change Python version")
            }
        }
        "list" -> {
            if (!devEnv.listPythonVersions()) {
                println("Failed to list Python versions")
            }
        }
        "find" -> {
            if (args.size < 3) {
                println("Missing Python version to find")
                return
            }
            val pythonVersion = args[2]
            if (!devEnv.findPythonVersion(pythonVersion)) {
                println("Failed to find Python version")
            }
        }
        "install" -> {
            if (args.size < 3) {
                println("Missing Python version to install")
                return
            }
            val pythonVersion = args[2]
            if (!devEnv.installPythonVersion(pythonVersion)) {
                println("Failed to install Python version")
            }
        }
        "uninstall" -> {
            if (args.size < 3) {
                println("Missing Python version to uninstall")
                return
            }
            val pythonVersion = args[2]
            if (!devEnv.uninstallPythonVersion(pythonVersion)) {
                println("Failed to uninstall Python version")
            }
        }
        else -> {
            println("Unknown python subcommand: $subcommand")
            printHelp()
        }
    }
}

/**
 * Handle package command
 */
fun handlePackage(args: Array<String>) {
    if (args.size < 2) {
        println("Missing package subcommand")
        printHelp()
        return
    }
    
    val subcommand = args[1].lowercase()
    
    when (subcommand) {
        "add" -> {
            if (args.size < 3) {
                println("Missing package name")
                return
            }
            val packageName = args[2]
            
            val frontend = BaseInterface.create("cli")
            frontend.initialize()
            val middleware = frontend.getMiddleware()
            val crossEnv = CrossEnv()
            crossEnv.initialize(middleware.getBackend())
            
            if (!crossEnv.addPackage(packageName)) {
                println("Failed to add package: $packageName")
            }
        }
        "remove" -> {
            if (args.size < 3) {
                println("Missing package name")
                return
            }
            val packageName = args[2]
            
            val frontend = BaseInterface.create("cli")
            frontend.initialize()
            val middleware = frontend.getMiddleware()
            val crossEnv = CrossEnv()
            crossEnv.initialize(middleware.getBackend())
            
            if (!crossEnv.removePackage(packageName)) {
                println("Failed to remove package: $packageName")
            }
        }
        else -> {
            // Handle package-specific commands
            if (args.size < 3) {
                println("Missing package command arguments")
                printHelp()
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
        println("Missing target subcommand")
        printHelp()
        return
    }
    
    val subcommand = args[1].lowercase()
    
    when (subcommand) {
        "add" -> {
            if (args.size < 3) {
                println("Missing target name")
                return
            }
            val targetName = args[2]
            val packageName = if (args.size > 3) args[3] else null
            
            if (packageName == null) {
                println("Missing package name")
                return
            }
            
            val frontend = BaseInterface.create("cli")
            frontend.initialize()
            val middleware = frontend.getMiddleware()
            val crossEnv = CrossEnv()
            crossEnv.initialize(middleware.getBackend())
            
            if (!crossEnv.addTargetPlatform(packageName, listOf(targetName))) {
                println("Failed to add target $targetName to package $packageName")
            }
        }
        "remove" -> {
            if (args.size < 3) {
                println("Missing target name")
                return
            }
            val targetName = args[2]
            val packageName = if (args.size > 3) args[3] else null
            
            if (packageName == null) {
                println("Missing package name")
                return
            }
            
            val frontend = BaseInterface.create("cli")
            frontend.initialize()
            val middleware = frontend.getMiddleware()
            val crossEnv = CrossEnv()
            crossEnv.initialize(middleware.getBackend())
            
            if (!crossEnv.removeTargetPlatform(packageName, listOf(targetName))) {
                println("Failed to remove target $targetName from package $packageName")
            }
        }
        else -> {
            println("Unknown target subcommand: $subcommand")
            printHelp()
        }
    }
}

/**
 * Handle add dependency command
 */
fun handleAddDependency(args: Array<String>) {
    if (args.size < 2) {
        println("Missing dependency name")
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
        println("No dependencies specified")
        return
    }
    
    val frontend = BaseInterface.create("cli")
    frontend.initialize()
    val middleware = frontend.getMiddleware()
    
    if (!middleware.addDependencies(null, dependencies, null, extraArgs.ifEmpty { null })) {
        println("Failed to add dependencies: ${dependencies.joinToString(", ")}")
    }
}

/**
 * Handle remove dependency command
 */
fun handleRemoveDependency(args: Array<String>) {
    if (args.size < 2) {
        println("Missing dependency name")
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
        println("No dependencies specified")
        return
    }
    
    val frontend = BaseInterface.create("cli")
    frontend.initialize()
    val middleware = frontend.getMiddleware()
    
    if (!middleware.removeDependencies(null, dependencies, null, extraArgs.ifEmpty { null })) {
        println("Failed to remove dependencies: ${dependencies.joinToString(", ")}")
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
            println("Unexpected argument: $arg")
            i++
        }
    }
    
    val frontend = BaseInterface.create("cli")
    frontend.initialize()
    val middleware = frontend.getMiddleware()
    
    if (!middleware.syncDependencies(null, null, extraArgs.ifEmpty { null })) {
        println("Failed to synchronize dependencies")
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
            println("Unexpected argument: $arg")
            i++
        }
    }
    
    val frontend = BaseInterface.create("cli")
    frontend.initialize()
    val middleware = frontend.getMiddleware()
    
    if (!middleware.showDependencyTree(null, null, extraArgs.ifEmpty { null })) {
        println("Failed to show dependency tree")
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
