package org.thisisthepy.python.multiplatform.packpack.dependency.backend

/**
 * One prebuilt CPython archive `installPython` can fetch for one (version, target) pair.
 *
 * @property sha256 the pinned digest the download must match before anything is extracted.
 * @property provenance where [sha256] was copied from, so it can be re-checked.
 * @property stripComponents / [prefixFilter] how the archive's tree maps onto the install directory
 *   (passed to `utils/Archive.kt`'s `extractArchive`).
 */
data class PythonDistribution(
    val version: String,
    val target: String,
    val url: String,
    val sha256: String,
    val provenance: String,
    val stripComponents: Int,
    val prefixFilter: String?,
) {
    /** The archive's file name: the last path segment of [url]. */
    val fileName: String get() = url.substringAfterLast('/')
}

/**
 * The interpreters `installPython` knows, keyed by exact version and canonical target triple
 * (`docs/SPEC.md`, "Installing a ppp Python distribution").
 *
 * The runtime is CPython 3.15t, free-threaded only (`docs/SPEC.md` Scope decisions, python-multiplatform
 * #158). Desktop: python-build-standalone `20261013`'s free-threaded archives, version `3.15.0t`
 * (abi3t, PEP 803). Every digest was computed with `shasum -a 256` on the downloaded archive and equals
 * the release's `SHA256SUMS`.
 *
 * Android and iOS are the one GIL exception: python.org's 3.15.0 archives, version `3.15.0`, until
 * python-multiplatform #163 publishes free-threaded mobile builds. Then [android315] and [ios315] take
 * that build's URL and digest with `freeThreaded = true`, and the exception (and this paragraph) goes.
 */
object PythonDistributions {
    private const val PBS = "https://github.com/astral-sh/python-build-standalone/releases/download"
    private const val PBS_RELEASE_3_15_0 = "20261013"
    private const val PYTHON_ORG = "https://www.python.org/ftp/python"

    private const val SUMS_3_15_0 = "python-build-standalone $PBS_RELEASE_3_15_0 SHA256SUMS"
    private const val PYTHON_ORG_DIGEST =
        "shasum -a 256 of the python.org download (python.org publishes no SHA256SUMS; it signs with Sigstore)"

    /** `X.Y` shorthands accepted on the command line; both name the free-threaded build. */
    val VERSION_ALIASES: Map<String, String> = mapOf(
            "3.15" to "3.15.0t",
            "3.15t" to "3.15.0t",
        )

    /** 3.15.0t desktop: python-build-standalone's free-threaded `install_only` archive. */
    private fun pbs315(
        target: String,
        sha256: String,
    ) = PythonDistribution(
        version = "3.15.0t",
        target = target,
        url = "$PBS/$PBS_RELEASE_3_15_0/cpython-3.15.0+$PBS_RELEASE_3_15_0-$target-freethreaded-install_only.tar.gz",
        sha256 = sha256,
        provenance = "$SUMS_3_15_0, recomputed with shasum -a 256 on the downloaded archive",
        // install_only archives hold a single top-level `python/` (bin/, lib/, include/).
        stripComponents = 1,
        prefixFilter = "python/",
    )

    /**
     * 3.15.0 for Android. [freeThreaded] is `false` while python.org's GIL tarball is the source (the
     * SPEC exception); python-multiplatform #163's build passes its own [url] with `true`.
     */
    private fun android315(
        target: String,
        sha256: String,
        freeThreaded: Boolean = false,
        url: String = "$PYTHON_ORG/3.15.0/python-3.15.0-$target.tar.gz",
        provenance: String = PYTHON_ORG_DIGEST,
    ) = PythonDistribution(
        version = if (freeThreaded) "3.15.0t" else "3.15.0",
        target = target,
        url = url,
        sha256 = sha256,
        provenance = provenance,
        // python.org's Android tarball is rooted at `./` (README.md, android.py, prefix/, ...).
        stripComponents = 1,
        prefixFilter = "./",
    )

    /** 3.15.0 for iOS: one XCframework holds every slice. [freeThreaded] as for [android315]. */
    private fun ios315(
        target: String,
        sha256: String = "68e98ab35cc0643ba3a227a8c7768da45c2a5e67dbe1bc26598537e96680fd19",
        freeThreaded: Boolean = false,
        url: String = "$PYTHON_ORG/3.15.0/python-3.15.0-iOS-XCframework.tar.gz",
        provenance: String = PYTHON_ORG_DIGEST,
    ) = PythonDistribution(
        version = if (freeThreaded) "3.15.0t" else "3.15.0",
        target = target,
        url = url,
        sha256 = sha256,
        provenance = provenance,
        stripComponents = 0,
        prefixFilter = null,
    )

    /** Every known (version, target) pair. */
    val ALL: List<PythonDistribution> =
        listOf(
            pbs315("aarch64-apple-darwin", "9b982a1644405461bb0c96efecff18538e259c3ac253056f25a423fdce8fc069"),
            pbs315("x86_64-apple-darwin", "40c0033260b35a88365dad616d22bfbf182e894de866271b0b290e3c87e7468b"),
            pbs315("x86_64-unknown-linux-gnu", "f8bf1b9cfe976fc415f656b3ae42a179e32ba3c7f34fa254ac0217948c43b34a"),
            pbs315("x86_64-pc-windows-msvc", "3fd540d407f34dfcec3c51db57b84e35264cda209be0c4d43e113e4e67d0bbb5"),
            pbs315("aarch64-unknown-linux-gnu", "05717335ecaa89b0dac64833c9c7c61a70e6db291e0a2c7ca707c80bfa20b0f5"),
            pbs315("aarch64-pc-windows-msvc", "53cbedb81dce9885273da9117f2652a7b1cf31afe1b63cbb11f01971658c8d85"),
            android315("aarch64-linux-android", "571e4cf4e0aee3d1ab54a52ca3d4e6845b85ec0bb327f7debfb873172f445dab"),
            android315("x86_64-linux-android", "2413d51948d612cf42baefba25295bb83c2ed84df5726af6fe5bde2dec5d87e7"),
            ios315("arm64-apple-ios"),
            ios315("arm64-apple-ios-simulator"),
            ios315("x86_64-apple-ios-simulator"),
        )

    /** The pairs `installPython` will install, as `version/target`, in [ALL]'s order. */
    val SUPPORTED: List<String> = ALL.map { "${it.version}/${it.target}" }

    /** [version] with an `X.Y` alias expanded ("3.15" and "3.15t" -> "3.15.0t"); anything else unchanged. */
    fun canonicalVersion(version: String): String = VERSION_ALIASES[version] ?: version

    /**
     * The pinned distribution for ([version], [canonicalTarget]), or a failed [Result] that names
     * the pair and lists [SUPPORTED].
     */
    fun resolve(
        version: String,
        canonicalTarget: String,
    ): Result<PythonDistribution> {
        val exact = canonicalVersion(version)
        val known = ALL.firstOrNull { it.version == exact && it.target == canonicalTarget }
        return known?.let { Result.success(it) }
            ?: Result.failure(
                IllegalArgumentException(
                    "Python $version is not available for $canonicalTarget. Supported: ${SUPPORTED.joinToString(", ")}",
                ),
            )
    }
}
