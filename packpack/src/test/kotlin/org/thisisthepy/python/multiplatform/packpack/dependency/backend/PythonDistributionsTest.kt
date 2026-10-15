package org.thisisthepy.python.multiplatform.packpack.dependency.backend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the (version, target) -> (URL, SHA-256) table of `PythonDistributions` for every pair, so a
 * changed URL or digest is a deliberate, reviewed edit. The digests' provenance is in
 * `PythonDistributions.kt` (python-multiplatform's `python-checksums.properties` and the upstream
 * `SHA256SUMS` / release asset digests).
 */
class PythonDistributionsTest {
    private val pbs = "https://github.com/astral-sh/python-build-standalone/releases/download/20260807"
    private val pbs315 = "https://github.com/astral-sh/python-build-standalone/releases/download/20261013"
    private val pyorg315 = "https://www.python.org/ftp/python/3.15.0"
    private val ios315 = "$pyorg315/python-3.15.0-iOS-XCframework.tar.gz"
    private val ios315Sha = "68e98ab35cc0643ba3a227a8c7768da45c2a5e67dbe1bc26598537e96680fd19"
    private val pm = "https://github.com/thisisthepy/python-multiplatform/raw/release/binary"
    private val ios314 = "https://github.com/beeware/Python-Apple-support/releases/download/3.14-b11/Python-3.14-iOS-support.b11.tar.gz"
    private val ios314Sha = "b591f3301bd22a4f423c49c746cac9e55558b909fd14d6eb8327ccc62234ab7b"

