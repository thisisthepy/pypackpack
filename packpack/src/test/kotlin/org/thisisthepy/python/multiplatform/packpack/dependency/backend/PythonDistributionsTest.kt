package org.thisisthepy.python.multiplatform.packpack.dependency.backend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the (version, target) -> (URL, SHA-256) table of `PythonDistributions` for every pair, so a
 * changed URL or digest is a deliberate, reviewed edit. The digests' provenance is in
 * `PythonDistributions.kt` (python-build-standalone's `SHA256SUMS`, and `shasum -a 256` of the
 * python.org downloads).
 */
class PythonDistributionsTest {
    private val pbs315 = "https://github.com/astral-sh/python-build-standalone/releases/download/20261013"
    private val pyorg315 = "https://www.python.org/ftp/python/3.15.0"
    private val ios315 = "$pyorg315/python-3.15.0-iOS-XCframework.tar.gz"
    private val ios315Sha = "68e98ab35cc0643ba3a227a8c7768da45c2a5e67dbe1bc26598537e96680fd19"

    /** version/target -> (url, sha256). */
    private val pinned: Map<String, Pair<String, String>> =
        linkedMapOf(
            "3.15.0t/aarch64-apple-darwin" to
                ("$pbs315/cpython-3.15.0+20261013-aarch64-apple-darwin-freethreaded-install_only.tar.gz" to
                    "9b982a1644405461bb0c96efecff18538e259c3ac253056f25a423fdce8fc069"),
            "3.15.0t/x86_64-apple-darwin" to
                ("$pbs315/cpython-3.15.0+20261013-x86_64-apple-darwin-freethreaded-install_only.tar.gz" to
                    "40c0033260b35a88365dad616d22bfbf182e894de866271b0b290e3c87e7468b"),
            "3.15.0t/x86_64-unknown-linux-gnu" to
                ("$pbs315/cpython-3.15.0+20261013-x86_64-unknown-linux-gnu-freethreaded-install_only.tar.gz" to
                    "f8bf1b9cfe976fc415f656b3ae42a179e32ba3c7f34fa254ac0217948c43b34a"),
            "3.15.0t/x86_64-pc-windows-msvc" to
                ("$pbs315/cpython-3.15.0+20261013-x86_64-pc-windows-msvc-freethreaded-install_only.tar.gz" to
                    "3fd540d407f34dfcec3c51db57b84e35264cda209be0c4d43e113e4e67d0bbb5"),
            "3.15.0t/aarch64-unknown-linux-gnu" to
                ("$pbs315/cpython-3.15.0+20261013-aarch64-unknown-linux-gnu-freethreaded-install_only.tar.gz" to
                    "05717335ecaa89b0dac64833c9c7c61a70e6db291e0a2c7ca707c80bfa20b0f5"),
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
        assertEquals(pinned.keys.sorted(), PythonDistributions.SUPPORTED.sorted())
        assertEquals(pinned.keys.sorted(), PythonDistributions.ALL.map { "${it.version}/${it.target}" }.sorted())
    }

    @Test
    fun everyPinIsALowerCaseSha256() {
        for (distribution in PythonDistributions.ALL) {
            val sha = distribution.sha256 ?: continue
            assertTrue(Regex("[0-9a-f]{64}").matches(sha), "${distribution.version}/${distribution.target}: $sha")
        }
    }

    @Test
    fun anUnknownPairIsRefusedWithTheSupportedList() {
        for ((version, target) in listOf("3.12.0" to "aarch64-apple-darwin", "3.15.0t" to "wasm32-pyodide2024", "3.15.0t" to "aarch64-linux-android")) {
            val result = PythonDistributions.resolve(version, target)
            assertTrue(result.isFailure, "$version/$target")
            assertEquals(
                "Python $version is not available for $target. Supported: ${PythonDistributions.SUPPORTED.joinToString(", ")}",
                result.exceptionOrNull()?.message,
            )
        }
    }

    /**
     * SPEC Scope decisions: CPython 3.15t free-threaded only. The GIL builds (3.14.7, 3.13.0 and the
     * 3.15.0 desktop ones) are gone; `3.15` and `3.15t` both name the free-threaded `3.15.0t`.
     */
    @Test
    fun the315AliasesNameTheFreeThreadedBuild() {
        assertEquals("3.15.0t", PythonDistributions.canonicalVersion("3.15"))
        assertEquals("3.15.0t", PythonDistributions.canonicalVersion("3.15t"))
        val ft = PythonDistributions.resolve("3.15", "x86_64-unknown-linux-gnu").getOrThrow()
        assertEquals("3.15.0t", ft.version)
        assertTrue("-freethreaded-install_only.tar.gz" in ft.url, ft.url)
    }

    @Test
    fun noGilBuildIsOfferedForADesktopTarget() {
        val desktop =
            listOf(
                "aarch64-apple-darwin", "x86_64-apple-darwin", "x86_64-unknown-linux-gnu",
                "aarch64-unknown-linux-gnu", "x86_64-pc-windows-msvc", "aarch64-pc-windows-msvc",
            )
        for (target in desktop) {
            assertTrue(PythonDistributions.resolve("3.15t", target).isSuccess, target)
            for (gil in listOf("3.15.0", "3.14.7", "3.14", "3.13.0", "3.13")) {
                assertTrue(PythonDistributions.resolve(gil, target).isFailure, "$gil/$target")
            }
        }
        assertTrue(PythonDistributions.ALL.none { it.version.startsWith("3.14") || it.version.startsWith("3.13") })
    }

    /**
     * The one GIL exception (SPEC, until python-multiplatform #163 publishes 3.15t mobile builds):
     * python.org's 3.15.0 Android and iOS archives, reachable only as the exact version `3.15.0`.
     */
    @Test
    fun theMobileTargetsKeepThePythonOrgGilBuildUntilPythonMultiplatform163() {
        val mobile = listOf("aarch64-linux-android", "x86_64-linux-android", "arm64-apple-ios", "arm64-apple-ios-simulator", "x86_64-apple-ios-simulator")
        for (target in mobile) {
            assertEquals("3.15.0", PythonDistributions.resolve("3.15.0", target).getOrThrow().version, target)
            assertTrue(PythonDistributions.resolve("3.15t", target).isFailure, target)
        }
    }
}
