package org.thisisthepy.python.multiplatform.packpack.compile.extension

import org.thisisthepy.python.multiplatform.packpack.compile.backend.external.Clang
import org.thisisthepy.python.multiplatform.packpack.compile.backend.external.NDK
import org.thisisthepy.python.multiplatform.packpack.utils.Platforms
import org.thisisthepy.python.multiplatform.packpack.utils.sha256Hex
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/*
 * The compile slot behind the `native` build level: C sources that a consumer generated
 * (TypedPython, from its own typed IR) in, CPython extension modules for one target out, built with
 * that target's C toolchain. (`mixed` ships the developer's modules as bytecode and does not call
 * it; `docs/design/compile-slot.md` section 4.)
 *
 * This is a backend-layer API in the sense of `AGENTS.md` rule 13: every directory it touches is a
 * parameter ([ExtensionCompileRequest.workingDir], [ExtensionCompileRequest.buildDir],
 * [ExtensionCompileRequest.outputDir], [ExtensionCompileRequest.includeDir],
 * [ExtensionCompileRequest.libDir]); nothing reads `user.dir`, and nothing is downloaded: the
 * CPython headers and `libpython` come in through the request, from `python-multiplatform`'s
 * `CPythonIncludeDirectories` providers (`includeDir`, and `libDir` from `python-multiplatform`
 * #56), resolved by the Gradle side. It is blocking, not `suspend`, because its one caller,
 * `ResourceBundler.bundle`, is blocking and runs inside a Gradle task action.
 */

/**
 * The C toolchain adapter that builds for a target family. Each maps onto an adapter
 * under `compile/backend/external/` (`Clang.kt`, `NDK.kt`, `XCode.kt`). For 2026-11: [CLANG] for
 * macOS (`aarch64-apple-darwin`, `x86_64-apple-darwin`) and Linux at host arch; [NDK] for
 * `aarch64-linux-android`; [XCODE] (iOS) is out of scope.
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
 * the bundle: an extension built for 3.14 does not import into 3.15. CPython 3.15+ is free-threaded
 * only (`cp315t` / PEP 803 `abi3t`), so there is no GIL vs free-threaded boolean: only `major.minor`
 * matters; the patch release does not change the ABI.
 *
 * @param version `major.minor`, e.g. `"3.15"` (free-threaded only).
 */
data class PythonAbi(
    val version: String,
)

/**
 * One extension module to build. It becomes exactly one artefact: the extension, which carries the
 * module's source and its interpreted fallback inside it, so nothing is placed beside it.
 *
 * @param moduleName the full dotted import name of the `.py` file it replaces
 *   (`app.physics.nbody`). It fixes the placement (`app/physics/nbody<EXT_SUFFIX>` under the output
 *   root); the C sources must define the matching init function (`PyInit_nbody`). A package's
 *   `__init__` is not accepted in this version.
 * @param sources the module's generated C translation units (`.c`), compiled and linked into one
 *   extension. At least one.
 * @param includeDirs header directories for this module only, after
 *   [ExtensionCompileRequest.includeDir].
 * @param defines preprocessor macros for this module only, added after
 *   [ExtensionCompileOptions.defines]; a `null` value defines the name without a value.
 */
data class ExtensionModuleSource(
    val moduleName: String,
    val sources: List<File>,
    val includeDirs: List<File> = emptyList(),
    val defines: Map<String, String?> = emptyMap(),
)

/**
 * A developer module for which TypedPython produced no C, and why (`docs/design/compile-slot.md` §2, §4).
 *
 * Mirrors TypedPython's `emit-c` manifest entry (`python-multiplatform` `docs/design/typedpython.md` §4.3.2a),
 * so `ResourceBundler` can format build warnings at `native` level without running TypedPython itself.
 */
data class NotNativeModule(
    val module: String,
    val kind: Kind,
    val items: List<SkippedItem> = emptyList(),
) {
    enum class Kind {
        NOT_MARKED,
        NOTHING_COMPILED,
    }
}

/**
 * One function or class that stayed interpreted inside a [NotNativeModule] (`NOTHING_COMPILED`).
 *
 * @param file package-relative path (`app/physics/nbody.py`).
 */
data class SkippedItem(
    val name: String,
    val file: String,
    val line: Int?,
    val message: String,
)

