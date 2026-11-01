package org.thisisthepy.python.multiplatform.packpack.bundle

/**
 * The PEP 425 python tag and ABI tag of the interpreter a wheel is built for, derived from the
 * target interpreter's version and build (`docs/SPEC.md`, "Wheel tags").
 *
 * A GIL build of 3.15 is `cp315`/`cp315`; a free-threaded build is `cp315`/`cp315t`: PEP 703 adds
 * the `t` to the ABI tag only, as in `numpy-2.1.0-cp313-cp313t-...whl`.
 */
data class PythonTags(
    val pythonTag: String,
    val abiTag: String,
) {
    companion object {
        /** The ecosystem interpreter (free-threaded 3.15), used when a request names no version. */
        const val DEFAULT_PYTHON_VERSION = "3.15t"

        private val VERSION = Regex("""(\d+)\.(\d+)(?:\.\d+)?(t?)""")

        /**
         * [pythonVersion] is `X.Y`, `X.Y.Z`, optionally followed by `t` for the free-threaded build
         * (`3.15`, `3.15.0`, `3.15t`, `3.15.0t`); `null` means [DEFAULT_PYTHON_VERSION].
         * Anything else is refused with an [IllegalArgumentException].
         */
        fun of(pythonVersion: String?): PythonTags {
            val text = (pythonVersion ?: DEFAULT_PYTHON_VERSION).trim()
            val match =
                VERSION.matchEntire(text)
                    ?: throw IllegalArgumentException(
                        "Unsupported Python version '$text' for wheel tags. Expected X.Y, X.Y.Z, X.Yt or X.Y.Zt (for example 3.15t).",
                    )
            val (major, minor, freeThreaded) = match.destructured
            val pythonTag = "cp$major$minor"
            return PythonTags(pythonTag, pythonTag + freeThreaded)
        }
    }
}
