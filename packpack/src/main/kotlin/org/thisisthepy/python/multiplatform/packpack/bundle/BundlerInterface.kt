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
 * @param minSdk the declared Android min SDK / API level, or `null` when undeclared. This is
 *   `toolchain`'s per-variant `platforms { android { androidSdk = ... } } }` value (see `toolchain`'s
 *   `f60bc3b`, which added the read/validate/log path but had "nowhere to send it" because this field
 *   did not exist yet). Only meaningful for an `android`-family [target]; validated by
 *   [org.thisisthepy.python.multiplatform.packpack.utils.Platforms.requireValidMinSdk], which every
 *   bundler that accepts it calls before using it.
 * @param metaDirs generated metadata directories -- `.pyi` type stubs and the like -- to merge into
 *   [BundleType.RESOURCE]'s payload. This is `toolchain`'s `python { sourceSets { commonMain {
 *   metaDirs(...) } } }` (`DSLBuild.kt`'s `SourceSetConfig.metaDirs`), which had "nowhere to send it"
 *   the same way [minSdk] once did: `toolchain`'s reference DSL (`(플러그인예시)build.gradle.kts`)
 *   pairs `metaDirs("src/commonMain/generated/meta")` with `srcDirs`, and this repository's own
 *   decision records `.pyi` generation as the Gradle plugin's job (PyREPL-style), not `ppp`'s -- so
 *   this is where that generated output is expected to land before bundling. Empty by default, which
 *   preserves every existing caller's behavior exactly. See [ResourceBundler]'s KDoc for merge order.
 * @param libDirs prebuilt/vendored library directories -- a site-packages-shaped tree -- to merge into
 *   [BundleType.RESOURCE]'s payload. This is `toolchain`'s `python { sourceSets { commonMain {
 *   libDirs(...) } } }`, paired in the same reference DSL with `libDirs("src/commonMain/build/
 *   site-packages")`. Empty by default, which preserves every existing caller's behavior exactly. See
 *   [ResourceBundler]'s KDoc for merge order.
 */
data class BundleRequest(
    val packageDir: File,
    val target: String,
    val buildType: String = "debug",
    val buildLevel: String = "instant",
    val outputDir: File? = null,
    val overwrite: Boolean = false,
    val minSdk: Int? = null,
    val metaDirs: List<File> = emptyList(),
    val libDirs: List<File> = emptyList(),
    /**
     * The app's Python payload version (toolchain's `defaultConfig { versionName; versionCode }`),
     * recorded in the manifest beside the package's own `version` when declared. Code push compares
     * it to decide whether a payload changed.
     */
    val versionName: String? = null,
    val versionCode: Int? = null,
)

/**
 * What a bundler produced, in terms a caller (CLI, Gradle plugin, toolchain) can act on.
 *
 * @param outputDir the conventional bundle output directory (`<package>/build/packpack/<type>/<buildType>/<buildLevel>`
 *   unless [BundleRequest.outputDir] overrode it).
 * @param manifestFile a machine-readable description of the bundle contents (JSON, TOML, ...). For
 *   directory-shaped bundles ([BundleType.RESOURCE]) this is the whole story. For archive-shaped
 *   bundles it still points at a file inside [outputDir] that a caller can inspect without opening the
 *   archive; see [artifactFile] for the archive itself.
 * @param fileCount count of payload files (the thing a human means by "how many files did this
 *   bundle"), not counting bundler-internal metadata such as a `.dist-info` directory.
 * @param artifactFile the single-file archive this bundle produced (e.g. the `.whl` for
 *   [BundleType.SINGLE]), when the bundle shape is "one file" rather than "a directory tree". `null`
 *   for directory-shaped bundles such as [BundleType.RESOURCE].
 */
data class BundleResult(
    val bundleType: BundleType,
    val outputDir: File,
    val manifestFile: File,
    val fileCount: Int,
    val artifactFile: File? = null,
)

/**
 * Factory pattern base interface for bundling.
 *
 * [BundleType.RESOURCE], [BundleType.SINGLE], [BundleType.FAT], and [BundleType.PATCH] are implemented;
 * [BundleType.BINARY] returns a failed [Result] naming itself rather than throwing or silently succeeding,
 * so a caller that asks for it gets an actionable message instead of an empty directory.
 */
interface BundlerInterface {
    fun bundle(request: BundleRequest): Result<BundleResult>

    companion object {
        fun create(type: BundleType): BundlerInterface =
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
) : BundlerInterface {
    override fun bundle(request: BundleRequest): Result<BundleResult> =
        Result.failure(
            NotImplementedError(
                "Bundle type '${type.id}' is not implemented yet. Only 'resource' and 'single' are available today.",
            ),
        )
}
