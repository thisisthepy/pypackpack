package org.thisisthepy.python.multiplatform.packpack.bundle.single

import org.thisisthepy.python.multiplatform.packpack.bundle.BundleType
import org.thisisthepy.python.multiplatform.packpack.bundle.UnimplementedBundler

/**
 * Single wheel bundler (.whl without dependencies)
 *
 * Not implemented. See `docs/SPEC.md` -> "Bundle Type" for the intended behaviour.
 */
class SingleWheelBundler : UnimplementedBundler(BundleType.SINGLE)