    /** version/target -> (url, sha256). */
    private val pinned: Map<String, Pair<String, String>> =
        linkedMapOf(
            "3.14.7/aarch64-apple-darwin" to
                ("$pbs/cpython-3.14.7+20260807-aarch64-apple-darwin-install_only.tar.gz" to
                    "433c2c61f5edfb2beda061d3b1f226bbe3493fa926cec68068843dd49110e539"),
            "3.14.7/x86_64-apple-darwin" to
                ("$pbs/cpython-3.14.7+20260807-x86_64-apple-darwin-install_only.tar.gz" to
                    "2551ac222fbf0ec16ad8bd575bf94614d4da0877e74adb0e155cef019bf3007d"),
            "3.14.7/x86_64-unknown-linux-gnu" to
                ("$pbs/cpython-3.14.7+20260807-x86_64-unknown-linux-gnu-install_only.tar.gz" to
                    "3d1705fee7747c491d774e26fa91fad67e25d1eb3ede4124dc88501279f2e7d4"),
            "3.14.7/x86_64-pc-windows-msvc" to
                ("$pbs/cpython-3.14.7+20260807-x86_64-pc-windows-msvc-install_only.tar.gz" to
                    "b06367695ce177e4abb2e02a8ff00fa60903c29fb381e40affaee2f51ef6115a"),
            "3.14.7/aarch64-unknown-linux-gnu" to
                ("$pbs/cpython-3.14.7+20260807-aarch64-unknown-linux-gnu-install_only.tar.gz" to
                    "3657f14592d0a9c3f459ded52bf6f38976698cb07365e4025684bb11dd6be1cb"),
            "3.14.7/aarch64-pc-windows-msvc" to
                ("$pbs/cpython-3.14.7+20260807-aarch64-pc-windows-msvc-install_only.tar.gz" to
                    "66c2afade786c86a303b2e8af501596df0dcd0e47da40ce6cf8cdae26241bb1c"),
            "3.14.7/aarch64-linux-android" to
                ("https://www.python.org/ftp/python/3.14.7/python-3.14.7-aarch64-linux-android.tar.gz" to
                    "6d50cc3aa66e414a439594089bcdfb5f1264358155c70c1f00471c24cfb477fb"),
            "3.14.7/x86_64-linux-android" to
                ("https://www.python.org/ftp/python/3.14.7/python-3.14.7-x86_64-linux-android.tar.gz" to
                    "2c16ce2359565cd8c24f86cfb75630768ba6607e732946b294b969797f583b60"),
            "3.14.7/arm64-apple-ios" to (ios314 to ios314Sha),
            "3.14.7/arm64-apple-ios-simulator" to (ios314 to ios314Sha),
            "3.14.7/x86_64-apple-ios-simulator" to (ios314 to ios314Sha),
            "3.15.0/aarch64-apple-darwin" to
                ("$pbs315/cpython-3.15.0+20261013-aarch64-apple-darwin-install_only.tar.gz" to
                    "f5d1d82e2f5660d4decae5d2b7470631850bcd7bd39595d9c3cca3c5ee54eae7"),
            "3.15.0t/aarch64-apple-darwin" to
                ("$pbs315/cpython-3.15.0+20261013-aarch64-apple-darwin-freethreaded-install_only.tar.gz" to
                    "9b982a1644405461bb0c96efecff18538e259c3ac253056f25a423fdce8fc069"),
            "3.15.0/x86_64-apple-darwin" to
                ("$pbs315/cpython-3.15.0+20261013-x86_64-apple-darwin-install_only.tar.gz" to
                    "c4adbda246262a55920ec0804f5a6a9a3a1e5948f3423da71926320b2489e50e"),
            "3.15.0t/x86_64-apple-darwin" to
                ("$pbs315/cpython-3.15.0+20261013-x86_64-apple-darwin-freethreaded-install_only.tar.gz" to
                    "40c0033260b35a88365dad616d22bfbf182e894de866271b0b290e3c87e7468b"),
            "3.15.0/x86_64-unknown-linux-gnu" to
                ("$pbs315/cpython-3.15.0+20261013-x86_64-unknown-linux-gnu-install_only.tar.gz" to
                    "e02a368dd5dc76ffe0599e5109ad6c1ee84e597910758b0e006bab25372a8921"),
            "3.15.0t/x86_64-unknown-linux-gnu" to
                ("$pbs315/cpython-3.15.0+20261013-x86_64-unknown-linux-gnu-freethreaded-install_only.tar.gz" to
                    "f8bf1b9cfe976fc415f656b3ae42a179e32ba3c7f34fa254ac0217948c43b34a"),
            "3.15.0/x86_64-pc-windows-msvc" to
                ("$pbs315/cpython-3.15.0+20261013-x86_64-pc-windows-msvc-install_only.tar.gz" to
                    "0d9950ba8c6399203e60ed6a2452fc4e8def35c83fb6da00ec815784b7887483"),
            "3.15.0t/x86_64-pc-windows-msvc" to
                ("$pbs315/cpython-3.15.0+20261013-x86_64-pc-windows-msvc-freethreaded-install_only.tar.gz" to
                    "3fd540d407f34dfcec3c51db57b84e35264cda209be0c4d43e113e4e67d0bbb5"),
            "3.15.0/aarch64-unknown-linux-gnu" to
                ("$pbs315/cpython-3.15.0+20261013-aarch64-unknown-linux-gnu-install_only.tar.gz" to
                    "26a54e8fbde81bcc39ba35ad855a6a573295c023793c20fff1670a560649bcd7"),
            "3.15.0t/aarch64-unknown-linux-gnu" to
                ("$pbs315/cpython-3.15.0+20261013-aarch64-unknown-linux-gnu-freethreaded-install_only.tar.gz" to
                    "05717335ecaa89b0dac64833c9c7c61a70e6db291e0a2c7ca707c80bfa20b0f5"),
            "3.15.0/aarch64-pc-windows-msvc" to
                ("$pbs315/cpython-3.15.0+20261013-aarch64-pc-windows-msvc-install_only.tar.gz" to
                    "588a4d0817d74fd632c235868a8218f63baee4ce40126ebe16b5e1826a1b86ba"),
            "3.15.0t/aarch64-pc-windows-msvc" to
                ("$pbs315/cpython-3.15.0+20261013-aarch64-pc-windows-msvc-freethreaded-install_only.tar.gz" to
                    "53cbedb81dce9885273da9117f2652a7b1cf31afe1b63cbb11f01971658c8d85"),
            "3.15.0/aarch64-linux-android" to
                ("$pyorg315/python-3.15.0-aarch64-linux-android.tar.gz" to
                    "571e4cf4e0aee3d1ab54a52ca3d4e6845b85ec0bb327f7debfb873172f445dab"),
            "3.15.0/x86_64-linux-android" to
                ("$pyorg315/python-3.15.0-x86_64-linux-android.tar.gz" to
                    "2413d51948d612cf42baefba25295bb83c2ed84df5726af6fe5bde2dec5d87e7"),
            "3.15.0/arm64-apple-ios" to (ios315 to ios315Sha),
            "3.15.0/arm64-apple-ios-simulator" to (ios315 to ios315Sha),
            "3.15.0/x86_64-apple-ios-simulator" to (ios315 to ios315Sha),
            "3.13.0/aarch64-apple-darwin" to
                ("$pm/cpython-3.13.0+20241008-aarch64-apple-darwin-pgo+lto-full.tar.zst" to
                    "834b674dd1430267ed3b2be6107e637ac42edc5007ef35dce0ac163a03038caf"),
            "3.13.0/x86_64-apple-darwin" to
                ("$pm/cpython-3.13.0+20241008-x86_64-apple-darwin-pgo+lto-full.tar.zst" to
                    "640ec7e42b4824e1d5645ab4fc35e132598dc3861cab7e0480b24b25a373b4da"),
            "3.13.0/x86_64-pc-windows-msvc" to
                ("$pm/cpython-3.13.0+20241008-x86_64-pc-windows-msvc-shared-pgo-full.tar.zst" to
                    "4d23c10867935d49e4b231ab2525b334c4004188fbc74e795856ea0dcaff2ce9"),
            "3.13.0/x86_64-unknown-linux-gnu" to
                ("$pm/cpython-3.13.0+20241008-x86_64_v4-unknown-linux-gnu-lto-full.tar.zst" to
                    "32fc3d20f14d58195e0d204e8d67610267c56c231f7ef4472da9f1301169ee17"),
        )

