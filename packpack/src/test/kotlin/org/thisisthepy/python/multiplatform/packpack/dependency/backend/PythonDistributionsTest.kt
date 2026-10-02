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
    private val pm = "https://github.com/thisisthepy/python-multiplatform/raw/release/binary"
    private val ios314 = "https://github.com/beeware/Python-Apple-support/releases/download/3.14-b10/Python-3.14-iOS-support.b10.tar.gz"
    private val ios314Sha = "200ef60eb67be0483ceb638daa9048f84f41a9a952707a5ad4c3198037c7b583"

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
}
