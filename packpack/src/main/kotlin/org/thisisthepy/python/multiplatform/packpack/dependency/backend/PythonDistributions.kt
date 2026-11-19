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
 * Android and iOS use `thisisthepy/python-multiplatform`'s `cpython-3.15.0t-mobile` release
 * (`python-multiplatform` #163, #328), version `3.15.0t`.
 */
object PythonDistributions {
    const val MOBILE_RELEASE_TAG: String = "cpython-3.15.0t-mobile"

    private const val PBS = "https://github.com/astral-sh/python-build-standalone/releases/download"
    private const val PBS_RELEASE_3_15_0 = "20261013"
    private const val PMP_MOBILE_3_15_0T =
        "https://github.com/thisisthepy/python-multiplatform/releases/download/cpython-3.15.0t-mobile"
    private const val PMP_MOBILE_SUMS_3_15_0T =
        "thisisthepy/python-multiplatform cpython-3.15.0t-mobile SHA256SUMS"

    private const val SUMS_3_15_0 = "python-build-standalone $PBS_RELEASE_3_15_0 SHA256SUMS"

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
     * 3.15.0t for Android. Default [freeThreaded] is `true`, fetching from
     * `thisisthepy/python-multiplatform`'s `cpython-3.15.0t-mobile` release.
     */
    private fun android315(
        target: String,
        sha256: String,
        freeThreaded: Boolean = true,
        url: String = "$PMP_MOBILE_3_15_0T/python-3.15.0t-$target.tar.gz",
        provenance: String = PMP_MOBILE_SUMS_3_15_0T,
    ) = PythonDistribution(
        version = if (freeThreaded) "3.15.0t" else "3.15.0",
        target = target,
        url = url,
        sha256 = sha256,
        provenance = provenance,
        // CPython's Platforms/Android package produces the same `./` root layout.
        stripComponents = 1,
        prefixFilter = "./",
    )

    /**
     * 3.15.0t for iOS: one XCframework holds every slice. Default [freeThreaded] is `true`,
     * fetching from `thisisthepy/python-multiplatform`'s `cpython-3.15.0t-mobile` release.
     */
    private fun ios315(
        target: String,
        sha256: String = "b8c0a4b32d31738f6ead7ace28acee0cb2a590bd0ca7015e9c32af613445e6b5",
        freeThreaded: Boolean = true,
        url: String = "$PMP_MOBILE_3_15_0T/python-3.15.0t-iOS-XCframework.tar.gz",
        provenance: String = PMP_MOBILE_SUMS_3_15_0T,
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
            android315("aarch64-linux-android", "40995c530c3e454d829b4fdea8404d8c0b0e58f60c2d41d400daa1fb0ab65991"),
            android315("x86_64-linux-android", "d52580653858c429f542db0ca4ecfe12452faf6c9a967bb4b62797df7c2c8d24"),
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
