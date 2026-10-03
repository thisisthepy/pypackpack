# PyPackPack Spec Sheet

## How to read this file

Every feature item carries one `Status:` line:

- `implemented`: the behaviour exists and a test in this repository exercises it. The test is cited.
- `partial`: the core flow exists, but part of the documented behaviour is missing, or it is wired
  with no test in this repository. What is missing is listed under *Limitation*.
- `planned`: not reflected in code yet (an empty placeholder file counts as not reflected), but a
  direction this project keeps.

Test paths below are abbreviated: `packpack/.../X.kt` means
`packpack/src/test/kotlin/org/thisisthepy/python/multiplatform/packpack/X.kt`, and `cli/.../X.kt`
means the command line's tests, `packpack/src/cliTest/kotlin/org/thisisthepy/python/multiplatform/packpack/<package>/X.kt`
(`utils/`, `dependency/frontend/` or `deploy/frontend/`; run with `./gradlew :packpack:cliTest`).

`docs/INTENT.md` is the boundary of this file: nothing here may go beyond it. Defects and open
questions that are not yet a spec item are tracked in `docs/issues/KNOWN_ISSUES.md`.

## Feature Overview

- A multi-platform build system for Python

#### Goals to achieve

- No OS dependency (Desktop, macOS, linux) via GraalVM Native Image
  - Status: partial. `:packpack` applies `org.graalvm.buildtools.native` to its `cli` source set
    (`./gradlew :packpack:nativeCompile`). publish-pypi.yml builds and smoke-tests the native image
    on four platforms for every pull request that touches it; no other job does.
