# The native/mixed compile slot

Issue: [#19](https://github.com/thisisthepy/pypackpack/issues/19). Status: **draft for review**,
due 2026-10-24. First consumer: TypedPython (`python-multiplatform`, issue #25 there, milestone M3).

This note proposes the interface that the `native` and `mixed` build levels call. It is a design
record, not specification: nothing in `docs/SPEC.md` changes until the open questions in
[§10](#10-open-questions) are answered. The only code that comes with it is the interface,
`packpack/src/main/kotlin/org/thisisthepy/python/multiplatform/packpack/compile/extension/ExtensionCompiler.kt`
(types and signatures, no implementation, no caller).

**Decided before this draft (user, through the project lead):** TypedPython does not use Cython. It
generates C directly from its own typed IR. The slot therefore takes **generated C**, not `.pyx` or
Python source, and has no source-to-C step.

**Decided in review (issue #19 comments, 2026-10-03), and folded into this revision:**

- **Headers are an input.** The slot does not locate, download or acquire CPython headers. The
  request carries `includeDir` (the directory that directly contains `Python.h`) and
  `extensionSuffix`. The Gradle side fills them from `python-multiplatform`'s
  `CPythonIncludeDirectories.includeDir(target, flavour)` provider (`python-multiplatform` #46,
  `docs/platforms/python-version-acquisition.md` §7), so the extraction task dependency comes with it.
- **Linking per target.** macOS and Linux build unlinked (`-undefined dynamic_lookup` on macOS).
  Android links `libpython3.14.so`; Windows needs `python314.lib`. For those the request carries
  `libDir` and `libraryName`, from the `libDir(target, flavour)` provider and library name that
  `python-multiplatform` #56 adds next to `includeDir` (with `isLinkRequired(target)`, false for
  macOS, Linux and iOS).
- **No `.py` fallback artefact.** The extension embeds the module's source and keeps an
  interpreted fallback per function inside the `.so`. One artefact per compiled module, named by the
  module's dotted name.
- **Modules with no C are not refused.** TypedPython lists exactly the modules it produced C for;
  every other module ships at the bytecode level.
- **Windows / `clang-cl` is out of scope for 2026-11.** M3 needs one desktop target, macOS first.

## 1. Where the slot sits

```
toolchain                         pypackpack
---------                         ---------------------------------------------------------------
buildTypes { compileLevel }  -->  BundleRequest(buildLevel = "native" | "mixed", nativeModules,
python-multiplatform providers      includeDir, extensionSuffix, libDir?, libraryName?, ...)
  includeDir / libDir         -->
bundleWithPackpack(...)           ResourceBundler.bundle()
                                    1. collect payload (src/main, src/<family>, metaDirs, libDirs)
                                    2. decide what is native, what is bytecode   <- level policy (§4)
                                    3. ExtensionCompilerInterface.compile(ExtensionCompileRequest)
                                         .c -> extension with the target's toolchain
                                         (compile/backend/external/{Clang,NDK}.kt)
                                    4. replace each compiled module's .py with its extension,
                                       bytecode the rest
                                    5. write resource-manifest.json
```

- **Reached only through `BundleRequest`.** `toolchain` already passes `compileLevel` through as
  `BundleRequest.buildLevel` (`toolchain`'s `BuildPythonArtifactTask.bundleWithPackpack`) and today
  rejects `bytecode`/`native`/`mixed` itself (`toolchain`'s `docs/SPEC.md` §1.6). No new entry point.
- **The slot is level-agnostic.** It builds the modules it is given. Which modules those are is the
  bundler's policy (§4), so a change to what `mixed` means never changes the slot's signature, and
  the wheel bundlers (`single`, `fat`, `patch`) can reuse it later.
- **Not the existing `compile/backend/BackendInterface`.** That compiles a whole package by name
  through Meson, finds the workspace through `user.dir` (a known defect, `AGENTS.md` rule 13) and
  returns `Result<String>`. The slot needs explicit directories, a target, an ABI and a structured
  result, so it lives in a new package, `compile/extension`. The per-target work goes into the
  existing placeholders `Clang.kt` and `NDK.kt`.
- **No Python-to-C translator in pypackpack.** `Cython.kt` and `Nuitka.kt` stay placeholders; the
  slot neither needs nor calls them.
- **No CPython acquisition in the slot.** It never calls `dependency/backend/DefaultBackend.installPython`
  and downloads nothing. Every CPython file it reads comes in through the request.
- **Blocking, not `suspend`.** `ResourceBundler.bundle` is blocking and runs in a Gradle task action;
  `bytecode` already shells out with `ProcessBuilder` the same way.

## 2. Inputs

| Input | Field | Notes |
|---|---|---|
| Generated C | `ExtensionModuleSource.sources` | One or more `.c` per module, compiled and linked into one extension. |
| Extension module name | `ExtensionModuleSource.moduleName` | Full dotted name (`app.physics.nbody`), the `.py` file's own module name. Fixes the placement; the C must define `PyInit_nbody`. |
| CPython headers | `ExtensionCompileRequest.includeDir` | The directory directly containing `Python.h` (`include/python3.14`, `include/python3.14t`, Windows `include/`). Used as the first `-I`, as is. From `CPythonIncludeDirectories.includeDir(target, flavour)`. |
| Extension suffix | `ExtensionCompileRequest.extensionSuffix` | The target's `EXT_SUFFIX` for the ABI (`.cpython-314-darwin.so`). Supplied by the Gradle side with `includeDir`; the slot does not derive it. |
| `libpython` directory | `ExtensionCompileRequest.libDir` | Only where the target links `libpython` (Android, later Windows); `null` elsewhere. From `libDir(target, flavour)` (`python-multiplatform` #56). |
| `libpython` name | `ExtensionCompileRequest.libraryName` | The library file in `libDir` as #56 reports it (`libpython3.14.so`, `python314.lib`); the slot turns it into the linker's form (`-lpython3.14`, or the `.lib` path). Set together with `libDir`. |
| Include dirs | `ExtensionCompileOptions.includeDirs`, `ExtensionModuleSource.includeDirs` | The consumer's own headers, after `includeDir`. |
| Defines | `ExtensionCompileOptions.defines`, `ExtensionModuleSource.defines` | Request-wide, then per module. |
| Other flags | `ExtensionCompileOptions.cFlags`, `linkFlags` | Passed verbatim, after the build-type defaults, so they win. |
| Target triple | `ExtensionCompileRequest.target` | Anything `Platforms.normalizeTarget` accepts. |
| CPython ABI | `ExtensionCompileRequest.pythonAbi` | `PythonAbi(version = "3.14", freeThreaded = false)`. Cross-checked against `includeDir` (§9). |
| Build type | `ExtensionCompileRequest.buildType` | `debug`: `-O0 -g`. `release`: `-O2 -DNDEBUG`, stripped. |
| Android API level | `ExtensionCompileRequest.minSdk` | For the NDK's `--target=aarch64-linux-android<api>`. From `BundleRequest.minSdk`. |
| Working dir | `ExtensionCompileRequest.workingDir` | `BundleRequest.packageDir`. Rule 13: never `user.dir`. |
| Build dir | `ExtensionCompileRequest.buildDir` | Objects and cache. The bundler passes `<package>/build/packpack/compile/<canonical target>/<abi tag>/<buildType>`, so GIL and free-threaded builds never share objects. |
| Output dir | `ExtensionCompileRequest.outputDir` | A tree shaped like the bundle's `python/` root. Absent or empty. |

### How this maps onto `BundleRequest`

`BundleRequest` stays the only thing `toolchain` builds. The proposal adds optional fields. They are
**not** part of this change, because they alter a public constructor that `toolchain` compiles
against from `mavenLocal` (`AGENTS.md` rule 14):

```kotlin
data class BundleRequest(
    // ... existing fields unchanged ...
    val pythonAbi: PythonAbi? = null,                              // required at native/mixed
    val nativeModules: List<ExtensionModuleSource> = emptyList(),  // exactly the modules TypedPython produced C for
    val nativeOptions: ExtensionCompileOptions = ExtensionCompileOptions(),
    val cpythonIncludeDir: File? = null,                           // required at native/mixed
    val extensionSuffix: String? = null,                           // required at native/mixed
    val cpythonLibDir: File? = null,                               // when isLinkRequired(target)
    val cpythonLibraryName: String? = null,                        // when isLinkRequired(target)
)
```

- pypackpack never runs TypedPython and never calls `python-multiplatform`'s providers; it depends
  on no other thisisthepy repository (`AGENTS.md` rule 12). The Gradle side runs TypedPython,
  resolves the providers (which carries their task dependencies) and hands the results here.
- At `instant` and `bytecode`, `nativeModules` and the CPython fields are ignored: the developer's
  `.py` runs as written, which keeps debug builds hot-reloadable.

## 3. Outputs and placement

### From the slot

`ExtensionCompileResult`: the canonical target, the ABI, the toolchain, the extension suffix used,
one `CompiledExtension(moduleName, relativePath)` per module, and the tool versions. A result never
lists fewer modules than were requested.

### Placement

One artefact per compiled module, at the module's dotted name. Every path is valid under both the
slot's `outputDir` and the bundle's `python/` root:

| File | `aarch64-apple-darwin`, 3.14 | `aarch64-linux-android`, 3.14 |
|---|---|---|
| Extension | `python/app/physics/nbody.cpython-314-darwin.so` | `python/app/physics/nbody.cpython-314-aarch64-linux-android.so` |
| The developer's `nbody.py` / `nbody.pyc` | not shipped | not shipped |

Other suffixes: `.cpython-314t-darwin.so` (free-threaded), `.cpython-314-x86_64-linux-gnu.so`. The
suffix is the request's `extensionSuffix`, never hard-coded.

The extension carries the module's source and its interpreted fallback per function inside the
`.so`, so nothing else ships for that module. The bundler leaves the developer's `.py` (and the
`.pyc` the bytecode pass would make) out of the bundle in both `debug` and `release`, so a module
name has exactly one importable file.

### Manifest entries

`resource-manifest.json` already lists every file under `python/` with size and SHA-256, so the
extensions appear there unchanged. Added, only at `native` or `mixed`:

```json
"pythonAbi": "cp314",
"extensionSuffix": ".cpython-314-darwin.so",
"nativeModules": [
  { "module": "app.physics.nbody", "path": "python/app/physics/nbody.cpython-314-darwin.so" }
],
"tools": { "cc": "Apple clang 17.0.0" }
```

The keys are additive, so `formatVersion` stays `1`. `pythonAbi` lets the runtime refuse a bundle of
the other flavour instead of crashing in `dlopen`; `nativeModules` tells it which files need a real
file system on Android.

## 4. What `native` and `mixed` mean

`toolchain`'s example build file `(플러그인예시)build.gradle.kts` is user-authored, so it is the
specification (`AGENTS.md` rule 6). On `release` it says:

> `compileLevel = "native"  // (native code only) or "mixed" (byte code (개발자 코드) + native code (라이브러리))`

pypackpack has no Python-to-C translator, so "native" can only mean C that somebody supplied: the
consumer's generated C, or the prebuilt extensions libraries already ship. TypedPython lists exactly
the modules it produced C for, so both levels mean **native where TypedPython produced C, bytecode
everywhere else**:

| Payload | `mixed` | `native` |
|---|---|---|
| Developer module listed in `nativeModules` | extension (its `.py` not shipped) | extension (its `.py` not shipped) |
| Developer module not listed | bytecode | bytecode |
| Developer `__init__.py` | bytecode | bytecode |
| `libDirs` prebuilt extensions (`.so`) | carried as-is ("native code (라이브러리)") | carried as-is |
| `libDirs` pure-Python modules | bytecode | bytecode |
| `metaDirs` (`.pyi`, …) | carried as-is | carried as-is |

- Neither level refuses a module that has no C. A listed module whose C fails to build is still a
  failure (§5); it is never silently shipped as bytecode.
- The two levels currently produce the same bundle. Whether anything should tell them apart is
  §10 Q1.
- To apply this the bundler must remember each payload file's origin; `collectPayload` currently
  merges all origins into one map.

## 5. Error reporting

All failures come back as a failed `Result` — never a throw out of `compile`, never `TODO()`
(`AGENTS.md` rule 15), never a success with fewer modules than were asked for.

- `NoCompileBackendException(target, pythonAbi, toolchain, reason)` — the refusal (§8). Returned
  by `checkSupport`, and by `compile` before anything runs.
- `ModuleCompileException(failures)` — every module is attempted, then all failures are reported
  together. Each `ModuleCompileFailure` carries the module name, the translation unit (for
  `C_COMPILE`), the stage (`C_COMPILE` or `LINK`), the exact command, the exit code, and the
  compiler's full output. The message lists each module with its output.

`ResourceBundler.bundle` already wraps everything in `runCatching`, so the exception reaches
`toolchain` as the cause of its `GradleException("packpack resource bundling failed: ...")`, with the
module named in the message.

## 6. What the consumer hands over

Per extension module: the generated `.c` file(s), the dotted module name, and any headers it needs
(TypedPython's runtime support headers, if it has any). Per request: include dirs, defines, and C/link
flags. The Gradle side, not TypedPython, adds the CPython inputs from `python-multiplatform`'s
providers: `includeDir`, `extensionSuffix`, and on targets where `isLinkRequired(target)` is true,
`libDir` and `libraryName`.

What the generated C must do itself (the slot cannot):

- Define `PyInit_<last component>` and, for a free-threaded ABI, declare `Py_mod_gil =
  Py_MOD_GIL_NOT_USED`; without it, importing the module re-enables the GIL.
- Carry its own interpreted fallback; the slot places nothing beside the extension.
- Compile as portable C11 with the target's compiler: no host-specific headers. (When Windows comes
  into scope: `long` is 32-bit there.)

## 7. Toolchains and linking per target

The headers must be those of the CPython the app embeds — same `major.minor`, same flavour — or the
extension does not load. Taking them from the same provider the runtime is built from makes that
true by construction.

| Target | Scope | Toolchain (adapter) | Link mode | CPython inputs |
|---|---|---|---|---|
| `aarch64-apple-darwin`; `x86_64-apple-darwin` | **2026-11 (M3), first** | `xcrun clang` from the Xcode command-line tools (`Clang.kt`); `-arch` selects the slice | `-bundle -undefined dynamic_lookup`, no `libpython` | `includeDir`, `extensionSuffix` |
| `x86_64-unknown-linux-gnu`, `aarch64-unknown-linux-gnu` (host arch) | after macOS | System `clang`, falling back to `cc` (`Clang.kt`) | `-shared -fPIC`, no `libpython` (the CPython convention) | `includeDir`, `extensionSuffix` |
| `aarch64-linux-android` | only if the NDK backend lands | NDK clang, `--target=aarch64-linux-android<minSdk>` (`NDK.kt`); NDK from `ANDROID_NDK_HOME`, then `$ANDROID_HOME/ndk/<version>` | `-shared -fPIC -L<libDir> -lpython3.14` (Bionic does not resolve undefined symbols from the host process) | `includeDir`, `extensionSuffix`, `libDir` (`prefix/lib`), `libraryName` (`libpython3.14.so`) |
| `x86_64-pc-windows-msvc` | **later**; refused for 2026-11 | `clang-cl` (`Clang.kt`), with the MSVC libraries and Windows SDK | `/LD`, links `python314[t].lib`; `/DPy_GIL_DISABLED=1` for free-threaded | `includeDir`, `extensionSuffix`, `libDir` (`python/libs`), `libraryName` (`python314.lib`) |
| iOS | out of scope | Xcode (`XCode.kt`) | — | refused (§8) |

The C compiler is not acquired: the Xcode command-line tools, a system clang and the NDK are
installed by the developer, and their absence is a refusal that says what was looked for.

On a target that does not link `libpython`, `libDir` and `libraryName` are ignored.

**Android loading.** The resource bundle is staged into `assets/python/`, and Android cannot
`dlopen` from inside the APK. Either the runtime extracts `nativeModules` to the file system before
import (and adds that directory to the package's `__path__`), or `toolchain` stages them through
`jniLibs` (which only packages files named `lib*.so`). §10 Q3.

## 8. A target with no backend: an explicit refusal

`checkSupport(target, pythonAbi, includeDir, libDir, libraryName)` fails with
`NoCompileBackendException` naming the target, the ABI, the toolchain (or none) and the reason:

| Situation | Reason |
|---|---|
| iOS (`arm64-apple-ios*`, `x86_64-apple-ios-simulator`) | the Xcode backend is a placeholder |
| `wasm32-pyodide2024` | no Emscripten backend |
| Windows | out of scope for 2026-11; planned later through `clang-cl` and `python314.lib` |
| Android before the NDK backend lands, or `x86_64-linux-android` | no NDK backend / only arm64 is in scope |
| Android (or, later, Windows) with `libDir` or `libraryName` missing, or the named library absent from `libDir` | "target '…' links libpython: pass libDir and libraryName from python-multiplatform's libDir(target, flavour) provider" |
| A Linux target that is not the host | cross-compiling desktop targets is not supported; build on a matching host |
| Toolchain missing | which tool was looked for, and where |
| `includeDir` without `Python.h`, or headers of another version or flavour than `pythonAbi` | what was expected, what was found |

`ResourceBundler` calls `checkSupport` before writing any output, so a refused variant fails fast,
and with `toolchain`'s per-variant task actions `--continue` still builds the other variants.

## 9. Interface sketch

The full file is
`packpack/src/main/kotlin/org/thisisthepy/python/multiplatform/packpack/compile/extension/ExtensionCompiler.kt`.

```kotlin
enum class NativeToolchain(val id: String) { CLANG("clang"), NDK("ndk"), XCODE("xcode") }

data class PythonAbi(val version: String, val freeThreaded: Boolean = false)

data class ExtensionModuleSource(
    val moduleName: String, val sources: List<File>,
    val includeDirs: List<File> = emptyList(), val defines: Map<String, String?> = emptyMap(),
)

data class ExtensionCompileOptions(
    val includeDirs: List<File> = emptyList(), val cFlags: List<String> = emptyList(),
    val defines: Map<String, String?> = emptyMap(), val linkFlags: List<String> = emptyList(),
)

data class ExtensionCompileRequest(
    val modules: List<ExtensionModuleSource>, val target: String, val pythonAbi: PythonAbi,
    val buildType: String, val workingDir: File, val buildDir: File, val outputDir: File,
    val includeDir: File, val extensionSuffix: String,
    val libDir: File? = null, val libraryName: String? = null,
    val minSdk: Int? = null, val options: ExtensionCompileOptions = ExtensionCompileOptions(),
)

data class CompiledExtension(val moduleName: String, val relativePath: String)

data class ExtensionCompileResult(
    val outputDir: File, val target: String, val pythonAbi: PythonAbi, val toolchain: NativeToolchain,
    val extensionSuffix: String, val modules: List<CompiledExtension>, val toolVersions: Map<String, String>,
)

enum class CompileStage { C_COMPILE, LINK }

data class ModuleCompileFailure(
    val moduleName: String, val source: File?, val stage: CompileStage,
    val command: List<String>, val exitCode: Int?, val output: String,
)

sealed class ExtensionCompileException(message: String) : RuntimeException(message)
class NoCompileBackendException(target, pythonAbi, toolchain: NativeToolchain?, reason) : ExtensionCompileException
class ModuleCompileException(failures: List<ModuleCompileFailure>) : ExtensionCompileException

interface ExtensionCompilerInterface {
    fun checkSupport(
        target: String, pythonAbi: PythonAbi, includeDir: File,
        libDir: File? = null, libraryName: String? = null,
    ): Result<Unit>
    fun compile(request: ExtensionCompileRequest): Result<ExtensionCompileResult>
}
```

When an implementation lands, a factory follows the existing pattern
(`ExtensionCompilerInterface.create()`). Until then nothing reaches the interface, and
`ResourceBundler` keeps refusing `native`/`mixed` exactly as today.

### How `ResourceBundler` would call it

```kotlin
// inside bundle(), after writePayload(...)
if (request.buildLevel == "native" || request.buildLevel == "mixed") {
    val level = request.buildLevel
    val abi = requireNotNull(request.pythonAbi) { "Build level '$level' needs BundleRequest.pythonAbi." }
    val includeDir = requireNotNull(request.cpythonIncludeDir) { "Build level '$level' needs BundleRequest.cpythonIncludeDir." }
    val suffix = requireNotNull(request.extensionSuffix) { "Build level '$level' needs BundleRequest.extensionSuffix." }
    val compiler = ExtensionCompilerInterface.create()
    compiler.checkSupport(descriptor.canonicalTarget, abi, includeDir, request.cpythonLibDir, request.cpythonLibraryName)
        .getOrThrow()

    val staging = File(packageDir, "build/packpack/compile/${descriptor.canonicalTarget}/${abiTag(abi)}/${request.buildType}")
    val out = File(staging, "out").apply { deleteRecursively() }
    val compiled = compiler.compile(
        ExtensionCompileRequest(
            modules = request.nativeModules, target = descriptor.canonicalTarget, pythonAbi = abi,
            includeDir = includeDir, extensionSuffix = suffix,
            libDir = request.cpythonLibDir, libraryName = request.cpythonLibraryName,
            buildType = request.buildType, workingDir = packageDir,
            buildDir = File(staging, "work"), outputDir = out,
            minSdk = request.minSdk, options = request.nativeOptions,
        ),
    ).getOrThrow()                                    // the exception survives runCatching

    val pythonRoot = File(outputDir, PYTHON_ROOT)
    removeReplacedSources(pythonRoot, compiled)       // nbody.py goes; one artefact per compiled module
    out.copyRecursively(pythonRoot)                   // the extensions
}
// then the existing bytecode pass for every module that was not compiled
```

### How a backend would implement it

`NativeExtensionCompiler` (planned, `compile/extension/`) delegates per target family to the
existing placeholders:

1. **`checkSupport`**: normalise the target; map its family (`macos`/`linux` → `Clang.kt`,
   `android` → `NDK.kt`; `windows`, `ios`, `wasm` → refused in this scope); for Linux require
   `Platforms.detectHostTarget()` to match; ask the adapter to locate its compiler; check that
   `includeDir/Python.h` exists, that `patchlevel.h`'s `PY_VERSION` matches `pythonAbi.version`, and
   that `pyconfig.h`'s `Py_GIL_DISABLED` matches `freeThreaded`; on Android require `libDir` and
   `libraryName` and that `libDir/<libraryName>` exists.
2. **Compile**: per translation unit, `<cc> -c <defaults> -I<includeDir> -I<consumer dirs>
   -D<defines> <cFlags> unit.c -o <buildDir>/<module>/<unit>.o`. Skip a unit whose cache key
   (source SHA-256, flags, compiler version, ABI) is unchanged.
3. **Link**: `<cc> <link mode> <objects> [-L<libDir> -l<name>] <linkFlags> -o
   <outputDir>/<path><extensionSuffix>`; strip in `release`.
4. Collect every failure; return `ModuleCompileException` if any, otherwise the result.

## 10. Open questions

1. **What tells `native` and `mixed` apart?** With modules without C shipped as bytecode at both
   levels, they produce the same bundle. Keep them as synonyms, or does one of them change (for
   example `mixed` = libraries' prebuilt extensions only, without TypedPython's)?
2. **The GIL / free-threaded output path** (TypedPython #25): the resource output path
   `<type>/<buildType>/<buildLevel>` does not separate flavours. Proposed: intermediates keyed by ABI
   tag, `toolchain` passes a per-variant `outputDir`, and the manifest's `pythonAbi` lets the runtime
   refuse a mismatch. Should the conventional path gain `<abi tag>` as well? (Only the flavour
   selected by `-PpythonFreeThreaded` has headers, so one build produces one flavour.)
3. **Android `.so` extraction.** Who puts `nativeModules` on a real file system — the runtime
   (extract from assets, extend `__path__`) or `toolchain` (stage through `jniLibs`, renamed
   `lib*.so`)? Only matters once the NDK backend lands.
4. **Reproducibility.** Binaries differ across machines by default, so the manifest's SHA-256 (the
   code-push change detector) will differ too. Add `-ffile-prefix-map`, strip, and a fixed
   `ZERO_AR_DATE`/`SOURCE_DATE_EPOCH` on macOS, or accept it?
5. **Where does the ABI come from?** `BundleRequest` has no Python version. Proposed:
   `BundleRequest.pythonAbi`, set by `toolchain` from the same version and flavour it asks the
   providers for; the slot cross-checks it against `includeDir`'s headers. The same gap already
   affects `bytecode`: `.pyc` magic numbers come from whatever `.venv` holds (this repository's SPEC
   still says 3.13).
6. **macOS cross-arch.** Building `x86_64-apple-darwin` on an arm64 host is cheap with `-arch`;
   allow it, or require a matching host like Linux?
7. **Raw flags.** `cFlags` are passed verbatim and are toolchain-specific. Acceptable, or take flags
   per toolchain, or only an abstract optimisation level?
8. **Shared runtime C.** If TypedPython's generated modules share support code, should it be one
   shared library placed once (and found by every module's loader), or compiled into each
   extension? The interface today links each module from its own units only.
9. **The `libraryName` form.** This draft takes the file name in `libDir` as `python-multiplatform`
   #56 reports it and derives the linker flag. Settle once #56 lands.

**Closed in review (2026-10-03):** whether `native` refuses modules without C (no: bytecode); whether
a `.py` fallback is mandatory and how it is named (no fallback artefact: it lives inside the `.so`);
how headers and `libpython` are acquired (inputs from `python-multiplatform`'s providers, §7);
Windows and `clang-cl` (out of scope for 2026-11, macOS first; Windows later).

**Existing defect, found while reading:** `ResourceBundler` drops `*.pyd` from every input,
including `libDirs`, so a Windows library's prebuilt extensions never reach the bundle while
Linux/macOS `.so` files do. Independent of this slot; belongs in `docs/issues/KNOWN_ISSUES.md`.