/**
 * Flags shared by every module of one request.
 *
 * The slot applies build-type defaults first (`debug`: no optimisation, debug info; `release`:
 * optimised, `NDEBUG`, stripped) and the target's required flags (position-independent code, the
 * shared-object link mode, `libpython` where the target links it).
 * These flags are appended after the defaults and so win over them.
 *
 * Floating-point flags are semantics, not tuning: every build passes `-ffp-contract=off
 * -fno-fast-math`, so results match CPython's bit for bit (clang contracts multiply-add by default),
 * and no build type ever adds fast-math or contraction. A consumer that wants otherwise says so in
 * [cFlags], which still come last.
 *
 * @param includeDirs header directories for every module (for example a consumer's runtime
 *   headers), after [ExtensionCompileRequest.includeDir].
 * @param cFlags raw clang/gcc flags, passed verbatim. Another toolchain (`clang-cl`, later) does not
 *   receive them verbatim: its adapter maps the floating-point semantics itself (`/fp:precise`,
 *   contraction off).
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
 * @param pythonAbi the runtime's CPython ABI. The slot checks it against [includeDir]'s headers
 *   (`patchlevel.h`'s `PY_VERSION`, and `pyconfig.h` defining `Py_GIL_DISABLED`).
 * @param buildType `debug` / `release`, as in `BundleRequest.buildType`.
 * @param workingDir the ppp package directory (the one holding `pyproject.toml`); the base for
 *   relative paths in diagnostics and for `-ffile-prefix-map=<workingDir>=.`, so no build path ends
 *   up in the binary.
 * @param buildDir intermediates the slot owns: object files and its cache, kept between runs. A
 *   translation unit is recompiled unless its key is unchanged: the SHA-256 of the `.c` and of every
 *   header its depfile (`-MMD`) lists, the full flag list, the compiler's identity and version, the
 *   target, [minSdk] and [pythonAbi]. The caller keys the directory by target and build
 *   type. (TypedPython keeps its own source-to-C cache; the request carries no cache field.)
 * @param outputDir where the results go, shaped like the bundle's `python/` root
 *   (`<outputDir>/app/physics/nbody<EXT_SUFFIX>`). Must be absent or empty.
 * @param includeDir the directory that directly contains `Python.h` for [target] and [pythonAbi]
 *   (`include/python3.15t`), used as the first `-I` as is. The Gradle side takes it from
 *   `python-multiplatform`'s `CPythonIncludeDirectories.includeDir(target)` provider (3.15t is the
 *   only flavour).
 * @param extensionSuffix the target's `EXT_SUFFIX` for [pythonAbi] (`.abi3t-darwin.so`,
 *   `.cpython-315t-darwin.so`, `.cpython-315t-aarch64-linux-android.so`), supplied with [includeDir];
 *   the slot does not derive it.
 * @param libDir the directory holding `libpython`, set only where the target links it
 *   (`isLinkRequired(target)` in `python-multiplatform` #56: Android, later Windows). macOS and
 *   Linux build unlinked and ignore it. An Android or Windows request without it is refused with a
 *   [NoCompileBackendException].
 * @param libraryName the library's file name in [libDir] as `python-multiplatform` #56 reports it
 *   (`libpython3.15t.so`, `python315t.lib`); the slot derives the linker's form (`-lpython3.15t`, or the
 *   `.lib` path). Set together with [libDir].
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
    val includeDir: File,
    val extensionSuffix: String,
    val libDir: File? = null,
    val libraryName: String? = null,
    val minSdk: Int? = null,
    val options: ExtensionCompileOptions = ExtensionCompileOptions(),
)

/**
 * One built extension module: the only artefact for [moduleName].
 *
 * @param relativePath the extension's path under [ExtensionCompileResult.outputDir], `/`-separated
 *   (`app/physics/nbody.abi3t-darwin.so` or `app/physics/nbody.cpython-315t-darwin.so`); the same path is valid under the bundle's
 *   `python/` root.
 */
data class CompiledExtension(
    val moduleName: String,
    val relativePath: String,
)

/**
 * What a successful compile produced. A request whose modules did not all build never yields this;
 * see [ModuleCompileException].
 *
 * @param target the canonical target triple.
 * @param toolchain the adapter that built it.
 * @param extensionSuffix the suffix the extensions were written with, as
 *   [ExtensionCompileRequest.extensionSuffix].
 * @param toolVersions the tools that ran, for the manifest (`"cc"` -> `"Apple clang 17.0.0"`).
 */
