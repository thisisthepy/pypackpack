package org.thisisthepy.python.multiplatform.packpack.bundle.patch

import org.thisisthepy.python.multiplatform.packpack.bundle.BundleType
import org.thisisthepy.python.multiplatform.packpack.bundle.UnimplementedBundler

/**
 * Wheel patch bundler (.whl.patch)
 *
 * Not implemented. See `docs/SPEC.md` -> "Bundle Type" for the intended behaviour.
 */
class WheelPatchBundler : UnimplementedBundler(BundleType.PATCH)
