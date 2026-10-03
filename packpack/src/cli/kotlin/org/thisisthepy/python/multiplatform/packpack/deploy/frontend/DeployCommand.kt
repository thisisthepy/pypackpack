package org.thisisthepy.python.multiplatform.packpack.deploy.frontend

import org.thisisthepy.python.multiplatform.packpack.utils.*
import org.thisisthepy.python.multiplatform.packpack.dependency.frontend.*
import org.thisisthepy.python.multiplatform.packpack.compile.frontend.*

import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.arguments.*
import com.github.ajalt.clikt.parameters.options.*
import org.thisisthepy.python.multiplatform.packpack.deploy.DeployInterface
import org.thisisthepy.python.multiplatform.packpack.deploy.DeployRequest
import org.thisisthepy.python.multiplatform.packpack.deploy.DeployType
import java.io.File

class DeployCommand : CliktCommand(name = "deploy") {
    override fun help(context: Context) = "Deploy a package artifact to an external deployment target."

    val packageName by argument(help = "Package name")
    val deployType by argument(help = "Deploy type (code/source, resource, weight)").default("code")
    val bundleType by argument(help = "Bundle type (default: binary)").optional()

    val type by option("--type", help = "Specify the build type (default: debug)").default("debug")
    val level by option("--level", help = "Specify the build level (default: instant)").default("instant")
    val target by option("--target", help = "Specify the build target")

    override fun run() {
        val dt = DeployType.fromId(deployType)
            ?: throw PrintMessage("Unknown deploy type '$deployType'. Supported types: code (source), resource, weight.", statusCode = 1)

        val deployer = DeployInterface.create(dt)
        val request = DeployRequest(
            packageDir = File(packageName),
            deployType = dt,
            bundleType = bundleType ?: "binary",
            target = target,
            buildType = type,
            buildLevel = level,
        )

        val result = deployer.deploy(request)
        result.onFailure {
            throw PrintMessage("Deploy failed for package '$packageName': ${it.message}", statusCode = 1)
        }
    }
}