package org.thisisthepy.python.multiplatform.packpack.deploy

import org.thisisthepy.python.multiplatform.packpack.deploy.code.CodeAPI
import org.thisisthepy.python.multiplatform.packpack.deploy.resource.ResourceAPI
import org.thisisthepy.python.multiplatform.packpack.deploy.weight.WeightAPI
import java.io.File

/**
 * The deploy types listed in `docs/SPEC.md` -> "Package deployment".
 *
 * [id] is the CLI spelling for deploy categories (code/source, resource, weight).
 */
enum class DeployType(
    val id: String,
) {
    CODE("code"),
    RESOURCE("resource"),
    WEIGHT("weight"),
    ;

    companion object {
        fun fromId(id: String): DeployType? = entries.firstOrNull {
            it.id.equals(id, ignoreCase = true) || (it == CODE && id.equals("source", ignoreCase = true))
        }
    }
}

/**
 * One deployment job request.
 *
 * @param packageDir the ppp package directory
 * @param deployType deployment category (`code`, `resource`, `weight`)
 * @param bundleType `binary` / `single` / etc.
 * @param target platform target string
 * @param buildType `debug` / `release`
 * @param buildLevel `instant` / `bytecode` / `native` / `mixed`
 * @param destination target server or registry destination URL / identifier
 */
data class DeployRequest(
    val packageDir: File,
    val deployType: DeployType = DeployType.CODE,
    val bundleType: String = "binary",
    val target: String? = null,
    val buildType: String = "debug",
    val buildLevel: String = "instant",
    val destination: String? = null,
)

/**
 * What a deployer produced.
 */
data class DeployResult(
    val deployType: DeployType,
    val target: String?,
    val artifactFile: File? = null,
    val statusMessage: String = "",
)

/**
 * Factory pattern base interface for deployment.
 */
interface DeployInterface {
    fun deploy(request: DeployRequest): Result<DeployResult>

    companion object {
        fun create(type: DeployType): DeployInterface =
            when (type) {
                DeployType.CODE -> CodeAPI()
                DeployType.RESOURCE -> ResourceAPI()
                DeployType.WEIGHT -> WeightAPI()
            }
    }
}

/**
 * Shared base for deployers where deployment target server is not decided yet.
 *
 * Kept as a failed [Result] rather than a `TODO()` so that a caller which reaches one of these
 * through the factory gets the same error channel as a genuine deployment failure.
 */
abstract class UnimplementedDeployer(
    private val type: DeployType,
    private val reason: String = "Deployment target server is not specified or decided yet (e.g. PyPI vs FastTrack vs ResourceHub). See docs/SPEC.md -> Package deployment.",
) : DeployInterface {
    override fun deploy(request: DeployRequest): Result<DeployResult> =
        Result.failure(
            NotImplementedError(
                "Deploy type '${type.id}' is not implemented yet. $reason",
            ),
        )
}