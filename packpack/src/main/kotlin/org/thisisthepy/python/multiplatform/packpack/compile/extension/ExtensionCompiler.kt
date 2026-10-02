package org.thisisthepy.python.multiplatform.packpack.compile.extension

import java.io.File

/*
 * The compile slot behind the `native` and `mixed` build levels: C sources that a consumer generated
 * (TypedPython, from its own typed IR) in, CPython extension modules for one target out, built with
 * that target's C toolchain.
 *
 * Interface and data types only -- nothing implements [ExtensionCompilerInterface] yet, and no
 * caller reaches it. The design, the level semantics and the open questions are in this
 * repository's `docs/design/compile-slot.md` (issue #19).
 *
 * This is a backend-layer API in the sense of `AGENTS.md` rule 13: every directory it touches is a
 * parameter ([ExtensionCompileRequest.workingDir], [ExtensionCompileRequest.buildDir],
 * [ExtensionCompileRequest.outputDir], [ExtensionCompileRequest.pythonHome]); nothing reads
 * `user.dir`. It is blocking, not `suspend`, because its one planned caller,
 * `ResourceBundler.bundle`, is blocking and runs inside a Gradle task action.
 */

/**
 * The C toolchain adapter that builds for a target family. Each maps onto an existing placeholder
 * under `compile/backend/external/` (`Clang.kt`, `NDK.kt`, `XCode.kt`). For 2026-11: [CLANG] for
 * desktop is required, [NDK] for `aarch64-linux-android` only if that backend lands, [XCODE] (iOS)
 * is out of scope.
 */
enum class NativeToolchain(
    val id: String,
) {
    CLANG("clang"),
    NDK("ndk"),
    XCODE("xcode"),
}

/**
 * The CPython ABI the extension is built against. It must be the ABI of the runtime that will load
 * the bundle: an extension built for 3.13 does not import into 3.14, and one built for the GIL
 * build does not import into the free-threaded build. Only `major.minor` and the flavour matter;
 * the patch release does not change the ABI.
 *
 * @param version `major.minor`, e.g. `"3.14"`.
 * @param freeThreaded `true` for the free-threaded build (`cp314t`, `Py_GIL_DISABLED`).
 */
data class PythonAbi(
    val version: String,
    val freeThreaded: Boolean = false,
)

/**
 * The plain-Python twin of an extension module: the code that runs, interpreted, when a call cannot
 * keep CPython semantics in C. It is an ordinary module with its own import name, a sibling of the
 * extension in the same package, which the generated C imports by that name (never by file path).
 * Being ordinary Python it follows the build level's bytecode rules like any other module.
 *
 * @param moduleName the fallback's full dotted name; same parent package as the extension, and a
 *   different last component (`app.physics._nbody_fallback` for `app.physics.nbody`).
 * @param source the `.py` file.
 */
data class FallbackModule(
    val moduleName: String,
    val source: File,
)

/**
 * One extension module to build.
 *
 * @param moduleName the full dotted import name (`app.physics.nbody`). It fixes the placement
 *   (`app/physics/nbody<EXT_SUFFIX>` under the output root); the C sources must define the matching
 *   init function (`PyInit_nbody`). A package's `__init__` is not accepted in this version.
 * @param sources the module's generated C translation units (`.c`), compiled and linked into one
 *   extension. At least one.
 * @param fallback the module's `.py` fallback, placed beside the extension. TypedPython always
 *   passes one; `null` for a consumer whose extensions have none.
 * @param includeDirs header directories for this module only. The target's CPython include
 *   directory is added by the slot and is not passed here.
 * @param defines preprocessor macros for this module only, added after
 *   [ExtensionCompileOptions.defines]; a `null` value defines the name without a value.
 */
data class ExtensionModuleSource(
    val moduleName: String,
    val sources: List<File>,
    val fallback: FallbackModule? = null,
    val includeDirs: List<File> = emptyList(),
    val defines: Map<String, String?> = emptyMap(),
)

/**
 * Flags shared by every module of one request.
 *
 * The slot applies build-type defaults first (`debug`: no optimisation, debug info; `release`:
 * optimised, `NDEBUG`, stripped) and the target's required flags (position-independent code, the
 * shared-object link mode, `Py_GIL_DISABLED` where the target's `pyconfig.h` does not set it).
 * These flags are appended after the defaults and so win over them.
 *
 * @param includeDirs header directories for every module (for example a consumer's runtime
 *   headers). The target's CPython include directory is added by the slot, first, and is not
 *   passed here.
 * @param cFlags raw C compiler flags, passed verbatim. They are toolchain-specific (`-O3` for
 *   Clang/NDK, `/O2` for MSVC); see the design note's open questions.
 * @param defines preprocessor macros; a `null` value defines the name without a value.
 * @param linkFlags raw linker flags, passed verbatim.
 */
data class ExtensionCompileOptions(
    val includeDirs: List<File> = emptyList(),
    val cFlags: List<String> = emptyList(),
    val defines: Map<String, String?> = emptyMap(),
    val linkFlags: List<String> = emptyList(),
)

