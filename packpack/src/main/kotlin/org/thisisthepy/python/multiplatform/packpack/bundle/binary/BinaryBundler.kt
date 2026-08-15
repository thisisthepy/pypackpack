package org.thisisthepy.python.multiplatform.packpack.bundle.binary

import org.thisisthepy.python.multiplatform.packpack.bundle.BundleType
import org.thisisthepy.python.multiplatform.packpack.bundle.UnimplementedBundler

/**
 * Binary bundler for executable files (.exe, etc.)
 *
 * Not implemented. See `docs/SPEC.md` -> "Bundle Type" for the intended behaviour.
 */
class BinaryBundler : UnimplementedBundler(BundleType.BINARY)
