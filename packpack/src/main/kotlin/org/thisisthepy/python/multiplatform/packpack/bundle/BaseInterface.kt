package org.thisisthepy.python.multiplatform.packpack.bundle

import org.thisisthepy.python.multiplatform.packpack.bundle.binary.BinaryBundler
import org.thisisthepy.python.multiplatform.packpack.bundle.fat.FatWheelBundler
import org.thisisthepy.python.multiplatform.packpack.bundle.patch.WheelPatchBundler
import org.thisisthepy.python.multiplatform.packpack.bundle.resource.ResourceBundler
import org.thisisthepy.python.multiplatform.packpack.bundle.single.SingleWheelBundler
import java.io.File

/**
 * The bundle types listed in `docs/SPEC.md` -> "Bundle Type".
 *
 * [id] is the on-disk/CLI spelling; it is also the directory segment used in the SPEC's output path
 * convention `<package>/build/packpack/<bundle type>/<build type>/<build level>`.
 */
enum class BundleType(
    val id: String,
) {
    BINARY("binary"),
    FAT("fat"),
    SINGLE("single"),
    PATCH("patch"),
    RESOURCE("resource"),
    ;

    companion object {
        fun fromId(id: String): BundleType? = entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
}

/**
 * One bundling job.
 *
 * @param packageDir the ppp package directory - the one holding `pyproject.toml` and `src/`.
 * @param target a target string accepted by `Platforms.normalizeTarget` (alias or canonical triple).
 * @param buildType `debug` / `release`, per SPEC's `--type`.
 * @param buildLevel `instant` / `bytecode` / `native` / `mixed`, per SPEC's `--level`.
 * @param outputDir overrides the conventional output path when non-null.
 * @param overwrite clears an existing output directory instead of failing.
 */
data class BundleRequest(
    val packageDir: File,
    val target: String,
    val buildType: String = "debug",
    val buildLevel: String = "instant",
    val outputDir: File? = null,
    val overwrite: Boolean = false,
)

/** What a bundler produced, in terms a caller (CLI, Gradle plugin, toolchain) can act on. */
data class BundleResult(
    val bundleType: BundleType,
    val outputDir: File,
    val manifestFile: File,
    val fileCount: Int,
)

/**
 * Factory pattern base interface for bundling.
 *
 * Only [BundleType.RESOURCE] has an implementation today; the other four return a failed [Result]
 * naming themselves rather than throwing or silently succeeding, so a caller that asks for one gets
 * an actionable message instead of an empty directory.
 */
interface BaseInterface {
    fun bundle(request: BundleRequest): Result<BundleResult>

    companion object {
        fun create(type: BundleType): BaseInterface =
            when (type) {
                BundleType.BINARY -> BinaryBundler()
                BundleType.FAT -> FatWheelBundler()
                BundleType.SINGLE -> SingleWheelBundler()
                BundleType.PATCH -> WheelPatchBundler()
                BundleType.RESOURCE -> ResourceBundler()
            }
    }
}

/**
 * Shared base for the bundlers `docs/SPEC.md` lists but nobody has written yet.
 *
 * Kept as a failed [Result] rather than a `TODO()` so that a caller which reaches one of these
 * through the factory gets the same error channel as a genuine bundling failure.
 */
abstract class UnimplementedBundler(
    private val type: BundleType,
) : BaseInterface {
    override fun bundle(request: BundleRequest): Result<BundleResult> =
        Result.failure(
            NotImplementedError(
                "Bundle type '${type.id}' is not implemented yet. Only 'resource' is available today.",
            ),
        )
}