    /** Known 3.13.0 archives with no obtainable digest: refused (fail closed). */
    private val unpinned: Map<String, String> =
        linkedMapOf(
            "3.13.0/aarch64-linux-android" to "$pm/aarch64-linux-android.tar.xz",
            "3.13.0/x86_64-linux-android" to "$pm/x86_64-linux-android.tar.xz",
            "3.13.0/arm64-apple-ios" to "$pm/arm64-iphoneos.zip",
            "3.13.0/arm64-apple-ios-simulator" to "$pm/arm64-iphonesimulator.zip",
            "3.13.0/x86_64-apple-ios-simulator" to "$pm/x86_64-iphonesimulator.zip",
        )

    @Test
    fun everyPinnedPairResolvesToItsUrlAndDigest() {
        for ((pair, expected) in pinned) {
            val (version, target) = pair.split('/')
            val distribution = PythonDistributions.resolve(version, target).getOrThrow()
            assertEquals(expected.first, distribution.url, pair)
            assertEquals(expected.second, distribution.sha256, pair)
            assertEquals(version, distribution.version, pair)
            assertEquals(target, distribution.target, pair)
            assertTrue(distribution.provenance.isNotBlank(), pair)
        }
    }

    @Test
    fun theSupportedListIsExactlyThePinnedPairs() {
        assertEquals(pinned.keys.toList(), PythonDistributions.SUPPORTED)
        assertEquals(
            (pinned.keys + unpinned.keys).sorted(),
            PythonDistributions.ALL.map { "${it.version}/${it.target}" }.sorted(),
        )
    }

