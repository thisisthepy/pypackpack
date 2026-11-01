package org.thisisthepy.python.multiplatform.packpack.deploy.code

import kotlinx.coroutines.runBlocking
import org.thisisthepy.python.multiplatform.packpack.dependency.backend.UVBackend
import org.thisisthepy.python.multiplatform.packpack.deploy.DeployInterface
import org.thisisthepy.python.multiplatform.packpack.deploy.DeployRequest
import org.thisisthepy.python.multiplatform.packpack.deploy.DeployResult
import org.thisisthepy.python.multiplatform.packpack.deploy.DeployType
import java.io.File

/**
 * PyPI publish API client (using UV): uploads the one wheel `pypackpack build <package> source
 * single|fat` left in `<package>/build/packpack/<bundle>/<build type>/<build level>` with `uv publish`.
 * No token is stored or passed: uv authenticates itself (`UV_PUBLISH_TOKEN`, trusted publishing).
 *
 * @param run runs `uv <arguments>` in a directory; pypackpack's own uv by default. A seam for tests.
 */
class PyPIPublishAPI(
    private val run: (arguments: List<String>, workingDir: File) -> Result<String> = { arguments, dir ->
        runBlocking { UVBackend().executeCommand(arguments, dir) }
    },
) : DeployInterface {
    override fun deploy(request: DeployRequest): Result<DeployResult> =
        runCatching {
            val bundle = request.bundleType.lowercase()
            require(bundle in CodeAPI.PYPI_BUNDLES) {
                "PyPI takes a single or fat wheel, not '${request.bundleType}'."
            }
            val dir = File(request.packageDir, "build/packpack/$bundle/${request.buildType}/${request.buildLevel}")
            val wheels = dir.listFiles { file -> file.isFile && file.name.endsWith(".whl") }.orEmpty().sortedBy { it.name }
            val build =
                "pypackpack build ${request.packageDir.name} source $bundle --type ${request.buildType} " +
                    "--level ${request.buildLevel}" + (request.target?.let { " --target $it" } ?: "")
            check(wheels.isNotEmpty()) { "No $bundle wheel in $dir to publish. Build it first: $build" }
            check(wheels.size == 1) {
                "More than one $bundle wheel in $dir (${wheels.joinToString { it.name }}); rebuild with --overwrite: $build --overwrite"
            }
            val wheel = wheels.single()
            val arguments = buildList {
                add("publish")
                request.destination?.let { addAll(listOf("--publish-url", it)) }
                if (request.dryRun) add("--dry-run")
                add(wheel.path)
            }
            val output = run(arguments, request.packageDir).getOrElse {
                throw IllegalStateException("uv publish failed for ${wheel.name}: ${it.message}", it)
            }
            DeployResult(
                deployType = DeployType.CODE,
                target = request.destination ?: "PyPI",
                artifactFile = wheel,
                statusMessage = (if (request.dryRun) "Dry run: " else "Published ") + "${wheel.name}\n$output",
            )
        }
}
