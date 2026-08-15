package org.thisisthepy.python.multiplatform.packpack.bundle.fat

import org.thisisthepy.python.multiplatform.packpack.bundle.BundleType
import org.thisisthepy.python.multiplatform.packpack.bundle.UnimplementedBundler

/**
 * Fat wheel bundler (.whl with all dependencies)
 *
 * Not implemented. See `docs/SPEC.md` -> "Bundle Type" for the intended behaviour.
 */
class FatWheelBundler : UnimplementedBundler(BundleType.FAT)
