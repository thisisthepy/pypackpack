package org.thisisthepy.python.multiplatform.packpack.cli

import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.arguments.*
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.options.*
import kotlinx.coroutines.runBlocking

class VersionCommand : CliktCommand(name = "version") {
    override fun help(context: Context) = "Show version information"

    override fun aliases() =
        mapOf(
            "v" to listOf("version"),
            "--version" to listOf("version"),
            "-v" to listOf("version"),
        )

    private val pypackpackVersion: String = "0.1.0"

    override fun run() {
        echo("PyPackPack version $pypackpackVersion")
        val backend = requireMiddleware().getBackend()

        runBlocking {
            backend
                .getVersion()
                .onSuccess { version ->
                    echo("UV version: ${version.trim()}")
                }.onFailure {
                    echo("UV is installed but version check failed", err = true)
                }
        }
    }
}

class PythonOptions : OptionGroup(name = "Python options") {
    val pythonVersion by option("--python", help = "Python version to use", metavar = "<PYTHON>").default("")
}

class InitCommand : CliktCommand(name = "init") {
    override fun help(context: Context) =
        """
        Initialize a new PyPackPack project.

        Creates a pyproject.toml file with basic project structure and configuration.
        """.trimIndent()

    val path by argument(name = "PATH", help = "The path to use for the project/script").optional()

    val projectName by option("--name", help = "Project name", metavar = "<NAME>").default("")
    val `package` by option("--package", help = "Initialize as a package").flag()
    val pythonVersion by option("--python", help = "Python version to use", metavar = "<PYTHON>").default("")

    override fun run() {
        val middleware = requireMiddleware()
        val extraArgs =
            buildMap {
                if (projectName.isNotEmpty()) put("name", projectName)
                if (pythonVersion.isNotEmpty()) put("python", pythonVersion)
                if (`package`) put("package", "")
            }

        val result =
            currentContext.terminal.runWithProgress("Initializing project...") {
                middleware.initProject(path, null, extraArgs)
            }

        result
            .onSuccess {
                echo("Successfully initialized project at ${path ?: System.getProperty("user.dir")}")
                if (it.isNotEmpty()) {
                    echo(it)
                }
            }.onFailure {
                throw PrintMessage("Failed to initialize project: ${it.message}", statusCode = 1)
            }
    }
}
