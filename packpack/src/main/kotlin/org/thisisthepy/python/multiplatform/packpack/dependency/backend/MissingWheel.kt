package org.thisisthepy.python.multiplatform.packpack.dependency.backend

/** Whether an sdist exists for a package that has no wheel for the target. See docs/SPEC.md (sync). */
enum class SdistAvailability {
    /** uv built the sdist, but the result does not fit the target. */
    ONLY_SDIST,

    /** uv found wheels for other platforms and no sdist. */
    NO_SDIST,

    /** uv was told not to build (`--no-build`), so it did not say. */
    UNKNOWN,
}

/** A package for which uv found no installable wheel for [target]. */
data class MissingWheel(
    val packageSpec: String,
    val target: String,
    val sdist: SdistAvailability,
    val detail: String? = null,
) {
    fun message(): String {
        val what =
            when (sdist) {
                SdistAvailability.ONLY_SDIST -> "only an sdist exists (building it does not produce a wheel for the target)"
                SdistAvailability.NO_SDIST -> "no sdist exists either${detail?.let { " (uv found wheels only for: $it)" }.orEmpty()}"
                SdistAvailability.UNKNOWN -> "no usable wheel was found (uv did not say whether an sdist exists)"
            }
        return "No wheel for `$packageSpec` for target $target: $what."
    }
}

/** Thrown by `UVBackend.installDependenciesToTarget`; [cause] holds uv's raw error. */
class NoWheelForTargetException(
    val missing: MissingWheel,
    cause: Throwable,
) : Exception("${missing.message()}\n\nuv said:\n${cause.message.orEmpty()}", cause)

private val SDIST_BUILT_INCOMPATIBLE =
    Regex("""Failed to download and build `([^`]+)`.*?is not compatible with the target Python""")
private val NO_PLATFORM_WHEELS =
    Regex("""Because (?:all versions of )?(\S+) (?:has|have) no wheels with a matching platform tag""")
private val NO_USABLE_WHEELS =
    Regex("""Because (?:all versions of )?(\S+) (?:has|have) no usable wheels""")
private val AVAILABLE_PLATFORMS =
    Regex("""Wheels are available for `[^`]+`(?: \([^)]*\))? on the following platforms?: (.+?)(?:\s+hint:|$)""")

/**
 * Recognizes uv's "no wheel for this target" failures in [output], or returns null so the caller
 * keeps the raw text. Pure; patterns come from real `uv pip install --python-platform
 * aarch64-linux-android` output (uv 0.12.3), whitespace-collapsed because uv wraps lines.
 */
fun parseMissingWheel(
    output: String,
    target: String,
): MissingWheel? {
    val text = output.replace(Regex("""\s+"""), " ")
    SDIST_BUILT_INCOMPATIBLE.find(text)?.let {
        return MissingWheel(it.groupValues[1], target, SdistAvailability.ONLY_SDIST)
    }
    NO_PLATFORM_WHEELS.find(text)?.let {
        val platforms = AVAILABLE_PLATFORMS.find(text)?.groupValues?.get(1)?.replace("`", "")?.trim()?.trimEnd('.')
        return MissingWheel(it.groupValues[1], target, SdistAvailability.NO_SDIST, platforms)
    }
    NO_USABLE_WHEELS.find(text)?.let {
        return MissingWheel(it.groupValues[1], target, SdistAvailability.UNKNOWN)
    }
    return null
}

/** [error] rewritten as [NoWheelForTargetException] when uv's text is recognized, else unchanged. */
fun describeInstallFailure(
    error: Throwable,
    target: String,
): Throwable = parseMissingWheel(error.message.orEmpty(), target)?.let { NoWheelForTargetException(it, error) } ?: error
