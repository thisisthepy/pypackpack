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
Python source, and has no source-to-C step. Each extension module comes with a plain `.py` fallback
module that runs interpreted when a call cannot keep CPython semantics in C.

## 1. Where the slot sits

```
toolchain                         pypackpack
---------                         ---------------------------------------------------------------
buildTypes { compileLevel }  -->  BundleRequest(buildLevel = "native" | "mixed", nativeModules, ...)
bundleWithPackpack(...)           ResourceBundler.bundle()
                                    1. collect payload (src/main, src/<family>, metaDirs, libDirs)
                                    2. decide what is native, what is bytecode   <- level policy (§4)
                                    3. ExtensionCompilerInterface.compile(ExtensionCompileRequest)
                                         .c -> extension with the target's toolchain
                                         (compile/backend/external/{Clang,NDK,XCode}.kt)
                                    4. merge extensions + fallbacks into python/, bytecode the rest
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
  existing placeholders `Clang.kt`, `NDK.kt`, `XCode.kt`.
- **No Python-to-C translator in pypackpack.** `Cython.kt` and `Nuitka.kt` stay placeholders; the
  slot neither needs nor calls them.
- **Blocking, not `suspend`.** `ResourceBundler.bundle` is blocking and runs in a Gradle task action;
  `bytecode` already shells out with `ProcessBuilder` the same way.

## 2. Inputs

| Input | Field | Notes |
|---|---|---|
| Generated C | `ExtensionModuleSource.sources` | One or more `.c` per module, compiled and linked into one extension. |
| Extension module name | `ExtensionModuleSource.moduleName` | Full dotted name (`app.physics.nbody`). Fixes the placement; the C must define `PyInit_nbody`. |
| `.py` fallback | `ExtensionModuleSource.fallback` = `FallbackModule(moduleName, source)` | Its own dotted name, a sibling of the extension (`app.physics._nbody_fallback`). §3. |
| Include dirs | `ExtensionCompileOptions.includeDirs`, `ExtensionModuleSource.includeDirs` | The consumer's own headers. **The target's CPython include directory is added by the slot**, first, because only pypackpack knows which distribution it built against (§7). |
| Defines | `ExtensionCompileOptions.defines`, `ExtensionModuleSource.defines` | Request-wide, then per module. `Py_GIL_DISABLED` is added by the slot when the ABI needs it and the target's `pyconfig.h` does not set it (Windows). |
| Other flags | `ExtensionCompileOptions.cFlags`, `linkFlags` | Passed verbatim, after the build-type defaults, so they win. |
| Target triple | `ExtensionCompileRequest.target` | Anything `Platforms.normalizeTarget` accepts. |
| CPython ABI | `ExtensionCompileRequest.pythonAbi` | `PythonAbi(version = "3.14", freeThreaded = false)`. Not in `BundleRequest` today (§10 Q3). |
| CPython distribution | `ExtensionCompileRequest.pythonHome` | Optional: an extracted distribution the caller already has. `null` = pypackpack acquires it (§7). |
| Build type | `ExtensionCompileRequest.buildType` | `debug`: `-O0 -g`. `release`: `-O2 -DNDEBUG`, stripped. |
| Android API level | `ExtensionCompileRequest.minSdk` | For the NDK's `--target=aarch64-linux-android<api>`. From `BundleRequest.minSdk`. |
| Working dir | `ExtensionCompileRequest.workingDir` | `BundleRequest.packageDir`. Rule 13: never `user.dir`. |
| Build dir | `ExtensionCompileRequest.buildDir` | Objects and cache. The bundler passes `<package>/build/packpack/compile/<canonical target>/<abi tag>/<buildType>`, so GIL and free-threaded builds never share objects. |
| Output dir | `ExtensionCompileRequest.outputDir` | A tree shaped like the bundle's `python/` root. Absent or empty. |

### How this maps onto `BundleRequest`

`BundleRequest` stays the only thing `toolchain` builds. The proposal adds three optional fields.
They are **not** part of this change, because they alter a public constructor that `toolchain`
compiles against from `mavenLocal` (`AGENTS.md` rule 14):

```kotlin
data class BundleRequest(
    // ... existing fields unchanged ...
    val pythonAbi: PythonAbi? = null,                              // required at native/mixed
    val nativeModules: List<ExtensionModuleSource> = emptyList(),  // TypedPython's generated C + fallbacks
    val nativeOptions: ExtensionCompileOptions = ExtensionCompileOptions(),
)
```

- pypackpack never runs TypedPython; it depends on no other thisisthepy repository (`AGENTS.md`
  rule 12). The Gradle side runs TypedPython and hands its output here.
- At `instant` and `bytecode`, `nativeModules` is ignored: the developer's `.py` runs as written,
  which keeps debug builds hot-reloadable.

## 3. Outputs, placement and import precedence

### From the slot

`ExtensionCompileResult`: the canonical target, the ABI, the toolchain, the target's `EXT_SUFFIX`,
the CPython distribution used, one `CompiledExtension(moduleName, relativePath, fallbackModuleName,
fallbackPath)` per module, and the tool versions. A result never lists fewer modules than were
requested.

### Placement

Every path is valid under both the slot's `outputDir` and the bundle's `python/` root:

| File | `aarch64-apple-darwin`, 3.14 | `aarch64-linux-android`, 3.14 |
|---|---|---|
| Extension | `python/app/physics/nbody.cpython-314-darwin.so` | `python/app/physics/nbody.cpython-314-aarch64-linux-android.so` |
| Fallback | `python/app/physics/_nbody_fallback.py` (+ `.pyc` per the bytecode rules) | same |
| The developer's `nbody.py` | removed | removed |

Other suffixes: `.cpython-314t-darwin.so` (free-threaded), `.cpython-314-x86_64-linux-gnu.so`,
`.cp314-win_amd64.pyd`. The suffix is read from the target distribution's sysconfig data, never
hard-coded.

### Import precedence (decided)

**Exactly one importable file per module name.** CPython's `FileFinder` checks, in one directory,
extension suffixes first, then `.py`, then sourceless `.pyc`; across `sys.path` the first entry
wins. Rather than lean on that order, the bundle never contains two candidates for one name:

1. `import app.physics.nbody` finds only the extension. When an extension replaces a module, the
   developer's `nbody.py` and its `nbody.pyc` are removed from the bundle in both `debug` and
   `release`. The source stays readable in the fallback.
2. The fallback is a separate module with its own name, and the generated C imports it **by name**
   (`PyImport_ImportModule("app.physics._nbody_fallback")`, or relative to `__package__`), lazily,
   on first use. Never by file path: on Android the extension may be extracted out of the APK to a
   different directory (§7), and a path computed from `__file__` would then point at nothing.
3. The fallback is ordinary Python, so the build level's bytecode rules apply to it: `debug` ships
   `.py` + `.pyc`, `release` ships `.pyc` only. No exemption list is needed.
4. The slot fails the request if a fallback name equals its extension's name, is not in the same
   package, or collides with another module in the payload.

This replaces what TypedPython's Cython prototype did (`compiler.py`, commit `ada2fe84`: a sibling
`<stem>_typedpython_interpreted.py` loaded by literal path), which would break under `release`
(the `.py` is stripped) and under Android extraction.

### Manifest entries

`resource-manifest.json` already lists every file under `python/` with size and SHA-256, so the
extensions and fallbacks appear there unchanged. Added, only at `native` or `mixed`:

```json
"pythonAbi": "cp314",
"extensionSuffix": ".cpython-314-darwin.so",
"nativeModules": [
  {
    "module": "app.physics.nbody",
    "path": "python/app/physics/nbody.cpython-314-darwin.so",
    "fallback": "app.physics._nbody_fallback"
  }
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
consumer's generated C, or the prebuilt extensions libraries already ship. The policy:

| Payload | `mixed` | `native` |
|---|---|---|
| Developer module with generated C in `nativeModules` | extension + fallback | extension + fallback |
| Developer module without generated C | bytecode | **refused**, naming every such module |
| Developer `__init__.py` | bytecode | bytecode |
| `libDirs` prebuilt extensions (`.so`, `.pyd`) | carried as-is ("native code (라이브러리)") | carried as-is |
| `libDirs` pure-Python modules | bytecode | bytecode |
| `metaDirs` (`.pyi`, …) | carried as-is | carried as-is |
| Fallbacks | bytecode | bytecode |

- `mixed` is therefore the `bytecode` level plus the consumer's extensions — "developer code as
  bytecode, libraries native", with TypedPython's `@compiled` modules as the developer's explicit
  exceptions.
- `native` refuses rather than quietly shipping bytecode for a module it could not make native;
  "native code only" would otherwise be false. Library pure-Python code and `__init__.py` are the
  stated exceptions, because nothing here can compile them (§10 Q1).
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

Per extension module: the generated `.c` file(s), the dotted module name, the fallback `.py` and its
dotted name, and any headers it needs (TypedPython's runtime support headers, if it has any).
Per request: include dirs, defines, and C/link flags. The consumer does **not** pass CPython's
include directory or `libpython`: it cannot know them per target, and they must match the
distribution the slot links against.

What the generated C must do itself (the slot cannot):

- Define `PyInit_<last component>` and, for a free-threaded ABI, declare `Py_mod_gil =
  Py_MOD_GIL_NOT_USED`; without it, importing the module re-enables the GIL.
- Import its fallback by name, lazily (§3).
- Compile as C11 with the target's compiler: no host-specific headers, no assumption that `long`
  is 64-bit (it is 32-bit on Windows).

## 7. Toolchains, CPython headers and `libpython` per target

This is the central question. The headers must be those of the CPython the app embeds — same
`major.minor`, same flavour — or the extension does not load.

| Target (2026-11 scope) | Toolchain (adapter) | Link mode | Headers and `libpython` from |
|---|---|---|---|
| `aarch64-apple-darwin`; `x86_64-apple-darwin` | `xcrun clang` from the Xcode command-line tools (`Clang.kt`); `-arch` selects the slice | `-bundle -undefined dynamic_lookup`, no `libpython` (as python-build-standalone's own `lib-dynload`) | python-build-standalone `cpython-<ver>+<tag>-<triple>[-freethreaded]-install_only`: `include/python3.14[t]`, sysconfig |
| `x86_64-unknown-linux-gnu`, `aarch64-unknown-linux-gnu` (host arch) | System `clang`, falling back to `cc` (`Clang.kt`) | `-shared -fPIC`, no `libpython` | Same family of archive |
| `x86_64-pc-windows-msvc` | `clang-cl` (`Clang.kt`), which still needs the MSVC libraries and Windows SDK installed (§10 Q6) | `/LD`, links `libs/python314[t].lib`; `/DPy_GIL_DISABLED=1` for free-threaded | Same family of archive |
| `aarch64-linux-android` — only if the NDK backend lands | NDK clang, `--target=aarch64-linux-android<minSdk>` (`NDK.kt`); NDK from `ANDROID_NDK_HOME`, then `$ANDROID_HOME/ndk/<version>` | `-shared -fPIC`, links `libpython3.14.so` (as python.org's Android `lib-dynload`) | python.org `python-<ver>-aarch64-linux-android.tar.gz`: `include/python3.14`, `lib/libpython3.14.so`, `_sysconfigdata__android_aarch64-linux-android.py` |
| iOS | Xcode (`XCode.kt`) | — | out of scope; refused (§8) |

Where the distribution comes from, in order:

1. **`pythonHome`, when the caller passes it.** `toolchain` (or the runtime's Gradle plugin) can
   point at the exact distribution the app embeds. Then the headers are those bytes, by
   construction. The slot still checks version and flavour against `pythonAbi`.
2. **Otherwise pypackpack acquires it** (rule 12: acquiring Python is pypackpack's work) into
   `~/.pypackpack/python/<target>/<version>[t]/`, from the same upstreams the runtime uses
   (`python-multiplatform`'s `docs/platforms/python-version-acquisition.md`: python-build-standalone
   for desktop, python.org for Android).

What exists today is not enough: `dependency/backend/DefaultBackend.installPython` is target-aware,
but accepts only `"3.13"`, downloads 3.13.0 archives from `python-multiplatform`'s stale
`release/binary` directory, has no free-threaded variant, and installs under `findProjectRoot()`
(`user.dir`, the rule 13 defect). The runtime now embeds **3.14.7** (`python-multiplatform`'s
`gradle.properties`). The slot needs an acquisition keyed by `(target, PythonAbi)`; §10 Q4.

The C compiler is not acquired: Xcode command-line tools, a system clang, MSVC/Windows SDK and
the NDK are installed by the developer, and their absence is a refusal that says what was looked
for.

**Android loading.** The resource bundle is staged into `assets/python/`, and Android cannot
`dlopen` from inside the APK. Either the runtime extracts `nativeModules` to the file system before
import (and adds that directory to the package's `__path__`), or `toolchain` stages them through
`jniLibs` (which only packages files named `lib*.so`). §10 Q7.

## 8. A target with no backend: an explicit refusal

`checkSupport(target, abi, pythonHome)` fails with `NoCompileBackendException` naming the target,
the ABI, the toolchain (or none) and the reason:

| Situation | Reason |
|---|---|
| iOS (`arm64-apple-ios*`, `x86_64-apple-ios-simulator`) | the Xcode backend is a placeholder |
| `wasm32-pyodide2024` | no Emscripten backend |
| Android before the NDK backend lands, or `x86_64-linux-android` | no NDK backend / only arm64 is in scope |
| A Linux or Windows target that is not the host | cross-compiling desktop targets is not supported; build on a matching host |
| Toolchain missing | which tool was looked for, and where |
| No distribution for the ABI, or `pythonHome` of another version or flavour | what was expected, what was found |

`ResourceBundler` calls `checkSupport` before writing any output, so a refused variant fails fast,
and with `toolchain`'s per-variant task actions `--continue` still builds the other variants.

## 9. Interface sketch

The full file is
`packpack/src/main/kotlin/org/thisisthepy/python/multiplatform/packpack/compile/extension/ExtensionCompiler.kt`.

```kotlin
enum class NativeToolchain(val id: String) { CLANG("clang"), NDK("ndk"), XCODE("xcode") }

data class PythonAbi(val version: String, val freeThreaded: Boolean = false)

data class FallbackModule(val moduleName: String, val source: File)

data class ExtensionModuleSource(
    val moduleName: String, val sources: List<File>, val fallback: FallbackModule? = null,
    val includeDirs: List<File> = emptyList(), val defines: Map<String, String?> = emptyMap(),
)

data class ExtensionCompileOptions(
    val includeDirs: List<File> = emptyList(), val cFlags: List<String> = emptyList(),
    val defines: Map<String, String?> = emptyMap(), val linkFlags: List<String> = emptyList(),
)

data class ExtensionCompileRequest(
    val modules: List<ExtensionModuleSource>, val target: String, val pythonAbi: PythonAbi,
    val buildType: String, val workingDir: File, val buildDir: File, val outputDir: File,
    val pythonHome: File? = null, val minSdk: Int? = null,
    val options: ExtensionCompileOptions = ExtensionCompileOptions(),
)

data class CompiledExtension(
    val moduleName: String, val relativePath: String,
    val fallbackModuleName: String?, val fallbackPath: String?,
)

data class ExtensionCompileResult(
    val outputDir: File, val target: String, val pythonAbi: PythonAbi, val toolchain: NativeToolchain,
    val extensionSuffix: String, val pythonHome: File,
    val modules: List<CompiledExtension>, val toolVersions: Map<String, String>,
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
    fun checkSupport(target: String, pythonAbi: PythonAbi, pythonHome: File? = null): Result<Unit>
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
    val abi = requireNotNull(request.pythonAbi) { "Build level '${request.buildLevel}' needs BundleRequest.pythonAbi." }
    val compiler = ExtensionCompilerInterface.create()
    compiler.checkSupport(descriptor.canonicalTarget, abi).getOrThrow()
    if (request.buildLevel == "native") requireEveryDeveloperModuleIsNative(payloadWithOrigins, request.nativeModules)

    val staging = File(packageDir, "build/packpack/compile/${descriptor.canonicalTarget}/${abiTag(abi)}/${request.buildType}")
    val out = File(staging, "out").apply { deleteRecursively() }
    val compiled = compiler.compile(
        ExtensionCompileRequest(
            modules = request.nativeModules, target = descriptor.canonicalTarget, pythonAbi = abi,
            buildType = request.buildType, workingDir = packageDir,
            buildDir = File(staging, "work"), outputDir = out,
            minSdk = request.minSdk, options = request.nativeOptions,
        ),
    ).getOrThrow()                                    // the exception survives runCatching

    val pythonRoot = File(outputDir, PYTHON_ROOT)
    removeReplacedSources(pythonRoot, compiled)       // nbody.py goes; one importable file per name
    out.copyRecursively(pythonRoot)                   // extensions + fallback .py
}
// then the existing bytecode pass for mixed/native, which now also covers the fallbacks
```

### How a backend would implement it

`NativeExtensionCompiler` (planned, `compile/extension/`) delegates per target family to the
existing placeholders:

1. **`checkSupport`**: normalise the target; map its family (`macos`/`linux`/`windows` → `Clang.kt`,
   `android` → `NDK.kt`, `ios` → `XCode.kt`, refused in this scope; `wasm` → refused); for Linux and
   Windows require `Platforms.detectHostTarget()` to match; ask the adapter to locate its compiler;
   resolve the distribution (`pythonHome` or acquired) and read its sysconfig: `EXT_SUFFIX`,
   `INCLUDEPY`, `LIBDIR`/`LDLIBRARY`, `Py_GIL_DISABLED` (must equal `freeThreaded`), version (must
   equal `pythonAbi.version`).
2. **Compile**: per translation unit, `<cc> -c <defaults> -I<CPython include> -I<consumer dirs>
   -D<defines> <cFlags> unit.c -o <buildDir>/<module>/<unit>.o`. Skip a unit whose cache key
   (source SHA-256, flags, compiler version, ABI) is unchanged.
3. **Link**: `<cc> <link mode> <objects> [libpython] <linkFlags> -o <outputDir>/<path><EXT_SUFFIX>`;
   strip in `release`.
4. **Fallback**: validate its name (§3 rule 4) and copy it to `<outputDir>/<its path>.py`.
5. Collect every failure; return `ModuleCompileException` if any, otherwise the result.

## 10. Open questions

1. **`native` and code nothing can compile.** Refuse when a developer module has no generated C
   (proposed), or ship it as bytecode? And is it acceptable that library pure-Python code and
   `__init__.py` stay bytecode even at `native`?
2. **Is the fallback mandatory?** The interface allows `null` for other consumers. Should the
   bundler require one for every TypedPython module, and is the naming
   (`_<name>_fallback`, same package) the TypedPython designer's to choose?
3. **Where does the ABI come from?** `BundleRequest` has no Python version. Proposed:
   `BundleRequest.pythonAbi`, set by `toolchain` from the runtime it embeds. The same gap already
   affects `bytecode`: `.pyc` magic numbers come from whatever `.venv` holds (this repository's
   SPEC still says 3.13).
4. **Acquisition of headers and `libpython`.** `toolchain` passes `pythonHome` (the exact embedded
   bytes), or pypackpack downloads by `(target, PythonAbi)` with its own pins — which can drift from
   `python-multiplatform`'s `python-checksums.properties`? Proposed: both, `pythonHome` first. Either
   way `installPython` needs to move past 3.13, gain free-threaded variants and drop `user.dir`.
5. **The GIL / free-threaded path key** (TypedPython #25): the resource output path
   `<type>/<buildType>/<buildLevel>` does not separate flavours. Proposed: intermediates keyed by ABI
   tag, `toolchain` passes a per-variant `outputDir`, and the manifest's `pythonAbi` lets the
   runtime refuse a mismatch. Should the conventional path gain `<abi tag>` as well?
6. **Windows.** `clang-cl` still needs MSVC's libraries and the Windows SDK. Is Windows desktop in
   the 2026-11 scope, and through `clang-cl` or an MSVC adapter?
7. **Android loading.** Who puts `nativeModules` on a real file system — the runtime (extract from
   assets, extend `__path__`) or `toolchain` (stage through `jniLibs`, renamed `lib*.so`)?
8. **macOS cross-arch.** Building `x86_64-apple-darwin` on an arm64 host is cheap with `-arch`;
   allow it, or require a matching host like Linux?
9. **Raw flags.** `cFlags` are passed verbatim and are toolchain-specific. Acceptable, or take
   flags per toolchain, or only an abstract optimisation level?
10. **Reproducibility.** Binaries differ across machines by default, so the manifest's SHA-256 (the
    code-push change detector) will differ too. Add `-ffile-prefix-map` and strip, or accept it?
11. **Shared runtime C.** If TypedPython's generated modules share support code, should it be one
    shared library placed once (and found by every module's loader), or compiled into each
    extension? The interface today links each module from its own units only.
12. **Existing defect, found while reading:** `ResourceBundler` drops `*.pyd` from every input,
    including `libDirs`, so a Windows library's prebuilt extensions never reach the bundle while
    Linux/macOS `.so` files do. Independent of this slot; belongs in `docs/issues/KNOWN_ISSUES.md`.
