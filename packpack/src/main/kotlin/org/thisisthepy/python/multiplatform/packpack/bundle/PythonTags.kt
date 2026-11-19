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
        private val ABI3T_EXT = Regex("""\.abi3t(?:-[^.]+)?\.(?:so|pyd)$""")
        private val ABI3_EXT = Regex("""\.abi3(?:-[^.]+)?\.(?:so|pyd)$""")
        private val FREE_THREADED_EXT = Regex("""\.(?:cpython-|cp)(\d+)t(?:-[^.]+)?\.(?:so|pyd)$""")
        private val REQUIRES_PYTHON_FLOOR = Regex("""(?:>=|==)\s*(\d+)\.(\d+)""")
        private val RUST_ABI3T = Regex("""^abi3t(?:-py3(\d+))?$""")
        private val RUST_ABI3 = Regex("""^abi3-py3(\d+)$""")

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

        /**
         * Derives wheel python and ABI tags from the payload's native extension filenames when
         * present, falling back to [of] when no stable-ABI (`abi3`/`abi3t`) or free-threaded
         * versioned extension (`cpython-<XY>t` / `cp<XY>t`) is found.
         *
         * On Windows, stable-ABI extensions are named `<name>.pyd` with no `.abi3` infix, so
         * [configuredRustAbi] (from `[tool.ppp.rust]`) disambiguates them (#147).
         */
        fun forPayload(
            pythonVersion: String?,
            payloadPaths: Iterable<String>,
            requiresPython: String? = null,
            configuredRustAbi: String? = null,
        ): PythonTags {
            val extensionNames =
                payloadPaths
                    .map { it.substringAfterLast('/') }
                    .filter { it.endsWith(".so") || it.endsWith(".pyd") }

            val freeThreadedMatch = extensionNames.firstNotNullOfOrNull { FREE_THREADED_EXT.find(it) }
            if (freeThreadedMatch != null) {
                if (pythonVersion != null) return of(pythonVersion)
                val xy = freeThreadedMatch.groupValues[1]
                return PythonTags("cp$xy", "cp${xy}t")
            }

            if (extensionNames.any { ABI3T_EXT.containsMatchIn(it) }) {
                val pyTag =
                    if (pythonVersion != null) {
                        of(pythonVersion).pythonTag
                    } else {
                        pythonTagFromRequiresPython(requiresPython) ?: of(null).pythonTag
                    }
                return PythonTags(pyTag, "abi3.abi3t")
            }

            if (extensionNames.any { ABI3_EXT.containsMatchIn(it) }) {
                val pyTag =
                    if (pythonVersion != null) {
                        of(pythonVersion).pythonTag
                    } else {
                        pythonTagFromRequiresPython(requiresPython) ?: of(null).pythonTag
                    }
                return PythonTags(pyTag, "abi3")
            }

            // Windows abi3/abi3t extensions are named `<name>.pyd` without an `.abi3` infix (#147).
            if (extensionNames.any { it.endsWith(".pyd") } && !configuredRustAbi.isNullOrBlank()) {
                val abiText = configuredRustAbi.trim()
                RUST_ABI3T.matchEntire(abiText)?.let { m ->
                    val pyTag =
                        if (pythonVersion != null) {
                            of(pythonVersion).pythonTag
                        } else {
                            "cp3${m.groupValues[1].ifEmpty { "15" }}"
                        }
                    return PythonTags(pyTag, "abi3.abi3t")
                }
                RUST_ABI3.matchEntire(abiText)?.let { m ->
                    val pyTag =
                        if (pythonVersion != null) {
                            of(pythonVersion).pythonTag
                        } else {
                            "cp3${m.groupValues[1]}"
                        }
                    return PythonTags(pyTag, "abi3")
                }
            }

            return of(pythonVersion)
        }

        private fun pythonTagFromRequiresPython(requiresPython: String?): String? {
            if (requiresPython.isNullOrBlank()) return null
            val match = REQUIRES_PYTHON_FLOOR.find(requiresPython) ?: return null
            val (major, minor) = match.destructured
            return "cp$major$minor"
        }

        /** Reads the configured `[tool.ppp.rust]` `abi` (package-level or first crate inline table), if any. */
        internal fun configuredRustAbi(editor: org.thisisthepy.python.multiplatform.packpack.utils.toml.TomlEditor): String? =
            (editor.getValue("tool.ppp.rust", "abi") as? org.thisisthepy.python.multiplatform.packpack.utils.toml.TomlValue.String)?.value
                ?: editor.getTableKeys("tool.ppp.rust")
                    .asSequence()
                    .mapNotNull { key ->
                        (editor.getValue("tool.ppp.rust", key) as? org.thisisthepy.python.multiplatform.packpack.utils.toml.TomlValue.InlineTable)
                            ?.pairs
                            ?.get("abi")
                            ?.let { it as? org.thisisthepy.python.multiplatform.packpack.utils.toml.TomlValue.String }
                            ?.value
                    }.firstOrNull()
    }
}
