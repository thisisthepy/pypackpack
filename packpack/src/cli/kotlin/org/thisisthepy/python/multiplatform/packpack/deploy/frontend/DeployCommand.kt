package org.thisisthepy.python.multiplatform.packpack.deploy.frontend

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.default
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import org.thisisthepy.python.multiplatform.packpack.deploy.DeployInterface
import org.thisisthepy.python.multiplatform.packpack.deploy.DeployRequest
import org.thisisthepy.python.multiplatform.packpack.deploy.DeployType
import org.thisisthepy.python.multiplatform.packpack.utils.findWorkspaceRoot
import org.thisisthepy.python.multiplatform.packpack.utils.resolveWorkspacePackageDir
import java.io.File

/**
 * `deploy <package> [code|resource|weight] [<bundle type>]` (`docs/SPEC.md` *Package deployment*). Today
 * only `code single|fat` works: the wheel goes to PyPI through `uv publish`. The seams ([deployerFor],
 * [workspaceDir]) are for tests.
 */
class DeployCommand(
    private val deployerFor: (DeployType) -> DeployInterface = DeployInterface::create,
    private val workspaceDir: File = File(System.getProperty("user.dir")),
) : CliktCommand(name = "deploy") {
    override fun help(context: Context) = "Deploy a package artifact to an external deployment target."

    val packageName by argument(help = "Package name")
    val deployType by argument(help = "Deploy type (code/source, resource, weight)").default("code")
    val bundleType by argument(help = "Bundle type: single or fat go to PyPI; binary (default) or patch to FastTrack").optional()

    val type by option("--type", help = "Specify the build type (default: debug)").default("debug")
    val level by option("--level", help = "Specify the build level (default: instant)").default("instant")
    val target by option("--target", help = "Specify the build target")
    val publishUrl by option("--publish-url", help = "Upload URL for PyPI deploys (uv publish --publish-url), e.g. TestPyPI")
    val dryRun by option("--dry-run", help = "Go through the upload without publishing (uv publish --dry-run)").flag()

    override fun run() {
        val dt = DeployType.fromId(deployType)
            ?: fail("Unknown deploy type '$deployType'. Supported types: code (source), resource, weight.")
        val packageDir = runCatching { resolveWorkspacePackageDir(findWorkspaceRoot(workspaceDir), packageName) }
            .getOrElse { fail("Deploy failed for package '$packageName': ${it.message}") }

        val request = DeployRequest(
            packageDir = packageDir,
            deployType = dt,
            bundleType = bundleType ?: "binary",
            target = target,
            buildType = type,
            buildLevel = level,
            destination = publishUrl,
            dryRun = dryRun,
        )
        val result = deployerFor(dt).deploy(request)
            .getOrElse { fail("Deploy failed for package '$packageName': ${it.message}") }
        echo(result.statusMessage)
    }

    private fun fail(message: String): Nothing = throw PrintMessage(message, statusCode = 1, printError = true)
}