    @Test
    fun everyPinIsALowerCaseSha256() {
        for (distribution in PythonDistributions.ALL) {
            val sha = distribution.sha256 ?: continue
            assertTrue(Regex("[0-9a-f]{64}").matches(sha), "${distribution.version}/${distribution.target}: $sha")
        }
    }

    @Test
    fun unpinnedPairsAreRefusedNamingTheArchiveAndTheSupportedList() {
        for ((pair, url) in unpinned) {
            val (version, target) = pair.split('/')
            val known = PythonDistributions.ALL.single { it.version == version && it.target == target }
            assertEquals(url, known.url, pair)
            assertNull(known.sha256, pair)

            val result = PythonDistributions.resolve(version, target)
            assertTrue(result.isFailure, pair)
            val message = result.exceptionOrNull()?.message.orEmpty()
            assertTrue("no pinned SHA-256" in message, message)
            assertTrue(url.substringAfterLast('/') in message, message)
            assertTrue("Supported: 3.14.7/aarch64-apple-darwin" in message, message)
        }
    }

    @Test
    fun anUnknownPairIsRefusedWithTheSupportedList() {
        for ((version, target) in listOf("3.12.0" to "aarch64-apple-darwin", "3.14.7" to "wasm32-pyodide2024", "3.13.0" to "aarch64-unknown-linux-gnu")) {
            val result = PythonDistributions.resolve(version, target)
            assertTrue(result.isFailure, "$version/$target")
            assertEquals(
                "Python $version is not available for $target. Supported: ${PythonDistributions.SUPPORTED.joinToString(", ")}",
                result.exceptionOrNull()?.message,
            )
        }
    }

    @Test
    fun minorVersionAliasesNameThePinnedPatchRelease() {
        assertEquals("3.14.7", PythonDistributions.resolve("3.14", "x86_64-linux-android").getOrThrow().version)
        assertEquals("3.13.0", PythonDistributions.resolve("3.13", "x86_64-apple-darwin").getOrThrow().version)
    }

    @Test
    fun the315AliasesNameThePinnedPatchReleaseAndTheFreeThreadedBuild() {
        assertEquals("3.15.0", PythonDistributions.canonicalVersion("3.15"))
        assertEquals("3.15.0t", PythonDistributions.canonicalVersion("3.15t"))
        assertEquals("3.15.0", PythonDistributions.resolve("3.15", "aarch64-apple-darwin").getOrThrow().version)
        val ft = PythonDistributions.resolve("3.15t", "x86_64-unknown-linux-gnu").getOrThrow()
        assertEquals("3.15.0t", ft.version)
        assertTrue("-freethreaded-install_only.tar.gz" in ft.url, ft.url)
        val gil = PythonDistributions.resolve("3.15", "x86_64-unknown-linux-gnu").getOrThrow()
        assertTrue("-freethreaded" !in gil.url, gil.url)
    }

    @Test
    fun the315DesktopTargetsHaveBothBuildsAndTheMobileTargetsHaveOnlyTheGilBuild() {
        val desktop =
            listOf(
                "aarch64-apple-darwin", "x86_64-apple-darwin", "x86_64-unknown-linux-gnu",
                "aarch64-unknown-linux-gnu", "x86_64-pc-windows-msvc", "aarch64-pc-windows-msvc",
            )
        for (target in desktop) {
            assertTrue(PythonDistributions.resolve("3.15", target).isSuccess, target)
            assertTrue(PythonDistributions.resolve("3.15t", target).isSuccess, target)
        }
        val mobile = listOf("aarch64-linux-android", "x86_64-linux-android", "arm64-apple-ios", "arm64-apple-ios-simulator", "x86_64-apple-ios-simulator")
        for (target in mobile) {
            assertTrue(PythonDistributions.resolve("3.15", target).isSuccess, target)
            assertTrue(PythonDistributions.resolve("3.15t", target).isFailure, target)
        }
    }

    @Test
    fun theFourteenSevenRowsAreKept() {
        assertTrue(PythonDistributions.ALL.count { it.version == "3.14.7" } == 11)
    }
}
