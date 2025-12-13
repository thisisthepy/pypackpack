package org.thisisthepy.python.multiplatform.packpack.commands

import kotlinx.coroutines.runBlocking
import org.thisisthepy.python.multiplatform.packpack.config.PackPackConfig
import org.thisisthepy.python.multiplatform.packpack.util.CliContext
import org.thisisthepy.python.multiplatform.packpack.util.ErrorHandler
import org.thisisthepy.python.multiplatform.packpack.util.runWithProgressBlocking
import java.io.File

/** Print help information */
fun printHelp() {
    val processPath =
        ProcessHandle
            .current()
            .info()
            .command()
            .orElse("Unknown")
    var processName = File(processPath).name
    if (processName == "java" ||
        processName == "javaw" ||
        processName == "javaw.exe" ||
        processName == "java.exe"
    ) {
        processName = "pypackpack"
    }

    println(
        """
        PyPackPack (PPP) - A multiplatform solution to distribute python project.

        Usage: $processName <command> [options]
               ppp <command> [options]

        Commands:
            init [<project name>] [<python version>]             Initialize a new project (default: python 3.13)
            package add <package name>                            Add a new package to the project
            package remove <package name>                         Remove a package from the project
            target add <target name> [<package name>]             Add target platform to a package (default: all)
            target remove <target name> [<package name>]          Remove target platform from a package (default: all)
            target list                                           List all supported target platforms
            
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

        """.trimIndent(),
    )
}

/** Print version information */
fun printVersion() {
    println("PyPackPack version 0.1.0")

    // Check UV version
    val backend = CliContext.middleware.getBackend()

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

/** Handle init command */
fun handleInit(args: Array<String>) {
    val projectName = if (args.size > 1 && !args[1].startsWith("-")) args[1] else null
    val pythonVersion = if (args.size > 2 && !args[2].startsWith("-")) args[2] else null

    val result =
        runWithProgressBlocking("Initializing project...") { _ ->
            CliContext.middleware.initProject(projectName ?: "", pythonVersion ?: "3.13")
        }

    if (!result) {
        ErrorHandler.operationFailed(
            operation = "initialize project",
            suggestions =
                listOf(
                    "Check if you have write permissions in the current directory",
                    "Verify UV is installed: pypackpack version",
                    "Make sure the directory is not already a PyPackPack project",
                    "Try a different project name or Python version",
                ),
        )
    } else {
        val projectDisplayName = projectName ?: "current directory"
        val versionDisplayName = pythonVersion ?: "default Python version"
        ErrorHandler.showSuccess(
            "Successfully initialized project '$projectDisplayName' with $versionDisplayName",
        )
    }
}
