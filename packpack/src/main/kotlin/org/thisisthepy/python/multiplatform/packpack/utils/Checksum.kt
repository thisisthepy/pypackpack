package org.thisisthepy.python.multiplatform.packpack.utils

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Lower-case hex SHA-256 of [file]'s bytes, streamed. */
fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * A downloaded artifact whose SHA-256 is not the pinned one. The message names the artifact, the
 * expected and the actual digest (`docs/SPEC.md`, "Installing a ppp Python distribution").
 */
class ChecksumMismatchException(
    val artifact: String,
    val expected: String,
    val actual: String,
) : IOException(
        "SHA-256 mismatch for $artifact: expected $expected, actual $actual. " +
            "The download was discarded and nothing was extracted.",
    )

/**
 * Compares [file]'s SHA-256 with [expectedSha256] (case-insensitive hex). Returns a failed [Result]
 * carrying a [ChecksumMismatchException] when they differ. It does not delete [file]; the caller
 * owns the staging directory and removes it.
 */
fun verifySha256(
    file: File,
    expectedSha256: String,
    artifact: String = file.name,
): Result<Unit> =
    runCatching {
        val expected = expectedSha256.lowercase()
        val actual = sha256Hex(file)
        if (actual != expected) throw ChecksumMismatchException(artifact, expected, actual)
    }
