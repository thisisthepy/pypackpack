package org.thisisthepy.python.multiplatform.packpack.utils

import org.thisisthepy.python.multiplatform.packpack.dependency.frontend.*
import org.thisisthepy.python.multiplatform.packpack.compile.frontend.*
import org.thisisthepy.python.multiplatform.packpack.deploy.frontend.*

import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.options.versionOption
import org.thisisthepy.python.multiplatform.packpack.dependency.frontend.FrontendInterface
import org.thisisthepy.python.multiplatform.packpack.dependency.frontend.FrontendType
import org.thisisthepy.python.multiplatform.packpack.dependency.middleware.MiddlewareInterface

internal fun createCliMiddleware(): MiddlewareInterface = FrontendInterface.create(FrontendType.CLI).apply { initialize() }.getMiddleware()

class PyPackPackCommand : CliktCommand(name = "pypackpack") {
    override fun help(context: Context) =
        """
        PyPackPack (PPP) - A multiplatform solution to distribute python project.

        Use 'pypackpack <command> --help' for more information about a command.

        You can also use: pypackpack <package_name> <command> [args]
        to execute commands on specific packages.
        """.trimIndent()

    // Aliases of this command's subcommands (Clikt applies `aliases()` to the subcommand token).
    override fun aliases() =
        mapOf(
            "ppp" to listOf("pypackpack"),
            "v" to listOf("version"),
        )

    init {
        configureCliTerminal()
        // `pypackpack --version` / `-v` (and `ppp --version`, the same binary): the first line of
        // `pypackpack version`, printed before any middleware or uv is touched.
        versionOption(BuildInfo.version, names = setOf("--version", "-v")) { "PyPackPack version $it" }

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
            BuildCommand(),
            DeployCommand(),
        )
    }

    override fun run() {
        currentContext.obj = createCliMiddleware()
    }
}

private val KNOWN_COMMANDS =
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
        "build",
        "deploy",
        "help",
        "--help",
        "-h",
        "v",
        "-v",
        "--version",
    )

private val DYNAMIC_PACKAGE_OPERATIONS = setOf("add", "remove", "sync", "tree", "target")

fun main(args: Array<String>) {
    if (args.size >= 2) {
        val firstArg = args[0]
        val secondArg = args[1]

        if (firstArg !in KNOWN_COMMANDS && secondArg in DYNAMIC_PACKAGE_OPERATIONS) {
            val remainingArgs = args.drop(2)
            return when (secondArg) {
                "target" -> TargetCommand(firstArg).main(remainingArgs)
                else -> DynamicPackageCommand(firstArg, secondArg).main(remainingArgs)
            }
        }
    }

    PyPackPackCommand().main(args)
}
