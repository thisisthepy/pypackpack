package org.thisisthepy.python.multiplatform.packpack.deploy.code

import org.thisisthepy.python.multiplatform.packpack.deploy.DeployInterface
import org.thisisthepy.python.multiplatform.packpack.deploy.DeployRequest
import org.thisisthepy.python.multiplatform.packpack.deploy.DeployResult

/**
 * Code deployment. The destination follows the artefact (`docs/INTENT.md` §4.4): a library's wheel
 * (`single`, `fat`) goes to PyPI, an app's `binary` or `patch` goes to FastTrack.
 */
open class CodeAPI(
    private val pypi: DeployInterface = PyPIPublishAPI(),
    private val fastTrack: DeployInterface = FastTrackAPI(),
) : DeployInterface {
    override fun deploy(request: DeployRequest): Result<DeployResult> =
        when (request.bundleType.lowercase()) {
            in PYPI_BUNDLES -> pypi.deploy(request)
            in FAST_TRACK_BUNDLES -> fastTrack.deploy(request)
            else -> Result.failure(
                IllegalArgumentException(
                    "Unknown bundle type '${request.bundleType}' for code deployment. " +
                        "Use single or fat (PyPI), or binary or patch (FastTrack).",
                ),
            )
        }

    companion object {
        val PYPI_BUNDLES = setOf("single", "fat")
        val FAST_TRACK_BUNDLES = setOf("binary", "patch")
    }
}
