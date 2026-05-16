package org.thisisthepy.python.multiplatform.packpack.cli

import com.github.ajalt.clikt.core.*
import org.thisisthepy.python.multiplatform.packpack.dependency.frontend.BaseInterface
import org.thisisthepy.python.multiplatform.packpack.dependency.frontend.FrontendType
import org.thisisthepy.python.multiplatform.packpack.dependency.middleware.BaseInterface as MiddlewareInterface

internal fun createCliMiddleware(): MiddlewareInterface = BaseInterface.create(FrontendType.CLI).apply { initialize() }.getMiddleware()

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
        configureCliTerminal()

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