/**
 * One compile job: a set of modules for one target and one ABI.
 *
 * @param modules what to build; non-empty, with unique [ExtensionModuleSource.moduleName]s.
 * @param target a target accepted by `Platforms.normalizeTarget` (alias or canonical triple).
 * @param pythonAbi the runtime's CPython ABI; selects the headers, `libpython` and `EXT_SUFFIX`.
 * @param buildType `debug` / `release`, as in `BundleRequest.buildType`.
 * @param workingDir the ppp package directory (the one holding `pyproject.toml`); the base for
 *   relative paths in diagnostics.
 * @param buildDir intermediates the slot owns: object files and a per-module cache. Kept between
 *   runs; the caller keys it by target and ABI.
 * @param outputDir where the results go, shaped like the bundle's `python/` root
 *   (`<outputDir>/app/physics/nbody<EXT_SUFFIX>` and its fallback `.py`). Must be absent or empty.
 * @param pythonHome the root of an extracted CPython distribution for [target] and [pythonAbi]
 *   (`include/`, `lib/`, sysconfig data), when the caller already has one -- for example the very
 *   distribution the app embeds. `null` lets pypackpack acquire it. Either way the slot checks that
 *   its version and flavour equal [pythonAbi].
 * @param minSdk the Android API level for the NDK compiler's target triple; `null` uses the floor
 *   of the Android CPython build. Meaningful for the `android` family only.
 * @param options flags shared by every module.
 */
data class ExtensionCompileRequest(
    val modules: List<ExtensionModuleSource>,
    val target: String,
    val pythonAbi: PythonAbi,
    val buildType: String,
    val workingDir: File,
    val buildDir: File,
    val outputDir: File,
    val pythonHome: File? = null,
    val minSdk: Int? = null,
    val options: ExtensionCompileOptions = ExtensionCompileOptions(),
)

/**
 * One built extension module.
 *
 * @param relativePath the extension's path under [ExtensionCompileResult.outputDir], `/`-separated
 *   (`app/physics/nbody.cpython-314-darwin.so`); the same path is valid under the bundle's
 *   `python/` root.
 * @param fallbackModuleName the fallback's dotted name, or `null` when the request had none.
 * @param fallbackPath the fallback's path under the same root (`app/physics/_nbody_fallback.py`),
 *   or `null`.
 */
data class CompiledExtension(
    val moduleName: String,
    val relativePath: String,
    val fallbackModuleName: String?,
    val fallbackPath: String?,
)

/**
 * What a successful compile produced. A request whose modules did not all build never yields this;
 * see [ModuleCompileException].
 *
 * @param target the canonical target triple.
 * @param toolchain the adapter that built it.
 * @param extensionSuffix the target's `EXT_SUFFIX` for [pythonAbi], read from the target
 *   distribution's own sysconfig data (`.cpython-314-darwin.so`, `.cp314-win_amd64.pyd`,
 *   `.cpython-314-aarch64-linux-android.so`).
 * @param pythonHome the CPython distribution whose headers were used.
 * @param toolVersions the tools that ran, for the manifest (`"cc"` -> `"Apple clang 17.0.0"`).
 */
data class ExtensionCompileResult(
    val outputDir: File,
    val target: String,
    val pythonAbi: PythonAbi,
    val toolchain: NativeToolchain,
    val extensionSuffix: String,
    val pythonHome: File,
    val modules: List<CompiledExtension>,
    val toolVersions: Map<String, String>,
)

/** Where in the pipeline a module failed. */
enum class CompileStage {
    /** C to object: the target's C compiler rejected a translation unit. */
    C_COMPILE,

    /** Objects to extension: the linker failed. */
    LINK,
}

/**
 * One module's failure, with everything a developer needs to act on it.
 *
 * @param source the translation unit that failed for [CompileStage.C_COMPILE]; `null` for
 *   [CompileStage.LINK].
 * @param command the command line that failed, as run.
 * @param exitCode the process exit code, or `null` when the process could not be started.
 * @param output the tool's combined stdout and stderr, untruncated.
 */
data class ModuleCompileFailure(
    val moduleName: String,
    val source: File?,
    val stage: CompileStage,
    val command: List<String>,
    val exitCode: Int?,
    val output: String,
)

/** The failures [ExtensionCompilerInterface] returns inside a failed [Result]. */
sealed class ExtensionCompileException(
    message: String,
) : RuntimeException(message)

/**
 * The target has no backend: no toolchain adapter exists for it, the toolchain is not installed,
 * the host cannot build it, or no CPython headers and `libpython` for [pythonAbi] are available
 * for it. Returned before anything is compiled.
 *
 * @param toolchain the adapter the target maps to, or `null` when none does.
 */
class NoCompileBackendException(
    val target: String,
    val pythonAbi: PythonAbi,
    val toolchain: NativeToolchain?,
    val reason: String,
) : ExtensionCompileException(
        "Cannot build CPython extension modules for target '$target' " +
            "(Python ${pythonAbi.version}${if (pythonAbi.freeThreaded) "t" else ""}): $reason",
    )

/**
 * One or more modules did not build. Every module is attempted, so [failures] lists all of them,
 * not only the first. Nothing is written to the output directory for a failed module.
 */
class ModuleCompileException(
    val failures: List<ModuleCompileFailure>,
) : ExtensionCompileException(
        failures.joinToString("\n\n") { failure ->
            "Module '${failure.moduleName}' failed at ${failure.stage}" +
                (failure.source?.let { " (${it.path})" } ?: "") +
                ", exit ${failure.exitCode ?: "not started"}:\n${failure.output}"
        },
    )

/** The compile slot. Implementations: none yet (#19). */
interface ExtensionCompilerInterface {
    /**
     * Whether this host can build extension modules for [target] and [pythonAbi]: a success, or a
     * failure carrying a [NoCompileBackendException]. Cheap enough to call before bundling starts.
     *
     * @param pythonHome as [ExtensionCompileRequest.pythonHome].
     */
    fun checkSupport(
        target: String,
        pythonAbi: PythonAbi,
        pythonHome: File? = null,
    ): Result<Unit>

    /**
     * Builds every module of [request]. Fails with [NoCompileBackendException] when
     * [checkSupport] would, and with [ModuleCompileException] when any module fails; never
     * succeeds with fewer modules than were asked for.
     */
    fun compile(request: ExtensionCompileRequest): Result<ExtensionCompileResult>
}
