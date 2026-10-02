package org.thisisthepy.python.multiplatform.packpack.dependency.backend

/**
 * One prebuilt CPython archive `installPython` can fetch for one (version, target) pair.
 *
 * @property sha256 the pinned digest the download must match before anything is extracted, or
 *   `null` when no digest could be obtained; a `null` pin is refused, never installed unverified.
 * @property provenance where [sha256] was copied from, so it can be re-checked.
 * @property stripComponents / [prefixFilter] how the archive's tree maps onto the install directory
 *   (passed to `utils/Archive.kt`'s `extractArchive`).
 */
data class PythonDistribution(
    val version: String,
    val target: String,
    val url: String,
    val sha256: String?,
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
 * The versions are the ones `toolchain` can ask for (its `PythonSdkVersion.PY3_14_7` and
 * `PY3_13_0`, in the `toolchain` repository's `dsl/DSLCore.kt`).
 *
 * 3.14.7 follows python-multiplatform (the runtime's source of truth; its `gradle.properties`
 * `pythonVersion=3.14.7`, `pythonBuildStandaloneRelease=20260807`, `pythonAppleSupportBuild=b10`,
 * and `docs/platforms/python-version-acquisition.md`): python-build-standalone for desktop,
 * python.org for Android, BeeWare Python-Apple-support for iOS. Every 3.14.7 digest was copied from
 * python-multiplatform's lockfile `python-checksums.properties` and cross-checked against the
 * upstream's own published digest, as each entry's [PythonDistribution.provenance] says.
 *
 * 3.13.0 keeps its existing source, the `binary/` directory of python-multiplatform's `release`
 * branch. Its four desktop archives are byte-for-byte-sized copies of python-build-standalone
 * release 20241008 (`binary/download.md` says so, and each git blob's size equals the release
 * asset's), so their pin is that release's `SHA256SUMS`. If the copy ever differs from upstream the
 * pin fails closed. Its Android (`.tar.xz`) and iOS (`.zip`) archives have no published digest
 * anywhere and cannot be hashed without downloading 64-76 MB each, so they carry `sha256 = null`
 * and are refused (TODO below).
 */
object PythonDistributions {
    private const val PBS = "https://github.com/astral-sh/python-build-standalone/releases/download"
    private const val PBS_RELEASE_3_14_7 = "20260807"
    private const val PYTHON_ORG = "https://www.python.org/ftp/python"
    private const val BEEWARE = "https://github.com/beeware/Python-Apple-support/releases/download"
    private const val PM_BINARY = "https://github.com/thisisthepy/python-multiplatform/raw/release/binary"

    private const val LOCKFILE = "python-multiplatform python-checksums.properties"
    private const val SUMS_3_14_7 = "python-build-standalone $PBS_RELEASE_3_14_7 SHA256SUMS"
    private const val SUMS_3_13_0 = "python-build-standalone 20241008 SHA256SUMS"

    /** `X.Y` shorthands accepted on the command line; each means the one patch release pinned here. */
    val VERSION_ALIASES: Map<String, String> = mapOf("3.14" to "3.14.7", "3.13" to "3.13.0")

    private fun pbs314(
        target: String,
        sha256: String,
        provenance: String,
    ) = PythonDistribution(
        version = "3.14.7",
        target = target,
        url = "$PBS/$PBS_RELEASE_3_14_7/cpython-3.14.7+$PBS_RELEASE_3_14_7-$target-install_only.tar.gz",
        sha256 = sha256,
        provenance = provenance,
        // install_only archives hold a single top-level `python/` (bin/, lib/, include/).
        stripComponents = 1,
        prefixFilter = "python/",
    )

    private fun android314(
        target: String,
        sha256: String,
        lockKey: String,
    ) = PythonDistribution(
        version = "3.14.7",
        target = target,
        url = "$PYTHON_ORG/3.14.7/python-3.14.7-$target.tar.gz",
        sha256 = sha256,
        provenance = "$LOCKFILE `$lockKey` (python.org publishes no SHA256SUMS; it signs with Sigstore)",
        // python.org's Android tarball is rooted at `./` (README.md, android.py, prefix/, ...).
        stripComponents = 1,
        prefixFilter = "./",
    )

    private fun ios314(target: String) =
        PythonDistribution(
            version = "3.14.7",
            target = target,
            // One XCframework holds every iOS slice (ios-arm64, ios-arm64_x86_64-simulator).
            // python-multiplatform pairs pythonVersion=3.14.7 with b10; b10's release notes say it
            // carries CPython 3.14.6 -- the newest BeeWare 3.14 build there is.
            url = "$BEEWARE/3.14-b10/Python-3.14-iOS-support.b10.tar.gz",
            sha256 = "200ef60eb67be0483ceb638daa9048f84f41a9a952707a5ad4c3198037c7b583",
            provenance =
                "$LOCKFILE `ios-3.14-b10`; equals the GitHub release asset digest " +
                    "(BeeWare publishes no checksum file or signature)",
            stripComponents = 0,
            prefixFilter = null,
        )

    private fun pbs313(
        target: String,
        flavour: String,
        sha256: String,
    ) = PythonDistribution(
        version = "3.13.0",
        target = target,
        url = "$PM_BINARY/cpython-3.13.0+20241008-$flavour.tar.zst",
        sha256 = sha256,
        provenance = "$SUMS_3_13_0 (binary/ holds copies of that release; blob size equals the asset size)",
        // `full` archives also hold python/build/, python/licenses/ and PYTHON.json; keep install/.
        stripComponents = 2,
        prefixFilter = "python/install/",
    )

    // TODO(#21): pin these once their SHA-256 is known. No digest is published for them, and
    // computing one needs the 64-76 MB download. Until then `resolve` refuses them (fails closed).
    private fun unpinned313(
        target: String,
        fileName: String,
    ) = PythonDistribution(
        version = "3.13.0",
        target = target,
        url = "$PM_BINARY/$fileName",
        sha256 = null,
        provenance = "none: python-multiplatform binary/$fileName has no published SHA-256",
        stripComponents = 0,
        prefixFilter = null,
    )

    /** Every known (version, target) pair, pinned or not. */
    val ALL: List<PythonDistribution> =
        listOf(
            pbs314(
                "aarch64-apple-darwin",
                "433c2c61f5edfb2beda061d3b1f226bbe3493fa926cec68068843dd49110e539",
                "$LOCKFILE `macos-aarch64-3.14.7-20260807`; equals $SUMS_3_14_7",
            ),
            pbs314(
                "x86_64-apple-darwin",
                "2551ac222fbf0ec16ad8bd575bf94614d4da0877e74adb0e155cef019bf3007d",
                "$LOCKFILE `macos-x86_64-3.14.7-20260807`; equals $SUMS_3_14_7",
            ),
            pbs314(
                "x86_64-unknown-linux-gnu",
                "3d1705fee7747c491d774e26fa91fad67e25d1eb3ede4124dc88501279f2e7d4",
                "$LOCKFILE `linux-x86_64-3.14.7-20260807`; equals $SUMS_3_14_7",
            ),
            pbs314(
                "x86_64-pc-windows-msvc",
                "b06367695ce177e4abb2e02a8ff00fa60903c29fb381e40affaee2f51ef6115a",
                "$LOCKFILE `windows-x86_64-3.14.7-20260807`; equals $SUMS_3_14_7",
            ),
            pbs314(
                "aarch64-unknown-linux-gnu",
                "3657f14592d0a9c3f459ded52bf6f38976698cb07365e4025684bb11dd6be1cb",
                "$SUMS_3_14_7 (not in python-multiplatform's lockfile, which pins 4 desktop targets)",
            ),
            pbs314(
                "aarch64-pc-windows-msvc",
                "66c2afade786c86a303b2e8af501596df0dcd0e47da40ce6cf8cdae26241bb1c",
                "$SUMS_3_14_7 (not in python-multiplatform's lockfile, which pins 4 desktop targets)",
            ),
            android314(
                "aarch64-linux-android",
                "6d50cc3aa66e414a439594089bcdfb5f1264358155c70c1f00471c24cfb477fb",
                "android-aarch64-3.14.7",
            ),
            android314(
                "x86_64-linux-android",
                "2c16ce2359565cd8c24f86cfb75630768ba6607e732946b294b969797f583b60",
                "android-x86_64-3.14.7",
            ),
            ios314("arm64-apple-ios"),
            ios314("arm64-apple-ios-simulator"),
            ios314("x86_64-apple-ios-simulator"),
            pbs313(
                "aarch64-apple-darwin",
                "aarch64-apple-darwin-pgo+lto-full",
                "834b674dd1430267ed3b2be6107e637ac42edc5007ef35dce0ac163a03038caf",
            ),
            pbs313(
                "x86_64-apple-darwin",
                "x86_64-apple-darwin-pgo+lto-full",
                "640ec7e42b4824e1d5645ab4fc35e132598dc3861cab7e0480b24b25a373b4da",
            ),
            pbs313(
                "x86_64-pc-windows-msvc",
                "x86_64-pc-windows-msvc-shared-pgo-full",
                "4d23c10867935d49e4b231ab2525b334c4004188fbc74e795856ea0dcaff2ce9",
            ),
            pbs313(
                "x86_64-unknown-linux-gnu",
                "x86_64_v4-unknown-linux-gnu-lto-full",
                "32fc3d20f14d58195e0d204e8d67610267c56c231f7ef4472da9f1301169ee17",
            ),
            unpinned313("aarch64-linux-android", "aarch64-linux-android.tar.xz"),
            unpinned313("x86_64-linux-android", "x86_64-linux-android.tar.xz"),
            unpinned313("arm64-apple-ios", "arm64-iphoneos.zip"),
            unpinned313("arm64-apple-ios-simulator", "arm64-iphonesimulator.zip"),
            unpinned313("x86_64-apple-ios-simulator", "x86_64-iphonesimulator.zip"),
        )

    /** The pairs `installPython` will install, as `version/target`, in [ALL]'s order. */
    val SUPPORTED: List<String> = ALL.filter { it.sha256 != null }.map { "${it.version}/${it.target}" }

    /** [version] with an `X.Y` alias expanded ("3.14" -> "3.14.7"); anything else unchanged. */
    fun canonicalVersion(version: String): String = VERSION_ALIASES[version] ?: version

    /**
     * The pinned distribution for ([version], [canonicalTarget]), or a failed [Result] that names
     * the pair and lists [SUPPORTED]. A known pair without a pin is refused too, saying why.
     */
    fun resolve(
        version: String,
        canonicalTarget: String,
    ): Result<PythonDistribution> {
        val exact = canonicalVersion(version)
        val known = ALL.firstOrNull { it.version == exact && it.target == canonicalTarget }
        val supportedList = SUPPORTED.joinToString(", ")
        return when {
            known == null -> {
                Result.failure(
                    IllegalArgumentException(
                        "Python $version is not available for $canonicalTarget. Supported: $supportedList",
                    ),
                )
            }

            known.sha256 == null -> {
                Result.failure(
                    IllegalStateException(
                        "Python $exact for $canonicalTarget (${known.fileName}) has no pinned SHA-256, " +
                            "so it is refused rather than installed unverified. Supported: $supportedList",
                    ),
                )
            }

            else -> {
                Result.success(known)
            }
        }
    }
}
