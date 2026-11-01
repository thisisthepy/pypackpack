package org.thisisthepy.python.multiplatform.packpack.deploy.code

import org.thisisthepy.python.multiplatform.packpack.deploy.DeployType
import org.thisisthepy.python.multiplatform.packpack.deploy.UnimplementedDeployer

/**
 * FastTrack API deployment client: an app's `binary` or `patch` bundle. No FastTrack server exists yet,
 * so it refuses (`docs/SPEC.md` -> Package deployment).
 */
class FastTrackAPI :
    UnimplementedDeployer(
        DeployType.CODE,
        "An app's binary or patch bundle goes to FastTrack, and no FastTrack server exists yet. " +
            "PyPI takes a library's single or fat wheel only.",
    )