data class ExtensionCompileResult(
    val outputDir: File,
    val target: String,
    val pythonAbi: PythonAbi,
    val toolchain: NativeToolchain,
    val extensionSuffix: String,
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
 * The target has no backend: no toolchain adapter exists for it or it is out of scope (iOS,
 * Windows for 2026-11), the toolchain is not installed, the host cannot build it, the request's
 * CPython headers are missing, of another version than [pythonAbi], or missing `Py_GIL_DISABLED`
 * (a GIL build), or the target links `libpython` and the request has no `libDir`/`libraryName`.
 * Returned before anything is compiled.
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
            "(Python ${pythonAbi.version}t): $reason",
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

/** Outcome of one compiler or linker process invocation. */
data class CompileProcessResult(
    val exitCode: Int?,
    val output: String,
)

/** Default process runner used by [Clang] and [NDK]. */
fun runCompileProcess(
    command: List<String>,
    workingDir: File,
    env: Map<String, String>,
): CompileProcessResult {
    val log = File.createTempFile("ppp-ext-cc", ".log")
    return try {
        val process =
            ProcessBuilder(command)
                .directory(workingDir.takeIf { it.isDirectory } ?: File("."))
                .redirectErrorStream(true)
                .redirectOutput(log)
                .apply { environment().putAll(env) }
                .start()
        val finished = process.waitFor(5, TimeUnit.MINUTES)
        if (!finished) {
            process.destroyForcibly()
            CompileProcessResult(null, "Timed out after 5 minutes:\n${log.readText()}")
        } else {
            CompileProcessResult(process.exitValue(), log.readText())
        }
    } catch (e: Exception) {
        CompileProcessResult(null, e.message ?: e.toString())
    } finally {
        log.delete()
    }
}

/** The compile slot (#19, #20). */
interface ExtensionCompilerInterface {
    /**
     * Whether this host can build extension modules for [target] and [pythonAbi]: a success, or a
     * failure carrying a [NoCompileBackendException]. Verifies that `includeDir/Python.h` exists,
     * `patchlevel.h`'s `PY_VERSION` matches `pythonAbi.version`, and `pyconfig.h` defines
     * `Py_GIL_DISABLED` (free-threaded headers required). Cheap enough to call before bundling starts.
     *
     * @param includeDir as [ExtensionCompileRequest.includeDir].
     * @param libDir as [ExtensionCompileRequest.libDir]; required for Android (and, later, Windows).
     * @param libraryName as [ExtensionCompileRequest.libraryName]; required with [libDir].
     */
    fun checkSupport(
        target: String,
        pythonAbi: PythonAbi,
        includeDir: File,
        libDir: File? = null,
        libraryName: String? = null,
    ): Result<Unit>

    /**
     * Builds every module of [request]. Fails with [NoCompileBackendException] when
     * [checkSupport] would, and with [ModuleCompileException] when any module fails; never
     * succeeds with fewer modules than were asked for.
     */
    fun compile(request: ExtensionCompileRequest): Result<ExtensionCompileResult>

    companion object {
        fun create(
            clang: Clang = Clang(),
            ndk: NDK = NDK(),
            hostTarget: () -> String = Platforms::detectHostTarget,
        ): ExtensionCompilerInterface =
            NativeExtensionCompiler(
                clang = clang,
                ndk = ndk,
                hostTarget = hostTarget,
            )
    }
}

/**
 * Default [ExtensionCompilerInterface] implementation (`docs/design/compile-slot.md` §7–§9, #20).
 *
 * Delegates desktop macOS (`aarch64-apple-darwin`, `x86_64-apple-darwin`) and Linux host-arch
 * (`x86_64-unknown-linux-gnu`, `aarch64-unknown-linux-gnu`) to [Clang], and `aarch64-linux-android`
 * to [NDK].
 */
class NativeExtensionCompiler(
    private val clang: Clang = Clang(),
    private val ndk: NDK = NDK(),
    private val hostTarget: () -> String = Platforms::detectHostTarget,
) : ExtensionCompilerInterface {
    override fun checkSupport(
        target: String,
        pythonAbi: PythonAbi,
        includeDir: File,
        libDir: File?,
        libraryName: String?,
    ): Result<Unit> =
        runCatching {
            resolveBackend(target, pythonAbi, includeDir, libDir, libraryName, minSdk = null)
        }

    override fun compile(request: ExtensionCompileRequest): Result<ExtensionCompileResult> =
        runCatching {
            val resolved =
                resolveBackend(
                    target = request.target,
                    pythonAbi = request.pythonAbi,
                    includeDir = request.includeDir,
                    libDir = request.libDir,
                    libraryName = request.libraryName,
                    minSdk = request.minSdk,
                )
            Platforms.requireValidMinSdk(request.minSdk, resolved.family)

            require(request.extensionSuffix.isNotBlank()) { "extensionSuffix must not be blank." }
            val names = request.modules.map { it.moduleName }
            require(names.distinct().size == names.size) { "Duplicate moduleName in request: $names" }
            request.modules.forEach { mod ->
                require(mod.moduleName.isNotBlank() && mod.moduleName.substringAfterLast('.') != "__init__") {
                    "Invalid extension module name '${mod.moduleName}' (__init__ is not supported)."
                }
                require(mod.sources.isNotEmpty()) {
                    "Extension module '${mod.moduleName}' has no C sources."
                }
            }

            if (request.outputDir.exists()) {
                val existing = request.outputDir.listFiles().orEmpty()
                require(existing.isEmpty()) {
                    "outputDir must be absent or empty: ${request.outputDir.absolutePath}"
                }
            } else {
                request.outputDir.mkdirs()
            }
            request.buildDir.mkdirs()

            val failures = mutableListOf<ModuleCompileFailure>()
            val compiled = mutableListOf<CompiledExtension>()

            val linkEnv =
                if (resolved.family == "macos") {
                    mapOf("ZERO_AR_DATE" to "1")
                } else {
                    emptyMap()
                }

            for (module in request.modules) {
                val moduleBuildDir = File(request.buildDir, module.moduleName.replace('.', '/')).apply { mkdirs() }
                val objects = mutableListOf<File>()
                var moduleFailed = false

                val mergedIncludeDirs = request.options.includeDirs + module.includeDirs
                val mergedDefines = LinkedHashMap<String, String?>().apply {
                    putAll(request.options.defines)
                    putAll(module.defines)
                }

                for ((index, source) in module.sources.withIndex()) {
                    val stem = "${source.nameWithoutExtension}_$index"
                    val objFile = File(moduleBuildDir, "$stem.o")
                    val depFile = File(moduleBuildDir, "$stem.d")
                    val keyFile = File(moduleBuildDir, "$stem.key")

                    val compileCmd =
                        when (resolved.toolchain) {
                            NativeToolchain.CLANG ->
                                clang.compileCommand(
                                    compilerCmd = resolved.compilerCmd,
                                    canonicalTarget = resolved.canonicalTarget,
                                    family = resolved.family,
                                    buildType = request.buildType,
                                    workingDir = request.workingDir,
                                    includeDir = request.includeDir,
                                    includeDirs = mergedIncludeDirs,
                                    defines = mergedDefines,
                                    cFlags = request.options.cFlags,
                                    source = source,
                                    objectFile = objFile,
                                    depFile = depFile,
                                )
                            NativeToolchain.NDK ->
                                ndk.compileCommand(
                                    compilerBin = File(resolved.compilerCmd.single()),
                                    buildType = request.buildType,
                                    workingDir = request.workingDir,
                                    includeDir = request.includeDir,
                                    includeDirs = mergedIncludeDirs,
                                    defines = mergedDefines,
                                    cFlags = request.options.cFlags,
                                    source = source,
                                    objectFile = objFile,
                                    depFile = depFile,
                                )
                            NativeToolchain.XCODE -> error("unreachable")
                        }

                    val cachedKey =
                        if (objFile.isFile && depFile.isFile && keyFile.isFile) {
                            computeCacheKey(
                                source = source,
                                depFile = depFile,
                                workingDir = request.workingDir,
                                compileCmd = compileCmd,
                                compilerVersion = resolved.compilerVersion,
                                canonicalTarget = resolved.canonicalTarget,
                                minSdk = request.minSdk,
                                pythonAbi = request.pythonAbi,
                            )
                        } else {
                            null
                        }

                    if (cachedKey != null && keyFile.readText() == cachedKey) {
                        objects += objFile
                        continue
                    }

                    val res = resolved.run(compileCmd, request.workingDir, emptyMap())
                    if (res.exitCode != 0) {
                        objFile.delete()
                        keyFile.delete()
                        failures +=
                            ModuleCompileFailure(
                                moduleName = module.moduleName,
                                source = source,
                                stage = CompileStage.C_COMPILE,
                                command = compileCmd,
                                exitCode = res.exitCode,
                                output = res.output,
                            )
                        moduleFailed = true
                    } else {
                        val newKey =
                            computeCacheKey(
                                source = source,
                                depFile = depFile,
                                workingDir = request.workingDir,
                                compileCmd = compileCmd,
                                compilerVersion = resolved.compilerVersion,
                                canonicalTarget = resolved.canonicalTarget,
                                minSdk = request.minSdk,
                                pythonAbi = request.pythonAbi,
                            )
                        if (newKey != null) {
                            keyFile.writeText(newKey)
                        }
                        objects += objFile
                    }
                }

                if (moduleFailed) continue

                val relativePath = module.moduleName.replace('.', '/') + request.extensionSuffix
                val outFile = File(request.outputDir, relativePath)
                outFile.parentFile?.mkdirs()

                val linkCmd =
                    when (resolved.toolchain) {
                        NativeToolchain.CLANG ->
                            clang.linkCommand(
                                compilerCmd = resolved.compilerCmd,
                                canonicalTarget = resolved.canonicalTarget,
                                family = resolved.family,
                                objects = objects,
                                linkFlags = request.options.linkFlags,
                                outputFile = outFile,
                            )
                        NativeToolchain.NDK ->
                            ndk.linkCommand(
                                compilerBin = File(resolved.compilerCmd.single()),
                                objects = objects,
                                libDir = request.libDir!!,
                                libraryName = request.libraryName!!,
                                linkFlags = request.options.linkFlags,
                                outputFile = outFile,
                            )
                        NativeToolchain.XCODE -> error("unreachable")
                    }

                val linkRes = resolved.run(linkCmd, request.workingDir, linkEnv)
                if (linkRes.exitCode != 0) {
                    outFile.delete()
                    failures +=
                        ModuleCompileFailure(
                            moduleName = module.moduleName,
                            source = null,
                            stage = CompileStage.LINK,
                            command = linkCmd,
                            exitCode = linkRes.exitCode,
                            output = linkRes.output,
                        )
                    continue
                }

                if (request.buildType == "release") {
                    val stripCmd =
                        when (resolved.toolchain) {
                            NativeToolchain.CLANG -> clang.stripCommand(resolved.family, outFile)
                            NativeToolchain.NDK -> ndk.stripBinary(resolved.ndkRoot!!)?.let { listOf(it.path, "--strip-unneeded", outFile.path) }
                            NativeToolchain.XCODE -> null
                        }
                    if (stripCmd != null) {
                        resolved.run(stripCmd, request.workingDir, emptyMap())
                    }
                }

                compiled += CompiledExtension(moduleName = module.moduleName, relativePath = relativePath)
            }

            if (failures.isNotEmpty()) {
                compiled.forEach { File(request.outputDir, it.relativePath).delete() }
                throw ModuleCompileException(failures)
            }

            ExtensionCompileResult(
                outputDir = request.outputDir,
                target = resolved.canonicalTarget,
                pythonAbi = request.pythonAbi,
                toolchain = resolved.toolchain,
                extensionSuffix = request.extensionSuffix,
                modules = compiled,
                toolVersions = mapOf("cc" to resolved.compilerVersion),
            )
        }

    private data class ResolvedBackend(
        val canonicalTarget: String,
        val family: String,
        val toolchain: NativeToolchain,
        val compilerCmd: List<String>,
        val compilerVersion: String,
        val ndkRoot: File? = null,
        val run: (List<String>, File, Map<String, String>) -> CompileProcessResult,
    )

    private fun resolveBackend(
        target: String,
        pythonAbi: PythonAbi,
        includeDir: File,
        libDir: File?,
        libraryName: String?,
        minSdk: Int?,
    ): ResolvedBackend {
        val canonical =
            Platforms.normalizeTarget(target)
                ?: throw NoCompileBackendException(
                    target = target,
                    pythonAbi = pythonAbi,
                    toolchain = null,
                    reason = Platforms.unsupportedTargetMessage(target),
                )
        val descriptor = Platforms.describeTarget(canonical)
        val family = descriptor.family

        val resolved =
            when (family) {
                "ios" ->
                    throw NoCompileBackendException(
                        target = canonical,
                        pythonAbi = pythonAbi,
                        toolchain = NativeToolchain.XCODE,
                        reason = "the Xcode backend is a placeholder (iOS is out of scope).",
                    )
                "wasm" ->
                    throw NoCompileBackendException(
                        target = canonical,
                        pythonAbi = pythonAbi,
                        toolchain = null,
                        reason = "no Emscripten backend for '$canonical'.",
                    )
                "windows" ->
                    throw NoCompileBackendException(
                        target = canonical,
                        pythonAbi = pythonAbi,
                        toolchain = NativeToolchain.CLANG,
                        reason = "Windows is out of scope for 2026-11; planned later through clang-cl and python315t.lib.",
                    )
                "macos" -> {
                    val cmd =
                        clang.compilerFor("macos")
                            ?: throw NoCompileBackendException(
                                target = canonical,
                                pythonAbi = pythonAbi,
                                toolchain = NativeToolchain.CLANG,
                                reason = "Xcode command-line tools ('xcrun clang' or 'clang') were not found on PATH.",
                            )
                    ResolvedBackend(
                        canonicalTarget = canonical,
                        family = family,
                        toolchain = NativeToolchain.CLANG,
                        compilerCmd = cmd,
                        compilerVersion = clang.versionOf(cmd),
                        run = clang.run,
                    )
                }
                "linux" -> {
                    if (canonical != "x86_64-unknown-linux-gnu" && canonical != "aarch64-unknown-linux-gnu") {
                        throw NoCompileBackendException(
                            target = canonical,
                            pythonAbi = pythonAbi,
                            toolchain = NativeToolchain.CLANG,
                            reason = "only x86_64-unknown-linux-gnu and aarch64-unknown-linux-gnu are supported on Linux.",
                        )
                    }
                    val host = runCatching { hostTarget() }.getOrNull()
                    if (host != canonical) {
                        throw NoCompileBackendException(
                            target = canonical,
                            pythonAbi = pythonAbi,
                            toolchain = NativeToolchain.CLANG,
                            reason = "cross-compiling Linux is not supported (host is '${host ?: "unknown"}'); build on a matching host.",
                        )
                    }
                    val cmd =
                        clang.compilerFor("linux")
                            ?: throw NoCompileBackendException(
                                target = canonical,
                                pythonAbi = pythonAbi,
                                toolchain = NativeToolchain.CLANG,
                                reason = "neither 'clang' nor 'cc' was found on PATH.",
                            )
                    ResolvedBackend(
                        canonicalTarget = canonical,
                        family = family,
                        toolchain = NativeToolchain.CLANG,
                        compilerCmd = cmd,
                        compilerVersion = clang.versionOf(cmd),
                        run = clang.run,
                    )
                }
                "android" -> {
                    if (canonical != "aarch64-linux-android") {
                        throw NoCompileBackendException(
                            target = canonical,
                            pythonAbi = pythonAbi,
                            toolchain = NativeToolchain.NDK,
                            reason = "only 'aarch64-linux-android' is in scope for the Android NDK backend.",
                        )
                    }
                    val ndkRoot =
                        ndk.ndkRoot()
                            ?: throw NoCompileBackendException(
                                target = canonical,
                                pythonAbi = pythonAbi,
                                toolchain = NativeToolchain.NDK,
                                reason = "Android NDK not found; looked for ANDROID_NDK_HOME and ANDROID_HOME/ndk/<version>.",
                            )
                    val compilerBin = ndk.compilerBinary(ndkRoot, canonical, minSdk)
                    if (!compilerBin.isFile) {
                        throw NoCompileBackendException(
                            target = canonical,
                            pythonAbi = pythonAbi,
                            toolchain = NativeToolchain.NDK,
                            reason = "NDK compiler '${compilerBin.path}' does not exist.",
                        )
                    }
                    if (libDir == null || libraryName.isNullOrBlank() || !File(libDir, libraryName).isFile) {
                        throw NoCompileBackendException(
                            target = canonical,
                            pythonAbi = pythonAbi,
                            toolchain = NativeToolchain.NDK,
                            reason = "target '$canonical' links libpython: pass libDir and libraryName from python-multiplatform's libDir(target) provider.",
                        )
                    }
                    ResolvedBackend(
                        canonicalTarget = canonical,
                        family = family,
                        toolchain = NativeToolchain.NDK,
                        compilerCmd = listOf(compilerBin.path),
                        compilerVersion = ndk.versionOf(compilerBin),
                        ndkRoot = ndkRoot,
                        run = ndk.run,
                    )
                }
                else ->
                    throw NoCompileBackendException(
                        target = canonical,
                        pythonAbi = pythonAbi,
                        toolchain = null,
                        reason = "no backend for family '$family'.",
                    )
            }

        verifyHeaders(canonical, pythonAbi, resolved.toolchain, includeDir)
        return resolved
    }

    private fun verifyHeaders(
        canonicalTarget: String,
        pythonAbi: PythonAbi,
        toolchain: NativeToolchain,
        includeDir: File,
    ) {
        val pythonH = File(includeDir, "Python.h")
        if (!pythonH.isFile) {
            throw NoCompileBackendException(
                target = canonicalTarget,
                pythonAbi = pythonAbi,
                toolchain = toolchain,
                reason = "Python.h not found in includeDir '${includeDir.path}'.",
            )
        }
        val patchlevel = File(includeDir, "patchlevel.h")
        if (!patchlevel.isFile) {
            throw NoCompileBackendException(
                target = canonicalTarget,
                pythonAbi = pythonAbi,
                toolchain = toolchain,
                reason = "patchlevel.h not found in includeDir '${includeDir.path}'.",
            )
        }
        val pyVersion =
            Regex("""#\s*define\s+PY_VERSION\s+"([^"]+)"""")
                .find(patchlevel.readText())
                ?.groupValues
                ?.get(1)
        if (pyVersion == null || (!pyVersion.startsWith("${pythonAbi.version}.") && pyVersion != pythonAbi.version)) {
            throw NoCompileBackendException(
                target = canonicalTarget,
                pythonAbi = pythonAbi,
                toolchain = toolchain,
                reason = "includeDir '${includeDir.path}' has PY_VERSION '${pyVersion ?: "missing"}', expected Python ${pythonAbi.version}.",
            )
        }
        val pyconfig = File(includeDir, "pyconfig.h")
        val gilDisabled =
            pyconfig.isFile &&
                Regex("""#\s*define\s+Py_GIL_DISABLED\s+1\b""").containsMatchIn(pyconfig.readText())
        if (!gilDisabled) {
            throw NoCompileBackendException(
                target = canonicalTarget,
                pythonAbi = pythonAbi,
                toolchain = toolchain,
                reason = "includeDir '${includeDir.path}' does not define Py_GIL_DISABLED 1 in pyconfig.h (free-threaded 3.15t headers required).",
            )
        }
    }

    private fun computeCacheKey(
        source: File,
        depFile: File,
        workingDir: File,
        compileCmd: List<String>,
        compilerVersion: String,
        canonicalTarget: String,
        minSdk: Int?,
        pythonAbi: PythonAbi,
    ): String? {
        if (!source.isFile || !depFile.isFile) return null
        val deps = parseDepFile(depFile, workingDir)
        if (deps.isEmpty()) return null
        val digest = MessageDigest.getInstance("SHA-256")
        fun updateLine(s: String) {
            digest.update(s.toByteArray())
            digest.update('\n'.code.toByte())
        }
        updateLine("target=$canonicalTarget")
        updateLine("minSdk=${minSdk ?: ""}")
        updateLine("abi=${pythonAbi.version}")
        updateLine("cc=$compilerVersion")
        updateLine("cmd=${compileCmd.joinToString("\u0000")}")
        updateLine("src=${source.canonicalFile.path}:${sha256Hex(source)}")
        for (dep in deps) {
            if (!dep.isFile) return null
            updateLine("dep=${dep.canonicalFile.path}:${sha256Hex(dep)}")
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun parseDepFile(
        depFile: File,
        workingDir: File,
    ): List<File> {
        val unfolded = depFile.readText().replace("\\\r\n", " ").replace("\\\n", " ")
        val colon = unfolded.indexOf(':')
        if (colon < 0) return emptyList()
        val rhs = unfolded.substring(colon + 1)
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < rhs.length) {
            val c = rhs[i]
            if (c == '\\' && i + 1 < rhs.length && rhs[i + 1] == ' ') {
                current.append(' ')
                i += 2
            } else if (c.isWhitespace()) {
                if (current.isNotEmpty()) {
                    tokens += current.toString()
                    current.setLength(0)
                }
                i++
            } else {
                current.append(c)
                i++
            }
        }
        if (current.isNotEmpty()) {
            tokens += current.toString()
        }
        return tokens.map { token ->
            val f = File(token)
            if (f.isAbsolute) f else File(workingDir, token)
        }
    }
}