- Must be easy to install and use (let's not make users configure paths for Python, JVM, etc.)
  - Status: partial. `uv` is downloaded into `~/.pypackpack/uv` when absent, and `meson`/`ninja` are
    installed through `uv tool install` when absent (see *CLI Version* and *Package build*).
    `uv tool install pypackpack` is wired but nothing is uploaded yet (see *Distribution through PyPI*).
- Must be fast (build speed matters)
  - Status: planned. Nothing measures build speed today.

#### Scope decisions

Recorded 2026-10-04 (#70); `docs/INTENT.md` §4 has the sources.

- Python: CPython 3.15 and later, free-threaded only (python-multiplatform #158: the free-threaded
  build is the default and the only one; the base is 3.15t). Today `python install` offers 3.14.7
  and 3.13.0 (GIL builds) and `SingleWheelBundler` writes `cp313` tags; both are to be brought in
  line (#70).
- Extension modules are free-threaded by default (planned):
  - Builds target PEP 803's `abi3t`, so one extension serves every free-threaded 3.15+ runtime.
    This covers the compile slot (#19, #60), Meson's `py.extension_module()` and the wheel tags.
  - The Cargo backend (#65) builds with PyO3's `gil_used = false` and `abi3t` by default.
  - A dependency whose extension does not declare `Py_mod_gil = Py_MOD_GIL_NOT_USED` makes CPython
    re-enable the GIL when it is imported. The build warns at that point, naming the module and
    saying that it re-enables the GIL, because the app would otherwise silently run with the GIL.
- WASM (`wasm32-pyodide2024`) is in the first release's scope. Nothing installs or compiles for it yet.
- `build` bundles: it takes the bundle as an argument (see *Package build*). There is no `bundle`
  command.
- Deploy destination follows the artefact: libraries go to PyPI, apps to FastTrack.

### <pypackpack> core

- (1). Support building Python packages written in C, C++, Rust (except when patches are required)
  - Status: partial. C/C++ through Meson (`packpack/.../compile/backend/external/MesonTest.kt`).
    Rust (`Cargo.kt`) is an empty placeholder.
- (2). Support crossenv for multi-platform code
  - Status: partial. Per-target dependency markers and per-target install directories
    (`build/crossenv/<target>`), see *Per-package target dependency management*; a real `uv`
    install of Android and iOS wheels is tested (`packpack/.../dependency/backend/UVBackendRealInstallTest.kt`).
    No cross interpreter environment is created.
- (3). Support Python code compilation/optimization/minification (nuitka, lpython, etc.)
  - Status: planned. `Nuitka.kt`, `Cython.kt`, `Lpython.kt`, `transcompile/` and `minification/`
    are empty placeholders. (The `bytecode` level of the `resource` bundle uses `compileall`, which
    is not this.)
- (4). Support code update fast track API
  - Status: planned. `deploy/code/FastTrackAPI.kt` is an empty placeholder; see *Package
    deployment*.

### PyPackPack Companion (tools designed to be used together)

These are other projects. They are listed for context and are not items of this contract, so they
carry no `Status:`.

#### <toolchain>

- (1). pypackpack wrapping
- (2). Generate a Kotlin library that includes Python code
- (3). Final binary generation feature
- (4). Extend pypackpack's code update fast track to also allow hot-reload during the development stage

`toolchain` consumes this repository's `:packpack` library as
`org.thisisthepy.python.multiplatform:packpack:0.1.0` (`packpack/build.gradle.kts`, `maven-publish`).

#### <toolchain(python)>

- A Kotlin Multiplatform project with the toolchain applied, packaged whole into a whl package

#### <pip-jit>

- A project that supports packages which fail to build in pypackpack because they require a patch, by inserting recipes so that they can be built

#### <pip-central> (closed-source project)

- A service that distributes packages using pip-jit

#### <brainWave/intelliPush>

- A management server that uses pypackpack's code update fast track API and the brain wave API

## Directory Structure

### pypackpack code directory structure

Files marked *placeholder* hold a declaration with no behaviour, or a class that only refuses.

```
- pypackpack
  - packpack                     # Gradle module :packpack -- the library (`main`, published to Maven) and the command line (`cli`)
    - src/main/python            # the PyPI launcher package `pypackpack` (__init__.py, __main__.py); see *Distribution through PyPI*
    - src/cli/kotlin/org/thisisthepy/python/multiplatform/packpack   # the `pypackpack` / `ppp` command line (Clikt); not published to Maven (#73)
      - utils
        - CommandLine.kt         # CLI endpoint: main(), root command, dynamic package command dispatch
        - CommandExtension.kt    # CLI helper, validation, progress display, passthrough-flag parsing
        - BuildInfo.kt           # the CLI's version, from build-info.properties that packpack/build.gradle.kts generates (cliVersion)
      - dependency/frontend
        - DependencyCommand.kt   # handles root-level 'add', 'remove', 'sync', 'tree'
        - DynamicPackageCommand.kt # handles '<package> add/remove/sync/tree'
        - PackageCommand.kt      # handles 'package add/remove/sync/tree'
        - ProjectCommand.kt      # handles 'version', 'init'
        - PythonCommand.kt       # handles 'python' related commands
        - TargetCommand.kt       # handles 'target' related commands
      - compile/frontend
        - BuildCommand.kt        # handles 'build' command
      - deploy/frontend
        - DeployCommand.kt       # handles 'deploy' (registered; every deploy type refuses today)
    - src/main/kotlin/org/thisisthepy/python/multiplatform/packpack
      - utils
        - Platforms.kt  # supported targets, aliases, families, markers, min SDK validation
        - Downloader.kt  # external tool downloader (URL downloader, pip downloader); also defines DownloadSpec
        - Archive.kt  # zip/tar.gz/tar.zst archive extraction (with contained symlinks and executable bits)
        - Checksum.kt  # SHA-256 of a file, and verification against a pinned digest
        - Workspace.kt  # project/workspace root discovery, workspace member listing
        - toml
          - TomlEditor.kt  # style-preserving TOML editor (tables/arrays/values)
          - TomlValue.kt   # TOML value type hierarchy
      - dependency
        - frontend
          - FrontendInterface.kt  # factory pattern
          - Cli.kt  # CLI interface
          - Gradle.kt  # Gradle interface
        - middleware
          - MiddlewareInterface.kt  # factory pattern
          - DefaultMiddleware.kt  # strategy pattern; also MarkerPolicy
          - environment
            - DevEnv.kt  # manages dev/build environment venv
            - CrossEnv.kt  # manages per-target dependencies and build/crossenv/<target>
        - backend
          - external
            - UV.kt  # uv downloader
          - BackendInterface.kt  # factory pattern
          - DefaultBackend.kt  # shared Python-version install/list/find/uninstall logic
          - PythonDistributions.kt  # (version, target) -> interpreter archive URL and pinned SHA-256
          - UVBackend.kt
          - MissingWheel.kt  # parses uv's "no wheel for this target" failure
      - compile
        - frontend
          - FrontendInterface.kt  # factory pattern
          - Cli.kt
          - Gradle.kt
        - middleware
          - external
            - Nuitka.kt  # placeholder (c++ converter)
            - Cython.kt  # placeholder (c converter)
            - Lpython.kt  # placeholder (llvm converter)
          - MiddlewareInterface.kt  # factory pattern
          - DefaultMiddleware.kt  # decorator pattern
          - transcompile
            - BaseTransInterface.kt  # placeholder
            - NuitkaTransInterface.kt  # placeholder
            - CythonTransInterface.kt  # placeholder
          - minification
            - BaseMinifyInterface.kt  # placeholder
        - backend
          - external
            - Clang.kt  # placeholder
            - MSVC.kt  # placeholder
            - NDK.kt  # placeholder (adapter of Clang.kt)
            - XCode.kt  # placeholder (adapter of Clang.kt)
            - Emscripten.kt  # placeholder
            - Cargo.kt  # placeholder
            - Meson.kt  # meson.build generation, meson setup/compile/install, meson/ninja auto-install
          - BackendInterface.kt  # factory pattern
          - DefaultBackend.kt  # strategy pattern
      - bundle
        - BundlerInterface.kt  # factory pattern; BundleType, BundleRequest, BundleResult
        - DefaultBundler.kt  # placeholder
        - binary
          - BinaryBundler.kt  # placeholder (refuses)
        - fat
          - FatWheelBundler.kt  # .whl including dependencies
        - single
          - SingleWheelBundler.kt  # .whl, just package except dependent libs
        - patch
          - WheelPatchBundler.kt  # .whl.patch
        - resource
          - ResourceBundler.kt  # directory payload for python-multiplatform/toolchain
      - deploy
        - DeployInterface.kt  # factory pattern; DeployType, DeployRequest, DeployResult
        - DefaultDeployer.kt  # placeholder (refuses)
        - resource
          - ResourceAPI.kt  # placeholder (refuses)
          - ResourceHubAPI.kt  # placeholder
        - code
          - CodeAPI.kt  # placeholder (refuses)
          - PyPIPublishAPI.kt  # placeholder (uv publish)
          - FastTrackAPI.kt  # placeholder
        - weight
          - WeightAPI.kt  # placeholder (refuses)
          - BrainWaveAPI.kt  # placeholder

  - usage-example  # a build file depending on :packpack; no sources

```

### User directory structure

```
- <project>  # multi-package project
  - .venv  # venv where dev dependencies for building the project are managed
  - <package1>  # package 1 (a single gradle module in the toolchain)
    - build  # stores build artifacts
      - crossenv
        - x86_64-pc-windows-msvc   # per-target dependency install directory (canonical target triple)
        - aarch64-linux-android
        - aarch64-apple-darwin
      - packpack
        - single
          - <build type>
            - <build level>
              - <target>  # Meson build directory of `pypackpack build`
        - <bundle type>  # single, fat, patch, resource (binary: planned)
          - <build type>  # debug, release
            - <build level>  # instant(.py), bytecode(.py+.pyc in debug, .pyc in release), native, mixed
    - dist
      - <target>
        - <build type>
          - <build level>  # `meson install --destdir` of `pypackpack build`; what the wheel bundlers read
    - src  # stores source code
      - main  # platform-common code
        - __init__.py
        - ...
      - android  # android-specific code (one directory per target family, created by `target add`)
        - __init__.py
        - ...
      - windows  # windows-specific code
        - __init__.py
        - ...
      - test  # test code
        - test_*.py
    - pyproject.toml  # package configuration (dependency management, etc.)
  - <package2>
    - ...
  - .gitignore
  - LICENSE
  - README.md
  - uv.lock
  - pyproject.toml
```

## Feature Specification

### Basic features

#### CLI Help

Status: implemented. `cli/.../DeployCommandTest.kt` (`deploy_registeredInPyPackPackCommandHelp`
runs the root `--help`; `deploy_helpOptionWorks` runs a subcommand's).

```bash
pypackpack --help
pypackpack -h
```

- Prints CLI usage

Limitation

- A bare `pypackpack help` (no dashes) is not a registered subcommand and fails with `no such subcommand help`; only the `--help`/`-h` eager options are wired up.

#### CLI Version

Status: partial. `cli/.../BuildInfoTest.kt` checks the printed version's source; nothing tests the uv
part.

```bash
pypackpack version
```

- Prints the pypackpack version and the detected uv version.
- The pypackpack version is `packpack/build.gradle.kts`'s `cliVersion`, which the build writes into the CLI's
  resources (`build-info.properties`, read by `BuildInfo`). It is the PyPI wheel's version too.
- If uv is not detected, downloads uv into `~/.pypackpack/uv` (`dependency/backend/external/UV.kt`) and uses it.
- The download is uv's release archive for the host. The `.tar.gz` archives (Linux, macOS) keep their
  files under one top-level directory, which is stripped; the Windows `.zip` holds `uv.exe` at its root.
  A binary missing after extraction is a failure, not a success (`packpack/.../dependency/backend/external/UVInstallTest.kt`).

Limitation

- `pypackpack --version`, `pypackpack -v` and `pypackpack v` fail (`no such option --version`,
  `no such option -v`, `no such subcommand v`; observed by running the `:packpack:installCliDist` launcher).
  `VersionCommand.aliases()` declares them, but Clikt applies `aliases()` to a command's own
  subcommands, and `VersionCommand` has none.

#### Distribution through PyPI

Status: partial. `.github/workflows/publish-pypi.yml` builds and smoke-tests the wheels on every pull
request that touches the publishing files. Nothing has been uploaded (PyPI still holds the old
repository's 0.1.0, a Windows-only wheel).

```bash
uv tool install pypackpack   # or: uvx pypackpack
pypackpack --help          # the native binary
ppp --help                 # the same binary, through the `pypackpack` Python package
```

- A wheel per platform, `py3-none-<platform>`: Linux x86_64 and aarch64 (manylinux), macOS arm64,
  Windows x86_64. Each carries the GraalVM native image as the `pypackpack` script and the Python
  package `pypackpack` (`packpack/src/main/python/`), whose `ppp` entry point and `python -m
  pypackpack` run that binary.
- The platform tag is read from the binary (`.github/scripts/pypi/build_wheel.py`): the highest
  `GLIBC_` symbol version on Linux, whose `NEEDED` libraries must be glibc's or `libz`; the
  `LC_BUILD_VERSION` minimum on macOS, which the build sets to 11.0 (`packpack/build.gradle.kts`).
- There is no sdist: building needs a JDK and GraalVM, so an sdist would install the launcher without
  the binary. On an unsupported platform the installer finds no matching wheel.
- Before uploading, the workflow requires the release tag to be `v<version>`, `pyproject.toml` and
  `packpack/build.gradle.kts`'s `cliVersion` to carry the same version, the version to be new on PyPI, and every wheel
  to pass `.github/scripts/pypi/smoke_wheel.py`: installed into a fresh venv, `pypackpack --help`
  works, `pypackpack`/`ppp`/`python -m pypackpack version` print that version, and
  `pypackpack init` creates a project.
- Upload is trusted publishing from the `pypi` environment, on a published GitHub Release only.

Limitation

- No wheel for macOS x86_64, Windows arm64 or musl Linux.
- Installing from source (`uv pip install .`) gives only the launcher; `ppp` then says the binary is
  missing and exits 1.

#### Project creation

Status: partial. `packpack/.../dependency/middleware/DefaultMiddlewareInitTest.kt` covers the
generated `README.md` and `LICENSE`.

```bash
pypackpack init [<path>] [--python <python version>] [--name <project name>] [--package]
```

- Project initialization is performed based on `uv init --bare`.
- `path`, `--python`, `--name`, `--package` are passed as `uv init` options, but `uv` only generates `pyproject.toml`, and ppp generates the other necessary files.
- If `targets` is given (library API only, see Limitation), updates `[tool.ppp.dependencies].platforms` in the root `pyproject.toml`.
- After `uv init --bare`, ppp additionally generates `.gitignore`, `README.md`, and `LICENSE`. `.python-version` is only generated when `--python` is passed.

Limitation

- There is not yet a check for whether an existing `pyproject.toml` is a ppp project, nor a determination of conflicts with other packaging tools.
- The `init` CLI command does not expose a way to pass `targets`, so `[tool.ppp.dependencies].platforms` cannot be set from `pypackpack init` even though the middleware supports it; targets must be added afterward via `target add`.

#### Changing the project's Python version

Status: partial. No test in this repository.

```bash
pypackpack python use <python version>
```

- Deletes `.venv` at the project root, recreates it with the new Python version, and performs `uv sync`.

Limitation

- There is no cleanup logic for subpackage `build` directories.

#### Listing, finding and uninstalling ppp Python distributions

Status: implemented. `packpack/.../dependency/backend/DefaultBackendTest.kt` (`listPython_*`,
`findPython_*`, `uninstallPython_*`).

```bash
pypackpack python list
pypackpack python find <python version>
pypackpack python uninstall <python version>
```

- These commands do not forward to `uv python`; they manage a separate, ppp-specific Python distribution instead.
- They consult a registry file at `~/.pypackpack/python/registry.properties` (`version=absolutePath`, written by `install`) first, and fall back to scanning `~/.pypackpack/python/<version>` directly (so a directory placed straight under the install root, e.g. by hand, is still found without ever touching the registry).
- A version containing a path separator is rejected.

#### Installing a ppp Python distribution

```bash
pypackpack python install <python version> [<target platform>]
```

##### Version and target selection

Status: implemented. `packpack/.../dependency/backend/PythonDistributionsTest.kt` (every pair's URL
and digest, the supported list, refusals, aliases) and `DefaultBackendTest.kt`
(`installPython_refusesAnUnsupportedVersionWithTheSupportedList`,
`installPython_returnsFailureForUnsupportedTargetPlatform`).

- The installable versions are the ones `toolchain` can ask for (`PY3_14_7`, `PY3_13_0` in `toolchain`'s
  `dsl/DSLCore.kt`). `3.14` and `3.13` are accepted as shorthands for `3.14.7` and `3.13.0`.
- Every (version, canonical target) pair maps to one archive URL and one pinned SHA-256 in
  `dependency/backend/PythonDistributions.kt`, which cites where each digest came from:

| Version | Targets | Source | Digest provenance |
|---|---|---|---|
| 3.14.7 | `aarch64-apple-darwin`, `x86_64-apple-darwin`, `x86_64-unknown-linux-gnu`, `x86_64-pc-windows-msvc` | python-build-standalone `20260807`, `install_only.tar.gz` | python-multiplatform's `python-checksums.properties`, equal to the release's `SHA256SUMS` |
| 3.14.7 | `aarch64-unknown-linux-gnu`, `aarch64-pc-windows-msvc` | same | the release's `SHA256SUMS` only |
| 3.14.7 | `aarch64-linux-android`, `x86_64-linux-android` | python.org `python-3.14.7-<arch>-linux-android.tar.gz` | python-multiplatform's lockfile (python.org publishes no checksum file) |
| 3.14.7 | `arm64-apple-ios`, `arm64-apple-ios-simulator`, `x86_64-apple-ios-simulator` | BeeWare Python-Apple-support `3.14-b11` (one XCframework for all three) | python-multiplatform's lockfile, equal to the GitHub release asset digest |
| 3.13.0 | `aarch64-apple-darwin`, `x86_64-apple-darwin`, `x86_64-unknown-linux-gnu`, `x86_64-pc-windows-msvc` | python-multiplatform `release` branch `binary/` (copies of python-build-standalone `20241008` `full.tar.zst`) | python-build-standalone `20241008` `SHA256SUMS` |

- A pair not in the table fails with `Python <version> is not available for <target>. Supported: <version/target, ...>`.
- A pair that is known but has no pin fails closed (`... has no pinned SHA-256, so it is refused rather
  than installed unverified`) without downloading anything. Today that is 3.13.0 for Android and iOS
  (`binary/*.tar.xz`, `binary/*.zip`): nothing publishes their digest and computing one needs the full
  download (a TODO in `PythonDistributions.kt`).

Limitation

- Free-threaded builds and Sigstore verification (both available in python-multiplatform's build) are
  not offered here.

##### Digest verification and placement

Status: implemented. `packpack/.../dependency/backend/DefaultBackendTest.kt`
(`installPython_matchingDigestExtractsIntoTheCrossTargetDirectory`,
`installPython_hostTargetGoesToTheProjectVenv`, `installPython_mismatchedDigestRefusesAndLeavesNothingBehind`,
`installPython_mismatchedDigestLeavesAnExistingInstallUntouched`,
`installPython_archiveWithAnUnexpectedLayoutIsRefused`, `installPython_refusesAPairWithNoPinnedDigestWithoutDownloading`)
and `utils/ArchiveTest.kt` (`extractArchive_recreatesRelativeSymlinksAndExecutableBits`,
`extractArchive_rejectsSymlinksThatEscapeTheDestination`). The tests use small fake archives; no test downloads.

- The archive is downloaded into a staging directory beside the install directory
  (`<project>/.<install dir>.pypackpack-staging`), and its SHA-256 is compared with the pin **before**
  anything is extracted.
- A mismatch fails with `SHA-256 mismatch for <archive>: expected <pin>, actual <digest>` and registers nothing.
- The archive is extracted inside the staging directory and only then moved to `<project>/.venv`
  (host target) or `<project>/<target-dir-name>` (cross target: `windows_amd64`, `windows_arm64`,
  `linux_x86_64`, `linux_arm64`, `macos_arm64`, `macos_x86_64`, `android_arm64`, `android_x86_64`,
  `ios_arm64`, `ios_simulator_arm64`, `ios_simulator_x86_64`). A missing install directory is created by
  a rename; an existing one (a `.venv` uv already made) has the tree copied over it.
- Whatever happens, the staging directory is deleted, so a refused or failed install leaves neither the
  archive nor a partial tree, and an existing install directory untouched.
- Only the interpreter tree is installed: `python/` of an `install_only` archive, `python/install/` of a
  `full` archive, the whole `./` tree of a python.org Android archive, the whole XCframework archive for
  iOS. An archive that verifies but yields nothing under that prefix is refused.
- tar symbolic links are recreated when they are relative and stay inside the install directory (others
  are rejected), and executable bits are kept, so `bin/python` exists and runs.
- On success it writes a `version=absolutePath` entry to `~/.pypackpack/python/registry.properties`, keyed
  by the version string as given, so `find`/`list`/`uninstall` can locate the project-relative install.

##### Installing into an explicit directory

Status: implemented. `packpack/.../dependency/backend/DefaultBackendTest.kt`
(`installPython_explicitInstallDirReceivesTheTreeAndNothingElse`,
`installPython_explicitInstallDirReplacesAPreviousInstallForTheHostToo`,
`installPython_explicitInstallDirStillRefusesAnUnpinnedPairBeforeDownloading`,
`installPython_explicitInstallDirIsLeftAsItWasOnADigestMismatch`). The tests use small fake archives; no test downloads.

```kotlin
suspend fun installPython(pythonVersion: String, targetPlatform: String?, installDir: File? = null): Result<String>
```

- This is the backend-layer form `toolchain` calls from a Gradle daemon (AGENTS.md rule 13), e.g. into
  `build/pythonRuntime/<triple>/<version>/`. The CLI does not expose it.
- The (version, target) pair is resolved and refused exactly as above, before anything is downloaded or
  created.
- The archive is downloaded and extracted in a staging directory beside `installDir`
  (`<parent>/.<name>.pypackpack-staging`), and its SHA-256 is verified before extraction.
- The extracted tree then **replaces** `installDir`: a previous `installDir` is renamed aside into the
  staging directory, the new tree is renamed into place (if that rename fails the previous tree is
  renamed back), and the staging directory is deleted. Nothing is merged into an existing tree.
- `projectRoot()` (and so `user.dir`), `<project>/.venv` and the registry are not touched, so
  `find`/`list`/`uninstall` do not see such an install; the caller owns the directory.
- A refused pair, a digest mismatch or an unexpected archive layout leaves `installDir` as it was.
- When `installDir` is `null`, the project-relative placement above applies unchanged.

Limitation

- Without `installDir`, the project root is located through `user.dir` (`findProjectRoot()`), not an
  explicit working directory.
- `Downloader` holds the whole archive in memory before writing it.
- `utils/Archive.kt` reads ustar names (100-byte name + 155-byte prefix) and skips PAX (`x`/`g`) and GNU
  long-name (`L`) records, and has no `.tar.xz` support.

### Package management features

#### Adding/removing packages

Status: partial. `packpack/.../dependency/middleware/environment/CrossEnvTest.kt`
(`addPackageCreatesSourceFoldersForInheritedWorkspaceTargets`) covers `add`; nothing covers `remove`.

```bash
pypackpack package add <package path> [--path <workspace root>]
pypackpack package remove <package path> [--path <workspace root>]
```

- `<package path>` allows a relative path based on the workspace root. Example: `packages/core`
- The package path cannot go outside the workspace.
- `package add` creates a directory at the specified path, then in that directory runs `uv init --bare --package --name <leaf dir name>` to generate only `pyproject.toml`, and ppp generates the scaffolding: the package's `README.md`, `src/main/__init__.py`, `src/test/test_import.py`, `build/crossenv`, `build/packpack`. It also registers the package as a workspace member.
- A new package inherits the root's `[tool.ppp.dependencies].platforms`, and gets a `src/<family>/__init__.py` for each inherited target family.
- `package remove` recursively deletes the package directory and removes the corresponding relative path from `[tool.uv.workspace].members` in the root `pyproject.toml`.
- `<package path>` is resolved as a literal path relative to the workspace root, not by matching workspace member names: a bare leaf name only works when the package actually sits directly under the workspace root (e.g. `package remove core` fails for a package registered at `packages/core`; the full relative path must be used there instead).
- The `package` command additionally exposes `sync` and `tree` subcommands for per-package dependency operations: see Per-package target dependency management.

Limitation

- There is not yet a reserved-word check for package names, nor additional consistency validation on remove.

### Package build target management features

#### Viewing build target platforms

Status: partial. No test covers `target list`'s output.

```bash
pypackpack target list
```

- `target list` groups `Platforms.SUPPORTED_TARGETS` by alias and platform family and prints them.

#### Adding/removing build target platforms

Status: implemented. `packpack/.../dependency/middleware/environment/CrossEnvTest.kt`
(`addTargetsSyncsWorkspaceMemberPackages`, `removeTargetsSyncsWorkspaceMemberPackages`,
`addTargetsUsesPackageNameInsteadOfPackagePath`, `removeTargetsUsesPackageNameInsteadOfPackagePath`).

```bash
pypackpack target add <target name>...
pypackpack target remove <target name>...
pypackpack <package> target add <target name>...
pypackpack <package> target remove <target name>...
```

- `target add/remove` modifies the `[tool.ppp.dependencies].platforms` array in the `pyproject.toml` of the current directory, and applies the same change to every workspace member listed there.
- `target add` creates `src/<family>/__init__.py`, one per target family, in each affected package (not in the workspace root).
- `pypackpack <package> target add/remove` finds the workspace member package by package name or path and adds/removes the target for that package.
- Targets are normalized via `Platforms.normalizeTargetsOrThrow`.

### Dependency management features

#### Policy notes

> - `pypackpack add/remove` is DevEnv-only and operates without a `packageName`
> - `pypackpack <package> add/remove/sync/tree` is CrossEnv-only and requires a `packageName`
> - CrossEnv target dependencies are normalized based on `Platforms.kt`, then managed via `uv add` or `pyproject.toml` editing using a per-target marker
> - Input that directly includes a marker in the dependency string (e.g. `numpy; ...`) is prohibited; only `--target` is allowed
> - `remove --target` does not remove the whole package, only the specified target scope
> - The CLI `--target` input uses a space-separated format (e.g. `--target windows linux`)
> - `tree` is an inspection-only action that shows the resolution result based on the host target or a specified target
> - No per-target *virtual environment* (interpreter) is created; `sync` installs each target's dependencies into a plain directory, `build/crossenv/<target>`

#### Dev environment dependency management

Status: partial. `cli/.../DependencyCommandTest.kt` and `cli/.../CommandExtensionTest.kt` cover
argument parsing and forwarding to the middleware (against a recording middleware). No test runs
the `uv` calls.

```bash
pypackpack add <pypi name>...
```

- Finds the project root, then runs `uv add` in the root working directory.
- `add`/`remove`/`sync`/`tree` (and their per-package equivalents, `pypackpack <package> add/remove/sync/tree` and `pypackpack package sync/tree <name>`) accept unrecognized `--flag [value]` tokens and forward them as `extraArgs` to the backend (`UVBackend.appendOptions`, which turns an arbitrary map into `--key [value]`). E.g. `pypackpack add requests --dev` and `pypackpack mypackage add numpy --target windows linux --extra-index-url https://pypi.org/simple` both work.
- The split between "dependency name" and "passthrough flag" is heuristic: every flag an earlier draft of this spec named (`--dev`, `--editable`, `--no-sync`, `--upgrade`, `--reinstall`, `--refresh`, `--frozen`, `--locked`, `--preview`, `--raw-sources`, `--quiet`, `--verbose`) is boolean in real `uv`, so any `--flag` defaults to a bare flag (no value) unless it is written `--flag=value` or is in a small value-taking allowlist (`--extra-index-url`, `--index-url`, `--index-strategy`, `--index`, `--default-index`, `--find-links`, `--python`, `--python-version`, `--only-binary`, `--resolution`) hardcoded in `parsePassthroughArgs` (`cli/CommandExtension.kt`). Test: `cli/.../CommandExtensionTest.kt`.

```bash
pypackpack remove <pypi name>...
```

- Runs `uv remove` in the root working directory.
- Does not allow dependency string input that includes a marker.

```bash
pypackpack sync
```

- Performs `uv sync` targeting the project root's `.venv`.

Limitation

- The backend call operates based on the working directory; the `venvPath` argument is not passed to the `uv sync` command (`UVBackend.syncDependencies` ignores it).

```bash
pypackpack tree [--target <target1> <target2> ...]
```

- When there is no package name, calls `uv tree --python-platform <target>` for each of the host target or the specified targets.
- If no target is specified, uses the single target corresponding to the current host as the default.

#### Per-package target dependency management

Status: partial. See the per-command lines below. `cli/.../DynamicPackageCommandTest.kt`,
`cli/.../PackageCommandTest.kt` and `cli/.../TargetOptionParsingTest.kt` cover the CLI parsing and
forwarding for all four commands.

```bash
pypackpack <package name> add <pypi name> [--target <target1> <target2> ...]
```

Status: partial. No test runs the per-target `uv add --marker` loop.

```bash
# example
pypackpack mypackage add numpy --target windows linux
```

- The dynamic package command `pypackpack <package> add/remove/sync/tree` is supported.
- `pypackpack package sync <name> [--target ...]` and `pypackpack package tree <name> [--target ...]` invoke the same logic as an explicit alternative to the dynamic `sync`/`tree` forms.
- The target package is resolved among workspace members by name or relative path.
- `add` computes a marker for each target (`platform_system == '<system>' and platform_machine == '<machine>'`) and repeatedly calls `uv add --package <name> --marker <marker>`.
- `<machine>` is the value uv evaluates for that target under `--python-platform`: `arm64` on macOS and iOS, `ARM64` and `x86` on Windows, the triple's own `aarch64`/`x86_64`/`riscv64` on Linux and Android, `wasm32` for Pyodide. Status: implemented. `packpack/.../dependency/middleware/MarkerPolicyUvTest.kt` resolves each canonical target's marker with `uv pip compile` against a local wheel and requires it to select exactly its own target.
- Markers written before #49 (`arm64` for Linux/Android/Windows aarch64, `i686` for 32-bit Windows) never matched, so those dependencies were not installed. `remove --target` still finds them under the corrected target (`MarkerPolicyTest`); to install them, remove and add them again.
- If `--target` is absent, uses the `[tool.ppp.dependencies].platforms` value from the package's `pyproject.toml` as the default target.
- If there is no default target and `--target` is also empty, raises an error.

```bash
pypackpack <package name> remove <pypi name> [--target <target1> <target2> ...]
```

Status: implemented. `packpack/.../dependency/middleware/environment/CrossEnvTest.kt`
(`removeDependenciesMatchesUvNormalizedMarkerText`, `removeDependenciesDoesNotMatchAWrongTargetsNormalizedMarker`).

- `remove` directly edits `project.dependencies` in the package's `pyproject.toml`, removing only entries where both the requested package name and the target marker match.
- Matching compares the (system, machine) pair, not the marker text: `MarkerPolicy.targetKeyFor` (`dependency/middleware/DefaultMiddleware.kt`) accepts both the `platform_system`/`platform_machine` spelling ppp writes and the `sys_platform`/`platform_machine` spelling `uv add` actually persists, in either clause order.
- After removal, performs `uv lock` at the workspace root.

Limitation

- Handled via TOML editing rather than calling `uv remove --marker`.

```bash
pypackpack <package name> sync [--target <target1> <target2> ...]
```

Status: implemented. `packpack/.../dependency/middleware/environment/CrossEnvTest.kt`
(`syncDependenciesInstallsPerTargetCrossenvDirectories`) and
`packpack/.../dependency/backend/UVBackendTest.kt` (`installDependenciesToTarget_*`) check the
calls against a recording backend. `packpack/.../dependency/backend/UVBackendRealInstallTest.kt`
(`installDependenciesToTarget_installsAndroidWheelsIntoCrossenvDirectory`,
`installDependenciesToTarget_installsIosWheelsIntoCrossenvDirectory`; tagged `network`, needs
network access and `uv`) runs the real install for `aarch64-linux-android` and `arm64-apple-ios`
with a pure-Python package (`six`) and a native one (`markupsafe`), and checks that the extension
module and its wheel tag are the target's (`android_24_arm64_v8a`, `ios_13_0_arm64_iphoneos`),
not the host's.

- `sync` first calls `uv sync --package <name>`. Then, for each target, it calls `uv tree --package <name> --python-platform <target>` to verify the target resolves, and installs that target's dependencies with `uv pip install -r pyproject.toml --target <package>/build/crossenv/<canonical target> --python-platform <target>`.
- `BackendInterface.installDependenciesToTarget(..., requirements: List<String>? = null)`: when `requirements` is given, those PEP 508 specifiers (markers and extras included) are passed to uv as positional arguments (`uv pip install <spec>... --target <dir> --python-platform <target>`, no shell) instead of `-r pyproject.toml`, so `workingDir` needs no `pyproject.toml`. `null` keeps `-r pyproject.toml`; an empty list is a successful no-op; a blank or `-`-leading entry fails. The missing-wheel mapping (`NoWheelForTargetException`) applies either way. Test: `UVBackendTest` (`installDependenciesToTarget_*Requirements*`) and, tagged `network`, `UVBackendRealInstallTest.installDependenciesToTarget_installsRequirementListWithoutPyproject`.
- uv selects wheels by `--python-platform` and by the Python version, which defaults to the
  interpreter uv finds on the host, not the target runtime's. Pass `--python-version` (the runtime's,
  e.g. `3.13`) through the extra arguments when the host's differs. A dependency with no wheel for the
  target is built from its sdist with the host compiler and then rejected as incompatible; pass
  `--only-binary :all:` to fail at resolution instead ("has no usable wheels").
- `sync` sends each extra argument only to the uv commands that accept it: `--python-version` to
  `uv tree` and `uv pip install`, `--only-binary` to `uv pip install`, neither to `uv sync` (which
  manages the host `.venv`). Test: `CrossEnvTest.syncDependenciesSendsTargetInstallOptionsOnlyToTheUvCommandsThatHaveThem`.
- If a package has no wheel for a target, the failure names the package spec and the target and says whether only an sdist exists (`NoWheelForTargetException`, with uv's raw text appended). Recognized uv texts (`parseMissingWheel`): `Failed to download and build ... is not compatible with the target Python` (only an sdist exists), `has no wheels with a matching platform tag` (wheels for other platforms only, no sdist) and `has no usable wheels` (building disabled, sdist unknown). Any other uv error passes through unchanged. Status: implemented. `packpack/.../dependency/backend/MissingWheelTest.kt` (fixtures are real uv 0.12.3 output).
- If `--target` is absent, uses a single host target as the default.

```bash
pypackpack <package name> tree [--target <target1> <target2> ...]
```

Status: partial. Only the CLI forwarding is tested.

- `tree` calls `uv tree --package <name> --python-platform <target>` for each target and prints the results in sequence.
- If `--target` is absent, uses a single host target as the default.

#### Cleaning up subpackage build directories on `python use`

Status: planned.

### Build, bundling, deployment

ppp distinguishes four stages: `build`, `compile`, `bundle`, `deploy`.

- `compile`: Converts source into an executable intermediate artifact.
- `bundle`: Packages the compile artifact together with resource/dependency/runtime metadata into a single deployment unit.
- `build`: The upper-level orchestration stage that coordinates dependency verification, compile, and bundle.
- `deploy`: Uploads the bundle artifact to external targets such as PyPI, FastTrack, ResourceHub, etc.

Of these four stages, the `pypackpack build` CLI command only drives `compile` (via Meson); it does not perform dependency verification or invoke `bundle`. `bundle` is a library API (`BundlerInterface.create(BundleType)`) with no CLI command. `pypackpack deploy` exists, but every deploy type refuses.

#### Package build

Status: partial. `packpack/.../compile/backend/external/MesonTest.kt` (meson.build generation,
meson/ninja auto-install) and `packpack/.../compile/backend/DefaultBackendTest.kt` (build and install
directories per type, level and target).

```bash
pypackpack build <package name> [--type <build type: default debug>] [--level <build level: default instant>] [--target <target name>] [--overwrite]
```

- Auto-generates a `meson.build` for the package by scanning `src/main` (falling back to `src`, then the package root) for Python packages: `.c`/`.cc`/`.cpp`/`.cxx` files become Meson `py.extension_module()` targets and `.py`/`.pyi`/`py.typed` files become `py.install_sources()`.
- Runs `meson setup` / `meson compile` / `meson install` for the package by shelling out to the `meson` CLI. `setup` probes `meson --version`/`ninja --version` first (`Meson.isMesonInstalled()`, which also looks in `uv`'s tool directory) and calls `Meson.installMeson()` (`uv tool install meson`/`ninja`) when either is missing, so `meson`/`ninja` do not need to be pre-installed on `PATH`.
- `--type` is passed to Meson as `--buildtype=<type>`. `--type`, `--level` and `--target` select the directories: the Meson build directory is `<package>/build/packpack/single/<type>/<level>/<target>`, and `meson install` writes to `<package>/dist/<target>/<type>/<level>` (`<target>` is `default` when `--target` is omitted).
- `--overwrite` regenerates `meson.build` (erroring otherwise if one already exists) and clears the build directory first.
- There is no `source`/`resource` bundle-type subcommand yet; `pypackpack build <package name> resource` does not exist.
- Planned shape, from the maintainer's spec sheet (`spec.md`, 686b1ad): `build` orchestrates
  dependency check, compile and bundle, and takes the bundle as an argument. There is no separate
  `bundle` command.

```bash
pypackpack build <package name> source [<bundle type: default binary>] [--type <build type>] [--level <build level>] [--target <target>]
pypackpack build <package name> resource [--target <target>]
```

Limitation

- `--level` and `--target` change only directory names. No level compiles differently, and `--target` does not cross-compile: Meson builds for the host.
- `--target` is used as typed. The wheel bundlers look for `dist/<canonical target triple>/...`, so a build meant for a bundle has to be run with the canonical triple (their error message says so); `default` and aliases such as `windows` are never found.
- Only the Meson backend is implemented; the Clang/MSVC/NDK/XCode/Emscripten/Cargo backend adapters and the Nuitka/Cython/Lpython compilers and minification middleware are all empty placeholder files, so only C/C++ extension compilation works today (no Rust, no pure-Python compilation/optimization/minification).
- `build` does not invoke the `bundle` stage; it produces compiled/installed files under `dist/`, not a `.whl`.
- The workspace is located through `user.dir` (`compile/backend/DefaultBackend.kt`), not an explicit working directory.

Not yet implemented (target). Status: planned

- Describing, in the package's `pyproject.toml`, the scope of dependency packages that need to be built (today everything under the package is built at the same level).
- Selecting whether to bundle everything into a single file or build separately, as metadata.
- Server-side patch version tracking (base version ledger, server management page).

#### Build Level

- `instant`: Bundles the Python source almost as-is.
  - Status: implemented. For bundle types `resource`, `single`, `fat` and `patch`
    (`packpack/.../bundle/resource/ResourceBundlerTest.kt`,
    `packpack/.../bundle/single/SingleWheelBundlerTest.kt`, `packpack/.../bundle/fat/FatWheelBundlerTest.kt`,
    `packpack/.../bundle/patch/WheelPatchBundlerTest.kt`).
- `bytecode`: Converts the Python source to `.pyc` and bundles it.
  - Status: partial. Implemented for bundle type `resource` only
    (`packpack/.../bundle/resource/ResourceBundlerTest.kt`: `bundle_bytecodeLevelKeepsBothPyAndPycForDebugBuildType`,
    `bundle_bytecodeLevelKeepsOnlyPycForReleaseBuildType`, `bundle_bytecodeLevelProducesARealLoadablePycFile`,
    `bundle_bytecodeLevelFailsClearlyWithoutAVenvInterpreter`). Every `.py` in the payload is compiled
    with `compileall -b` (sibling `foo.pyc`, not `__pycache__/`) by the interpreter in the nearest
    `<project>/.venv`; `debug` keeps the `.py` beside the `.pyc`, `release` keeps only the `.pyc`.
    `single`, `fat` and `patch` reject it (`bundle_rejectsBuildLevelsThatAreNotImplementedYet` in each).
- `native`: native code where it exists. The developer's modules become CPython extensions built
  from the C TypedPython generated for them (the compile slot, #19, draft #60). A developer module
  without C is allowed as an exception: it ships as bytecode, and the build warns, naming the module
  and the reason. C that fails to compile fails the build. Libraries ship their prebuilt native
  wheels.
  - Status: planned. Every bundler rejects it (`bundle_rejectsBuildLevelsThatAreNotImplementedYet`).
- `mixed`: bytecode for the developer's code, native code for libraries. The developer's modules ship
  as bytecode and TypedPython's C is not applied; libraries ship their prebuilt native wheels.
  - Status: planned. Every bundler rejects it.

The two levels' meaning is the user's (2026-10-03), reading `toolchain`'s user-authored
`(플러그인예시)build.gradle.kts`, which is specification (`AGENTS.md` rule 6):
`compileLevel = "native"  // (native code only) or "mixed" (byte code (개발자 코드) + native code (라이브러리))`.

The level is chosen per bundle request (`BundleRequest.buildLevel`) and names the output directory
`<package>/build/packpack/<bundle type>/<build type>/<build level>`. `pypackpack build --level` does
not select a level (see *Package build*).

#### Bundle Type

Bundling is a library API: `BundlerInterface.create(BundleType.<TYPE>).bundle(BundleRequest(...))`
(`bundle/BundlerInterface.kt`). There is no `pypackpack bundle` command.

- `binary`: Produces a target-specific binary layout suitable for execution or embedding in an app.
  - Status: planned. `BinaryBundler` returns a failed `Result` with `NotImplementedError`
    (`packpack/.../bundle/BundlerInterfaceTest.kt`, `unimplementedBundler_binaryReturnsFailureWithNotImplementedError`).
- `fat`: Produces a wheel-like archive that includes the current package together with its dependencies.
  - Status: implemented. `packpack/.../bundle/fat/FatWheelBundlerTest.kt`.
  - Reads the package's own compiled output from `<package>/dist/<canonical target>/<build type>/<build level>` (its `site-packages`).
  - Vendors workspace-local dependencies by name (matched against `tool.uv.workspace.members` entries) from their own `dist/<canonical target>/<build type>/<build level>`.
  - Vendors third-party dependencies, declared or transitive, from `<package>/build/crossenv/<canonical target>`, where `pypackpack <package> sync --target <target>` installs them via `uv pip install --target --python-platform`. That `sync` must have been run first.
  - Only the `instant` build level is currently supported.
- `single`: Produces a wheel-like archive that includes only the current package.
  - Status: implemented. `packpack/.../bundle/single/SingleWheelBundlerTest.kt`.
  - Reads `<package>/dist/<canonical target>/<build type>/<build level>` (its `site-packages`) and keeps native extension modules.
  - The Android platform tag uses the declared `minSdk`, falling back to the PEP 738 floor, and rejects a `minSdk` below that floor.
  - Only the `instant` build level is currently supported.
- `patch`: Produces a patch archive containing only the changes relative to the existing primary bundle.
  - Status: implemented. `packpack/.../bundle/patch/WheelPatchBundlerTest.kt`.
  - Reads the baseline wheel from `<package>/build/packpack/single/<buildType>/<buildLevel>`.
  - Compares current package files (from `<package>/dist/<canonical target>/<build type>/<build level>`) against baseline entries by path and SHA-256.
  - Records added and modified files in full; removed files in a text manifest (`PATCH-MANIFEST`).
  - Only the `instant` build level is currently supported.
  - Patch *version tracking* (base version, patch number) is planned.
- `resource`: Produces a resource layout consumable by python-multiplatform/toolchain.
  - Status: implemented. `packpack/.../bundle/resource/ResourceBundlerTest.kt`.
  - A directory, not an archive: `python/` (the payload, meant to be placed on `sys.path`) beside `resource-manifest.json` (identity, target, per-file SHA-256; sorted, no timestamp).
  - Reads the package *source*, not the compiled `dist/` output: `src/main` first, then `src/<family>` on top, then each `BundleRequest.metaDirs` entry, then each `libDirs` entry; a later stage wins for the same path.
  - Drops `__pycache__/`, `*.pyc`/`*.pyo`/`*.pyd`, dot-entries, `build/`, `dist/` and `node_modules/`. Because it reads source, extension modules that `build` compiles never reach it, unlike the wheel bundlers.
  - Supports the `instant` and `bytecode` build levels (see *Build Level*).
  - Validates a declared `minSdk` and records it in the manifest. It does not select a CPython build by API level, because no target-aware download exists here.
  - Records `BundleRequest.versionName` / `versionCode` (the app's payload version, from toolchain's `defaultConfig`) as `"versionName"` / `"versionCode"` beside the package's own `"version"`, only when set.
  - It is the one bundle type another repository consumes: `toolchain` calls it, and the payload it writes reaches the desktop jar's root and the APK's `assets/python/`.

#### Package deployment

Status: planned. `pypackpack deploy` is registered, and every deploy type refuses with a
"not implemented" message and exit code 1 (`cli/.../DeployCommandTest.kt`,
`packpack/.../deploy/DeployInterfaceTest.kt`). Nothing is uploaded.

```bash
pypackpack deploy <package name> [<deploy type: code (alias source) | resource | weight; default code>] [<bundle type: default binary>] [--type <build type: default debug>] [--level <build level: default instant>] [--target <target name>]
```

- `DeployInterface.create(DeployType)` returns `CodeAPI`, `ResourceAPI` or `WeightAPI`, each an `UnimplementedDeployer` that returns a failed `Result`. `PyPIPublishAPI`, `FastTrackAPI`, `ResourceHubAPI` and `BrainWaveAPI` are empty placeholders.
- The destination follows the artefact: a library goes to PyPI (`PyPIPublishAPI`), an app to FastTrack (`FastTrackAPI`).
- Implement patch feature
  - Let's go with a git-like concept for patch uploads (the concern is speed, parallel processing)
  - Need change upload, a server management page, client code
