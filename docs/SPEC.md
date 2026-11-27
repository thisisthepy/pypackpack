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
  - Status: partial (#160). `:packpack` applies `org.graalvm.buildtools.native` to its `cli` source set
    (`./gradlew :packpack:nativeCompile`). publish-pypi.yml builds and smoke-tests the native image for a
    release; the development workflow (test.yml `native`) builds it on the same four platforms and runs it
    through every command group (`.github/scripts/native/native_cli_suite.py`: version, help, init with
    targets, target, package, root add/tree/sync/remove, python list, build resource). `implemented` once
    that job has passed.
- Must be easy to install and use (let's not make users configure paths for Python, JVM, etc.)
  - Status: partial. `uv` is downloaded into `~/.pypackpack/uv` when absent, and `meson`/`ninja` are
    installed through `uv tool install` when absent (see *CLI Version* and *Package build*).
    `uv tool install pypackpack` installs the native CLI from PyPI (see *Distribution through PyPI*).
- Must be fast (build speed matters)
  - Status: partial (#160). `.github/scripts/native/build_speed.py <binary>` times `version`, `init`, `add`,
    `sync`, `build <package> resource` and a Meson `build` on a scratch project (min, median, max over
    `--runs`), refusing to start at a 1-minute load of 1.0 or more (AGENTS.md rule 8). The first result,
    with its date and machine, is recorded here once the lead has run it in a quiet window; that is what
    makes this `implemented`. Not yet run.

#### 1.0 scope

Agreed 2026-11-21 by the team lead for the owner's goal "ship a stable 1.0 of every project below 1.0"
(team brief 2026-11-21; proposal in the release-engineering report, approved as proposed):

- **In 1.0:** every item below marked `implemented`, plus the Cargo backend's Windows-on-its-own-machine
  build (#150). A `partial` item whose only gap was a missing test got the test (2026-11-21: *Changing the
  project's Python version*, *Adding/removing packages*, *Viewing build target platforms*, *Per-package
  target dependency management*).
- **The lead's "after 1.0" list is withdrawn** (owner, 2026-11-21, relayed by the team lead (toolchain report, "Owner decision (2026-11-21 21:55)"): "일을 제대로 하면 될거를 쓸데없이 자꾸
  미루려고만 하네? ... 전부 1.0에 넣어"; owner rule 2026-11-21 22:05: a scope cut is never proposed). Every
  `planned` and `partial` item in this file is in 1.0 except the two the owner placed after it:
  *Code minification* and FastTrack deploy (core item (4)). In 1.0, each with its own issue: build-speed
  measurement; the `binary` bundle; ResourceHub and
  BrainWave deploy; cleaning subpackage build directories on `python use`; the *Package build* targets
  (dependency build scope and bundling mode in `pyproject.toml`, server-side patch version tracking); and
  closing every `partial`: the GraalVM and tool-acquisition goals, *CLI Version*, *Distribution through Maven
  Central*, *Project creation*, *Dev environment dependency management*, *Package build* (Meson for every
  target, not the host only), crossenv (core (2)), and the `bytecode` / `native` / `mixed` levels on every
  target (iOS, Windows and wasm through the Xcode, MSVC/clang-cl and Emscripten adapters).
- **Not in this repository's power alone, reported as blockers and worked on:** ResourceHub, BrainWave and
  the patch-version ledger need servers that do not exist; Maven Central needs the owner's upload; WASM
  needs python-multiplatform #175's build (below).
- **WASM is in 1.0** (owner, 2026-11-21, relayed by the team lead: python-multiplatform #175, the
  free-threaded wasm runtime built with a post-build `.wasm` memory-import patch, is back in 1.0, and with it
  the `wasm32-emscripten` target, #82). *Scope decisions* below already put it in the first release (#70).
  1.0 is not tagged until #82 lands on top of #175's build; until then nothing installs or compiles for it.

#### Scope decisions

Recorded 2026-10-04 (#70); `docs/INTENT.md` §4 has the sources.

- Python: CPython 3.15 and later, free-threaded only (python-multiplatform #158: the free-threaded
  build is the default and the only one; the base is 3.15t). `python install` offers `3.15.0t` on every
  desktop and mobile target (see *Version and target selection*), and the wheel bundlers derive their tags from the
  target interpreter (see *Wheel tags*).
- Extension modules are free-threaded by default:
  - Builds target PEP 803's `abi3t`, so one extension serves every free-threaded 3.15+ runtime.
    Meson's `py.extension_module()`: implemented (see *Package build*). The compile slot (#19, #20, #60):
    implemented for macOS (`Clang.kt`), Linux host arch (`Clang.kt`) and Android `aarch64-linux-android` (`NDK.kt`)
    (see *Build Level*).
  - The Cargo backend builds with PyO3's `abi3t` by default (implemented for the host, #92); `gil_used = false`
    is the crate's own `#[pymodule]` option, and the GIL warning catches a module without it.
  - A dependency whose extension does not declare `Py_mod_gil = Py_MOD_GIL_NOT_USED` makes CPython
    re-enable the GIL when it is imported. The build warns, naming the module and saying that it
    re-enables the GIL, because the app would otherwise silently run with the GIL. Implemented for the
    `resource` bundle (see *GIL warning*).
- WASM (`wasm32-emscripten`, wheels tagged `cp315-cp315t-pyemscripten_2026_0_wasm32`, PEP 783's platform tag that python-multiplatform's 3.15t runtime reports from its sysconfig, built with Emscripten 5.0.3 (#82; team lead 2026-11-27)) is in the first release's scope. `python install 3.15t wasm32-emscripten` installs the interim runtime (see *Installing a ppp Python distribution*). C extensions compile for it with Emscripten 5.0.3 (the native compile slot's `EMSCRIPTEN` toolchain, below); Rust crates do not yet (#82);
  the target's ABI follows our own 3.15t pthreads build from python-multiplatform #175.
- `build` bundles: it takes the bundle as an argument (see *Package build*; #81). There is no `bundle`
  command.
- Deploy destination follows the artefact: libraries go to PyPI, apps to FastTrack.

### <pypackpack> core

- (1). Support building Python packages written in C, C++, Rust (except when patches are required)
  - Status: partial. C/C++ through Meson (`packpack/.../compile/backend/external/MesonTest.kt`).
    Rust through the Cargo backend for the host target (`packpack/.../compile/backend/external/CargoTest.kt`);
    Android and iOS too (see *Rust extension modules (Cargo backend)*).
- (2). Support crossenv for multi-platform code
  - Status: partial. Per-target dependency markers and per-target install directories
    (`build/crossenv/<target>`), see *Per-package target dependency management*; a real `uv`
    install of Android and iOS wheels is tested (`packpack/.../dependency/backend/UVBackendRealInstallTest.kt`).
    An sdist-only dependency is built for the target in pypackpack's own cross environment, see *Cross build
    environment (sdist-only dependencies)* (partial: Android has the real build).
- (3). Support Python code compilation/optimization/minification (nuitka, lpython, etc.)
  - Compilation is TypedPython's (owner, 2026-11-21, relayed by the team lead (toolchain report, "Owner decision (2026-11-21 21:55)"): "Nuitka and Cython are replaced by
    TypedPython and stay only as benchmark comparisons (not build backends)"). TypedPython generates the C
    (python-multiplatform's `typedpython emit-c`) and this repository compiles it through the native slot
    (`BundleRequest.nativeModules`, the `native` / `mixed` levels of every bundle; see *The native compile
    slot*). Lpython likewise (owner, 2026-11-21 23:00, relayed by the team lead: "Lpython도 비교군으로만":
    Lpython is a benchmark comparison only, not a build backend). The Cython and Nuitka comparisons live in
    python-multiplatform's `benchmarks/typedpython/`. The empty `Nuitka.kt`, `Cython.kt`, `Lpython.kt`,
    `NuitkaTransInterface.kt` and `CythonTransInterface.kt` placeholders were deleted (team lead 2026-11-21
    22:55 and the owner's 23:00 answer).
  - Status: partial. The native slot is `implemented` per platform as *The native compile slot* states.
    The empty `transcompile/BaseTransInterface.kt` was deleted too: no converter is left to implement it
    (team lead 2026-11-22 05:40, Decision 10).
  - Minification is its own section, *Code minification* (planned, built after 1.0 by the owner's decision).
    (The `bytecode` level of the `resource` bundle uses `compileall`, which is none of this.)
- (4). Support code update fast track API
  - Status: planned, after 1.0 (owner, 2026-11-21, relayed by the team lead (toolchain report, "Owner decision (2026-11-21 21:55)"): "FastTrack deploy must exist, after 1.0").
    `deploy/code/FastTrackAPI.kt` refuses (no FastTrack server exists); see *Package deployment*.

### PyPackPack Companion (tools designed to be used together)

These are other projects. They are listed for context and are not items of this contract, so they
carry no `Status:`.

#### <toolchain>

- (1). pypackpack wrapping
- (2). Generate a Kotlin library that includes Python code
- (3). Final binary generation feature
- (4). Extend pypackpack's code update fast track to also allow hot-reload during the development stage

`toolchain` consumes this repository's `:packpack` library as
`org.thisisthepy.python.multiplatform:packpack:1.0.0` (`packpack/build.gradle.kts`, `maven-publish`).

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
        - ArchiveWriter.kt  # deterministic .tar.gz (ustar prefix, PAX past it) and .zip writing (binary bundle)
        - Checksum.kt  # SHA-256 of a file, and verification against a pinned digest
        - FileTree.kt  # deleting a tree without following symbolic links out of it (#96)
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
          - DefaultMiddleware.kt  # strategy pattern; the project .venv (python use, root add/remove/sync); also MarkerPolicy
          - environment
            - CrossEnv.kt  # manages per-target dependencies and build/crossenv/<target>
        - backend
          - external
            - UV.kt  # uv downloader
          - BackendInterface.kt  # factory pattern
          - DefaultBackend.kt  # shared Python-version install/list/find/uninstall logic
          - PythonDistributions.kt  # (version, target) -> interpreter archive URL and pinned SHA-256
          - UVBackend.kt
          - MissingWheel.kt  # parses uv's "no wheel for this target" failure
          - CrossBuild.kt  # the cross build environment: builds sdist-only dependencies for a target (#159)
          - CrossBuildScripts.kt  # the environment's Python hook and PEP 517 frontend
          - CrossTargetInstall.kt  # CrossTargetInstaller: pin, install wheels, build the missing ones (#168)
      - compile
        - frontend
          - FrontendInterface.kt  # factory pattern
          - Cli.kt
          - Gradle.kt
        - middleware
          - MiddlewareInterface.kt  # factory pattern
          - DefaultMiddleware.kt  # decorator pattern
          - minification
            - BaseMinifyInterface.kt  # placeholder (*Code minification*, after 1.0)
        - backend
          - external
            - Clang.kt  # C compiler adapter for the compile slot (#20, #155): macOS xcrun clang -arch, Linux host clang/cc, Linux cross zig cc, Windows clang --target, iOS xcrun --sdk clang
            - MSVC.kt  # placeholder
            - NDK.kt  # Android NDK clang adapter for the compile slot (aarch64 and x86_64; #20, #155)
            - XCode.kt  # placeholder; iOS builds go through Clang.kt with the Xcode SDKs (#155)
            - Emscripten.kt  # placeholder
            - Cargo.kt  # Rust crates -> extension modules: discovery, maturin (abi3t), placement in crossenv
            - Meson.kt  # meson.build generation (native and cross), meson setup/compile/install, meson/ninja auto-install
            - MesonCross.kt  # Meson cross file per target: the native slot's compilers, host_machine (#155)
            - TargetPython.kt  # a target's CPython headers, EXT_SUFFIX and link args from python install's layout (#155)
          - BackendInterface.kt  # factory pattern
          - DefaultBackend.kt  # strategy pattern
        - extension
          - ExtensionCompiler.kt  # the native compile slot (#19, #20, #60; docs/design/compile-slot.md): ExtensionCompilerInterface, NativeExtensionCompiler, data types and exceptions
      - bundle
        - AndroidRuntimeLibraries.kt  # DT_NEEDED reader; the NDK runtime libraries (libc++_shared.so) an Android payload needs (#172)
        - BundlerInterface.kt  # factory pattern; BundleType, BundleRequest, BundleResult
        - DefaultBundler.kt  # placeholder
        - GilCheck.kt  # build warning for extensions that run the app with the GIL
        - PythonTags.kt  # wheel python/ABI tags from the target interpreter
        - binary
          - BinaryBundler.kt  # runtime + app + launcher, .tar.gz/.zip (#156)
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
          - CodeAPI.kt  # routes code deploys: single/fat to PyPI, binary/patch to FastTrack
          - PyPIPublishAPI.kt  # uploads the single/fat wheel with uv publish
          - FastTrackAPI.kt  # refuses: no FastTrack server exists
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

Status: implemented (#159). `cli/.../BuildInfoTest.kt` checks the printed version's source, `--version` / `-v`,
the `v` alias, and that `version` prints the uv version the middleware reports (and a failed check on
stderr); `packpack/.../dependency/backend/UVBackendVersionTest.kt` runs the real `uv --version`.

```bash
pypackpack version          # alias: pypackpack v
pypackpack --version        # or -v; also `ppp --version`
```

- `version` prints the pypackpack version and the detected uv version.
- `--version` and `-v` print only the first line, `PyPackPack version <cliVersion>`, and touch neither
  the middleware nor uv. `ppp` runs the same binary, so `ppp --version` prints the same.
- The pypackpack version is `packpack/build.gradle.kts`'s `cliVersion`, which the build writes into the CLI's
  resources (`build-info.properties`, read by `BuildInfo`). It is the PyPI wheel's version too.
- If uv is not detected, downloads uv (`0.13.6`) into `~/.pypackpack/uv` (`dependency/backend/external/UV.kt`) and uses it.
- The download is uv's release archive for the host. The `.tar.gz` archives (Linux, macOS) keep their
  files under one top-level directory, which is stripped; the Windows `.zip` holds `uv.exe` at its root.
  A binary missing after extraction is a failure, not a success (`packpack/.../dependency/backend/external/UVInstallTest.kt`).

#### Distribution through Maven Central

Status: partial (#99). Prepared and rehearsed; nothing is uploaded. Uploading is the user's decision: it
needs a Central Portal account and a verified namespace (below). The check is
`.github/scripts/maven/check_central_bundle.py`, which failed on the earlier publication (no sources or
javadoc jar, no checksums or signatures, no url, license, developer or SCM in the POM).

- The library is `org.thisisthepy.python.multiplatform:packpack:<version>` (`packpack/build.gradle.kts`),
  with sources and javadoc jars (the javadoc jar is empty: the sources are Kotlin) and a POM carrying url,
  Apache-2.0, developer `thisisthepy` and SCM. Every dependency in it is on Maven Central; the CLI's are not
  in it (the `cli` source set is not part of the component).
- Signing uses an in-memory PGP key from `-PsigningInMemoryKey` / `-PsigningInMemoryKeyPassword` (or
  `ORG_GRADLE_PROJECT_signingInMemoryKey` / `..._signingInMemoryKeyPassword` in CI). Without a key nothing is
  signed and `publishToMavenLocal` works as before.
- `./gradlew :packpack:publishAllPublicationsToStagingRepository` writes the repository layout to
  `packpack/build/staging-deploy`; `:packpack:centralBundle` zips it, without `maven-metadata`, into
  `packpack/build/central/central-bundle-<version>.zip`, the format the Central Portal's upload takes.
- Rehearsed 2026-10-17 with a throwaway key: the check passes on the signed staging directory (jar, sources,
  javadoc, POM and Gradle module, each with `.asc`, `.md5`, `.sha1`), `gpg --verify` accepts the signature, and
  the bundle holds 50 files.

To publish (the user's steps): a Central Portal account; the namespace `org.thisisthepy` verified by a DNS
TXT record on thisisthepy.org (it covers `org.thisisthepy.python.multiplatform`, and python-multiplatform's
`org.thisisthepy` after its #95); a PGP key whose public half is on a public keyserver; a Portal user token.
The workflow is python-multiplatform's mechanism (`maven-central.yml`, `central-upload.sh`), at the leader's
request (boost 2026-11-13, #116):

- **Release:** push the tag `packpack-v<packpackVersion>`. `.github/workflows/publish-maven.yml` checks that
  the tag names `gradle.properties`' `packpackVersion` and that the version is not on Central, stages the
  bundle signed with `MAVEN_GPG_PRIVATE_KEY` / `MAVEN_GPG_PASSPHRASE`, runs the check and `gpg --verify` on
  every `.asc`, and then, in the `maven-central` environment, uploads it with
  `.github/scripts/release/central-upload.sh` (`CENTRAL_TOKEN_USERNAME`, `CENTRAL_TOKEN_PASSWORD`) as
  `USER_MANAGED`. The script waits for the Portal's validation and fails on `FAILED`; the deployment then
  waits for a person to press Publish. A missing secret stops the job and names it. The four secrets are
  repository secrets with python-multiplatform's names (#120): the signing job has no environment, so an
  environment-only key would not reach it.
- **Dry run:** a pull request touching the publishing setup (the workflow, `.github/scripts/maven/`,
  `central-upload.sh`, `packpack/build.gradle.kts`, `gradle.properties`) and a manual run do everything except
  the upload, signing with a key made in the job and thrown away, so signing is proven before a tag
  depends on it. A manual run with `dry_run` off publishes the tag it names.

The group and version are written once, in `gradle.properties` (`mavenGroup`, `packpackVersion`).
`publish-pypi.yml` runs only for `v*` releases, so a `packpack-v*` tag does not touch PyPI.

#### Distribution through PyPI

Status: implemented. 0.2.0 is on PyPI (uploaded 2026-10-03 by `.github/workflows/publish-pypi.yml` from
the `v0.2.0` release): the four wheels below, each smoke-tested before upload. `uvx --from pypackpack==0.2.0
pypackpack version` prints `PyPackPack version 0.2.0` (checked 2026-10-17, macOS arm64). PyPI's 0.1.0 is
the old repository's Windows-only wheel. 0.3.0 (`cliVersion` and `pyproject.toml`) is the next
release; it is not on PyPI until a `v0.3.0` GitHub Release is published (notes:
`docs/release-notes/0.3.0.md`). The workflow also builds and smoke-tests the wheels, without
uploading, on every pull request that touches the publishing files.

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
  works, `pypackpack`/`ppp`/`python -m pypackpack version` and `pypackpack`/`ppp --version` print that
  version, and
  `pypackpack init` creates a project.
- Upload is trusted publishing from the `pypi` environment, on a published GitHub Release only.

Limitation

- No wheel for macOS x86_64, Windows arm64 or musl Linux.
- Installing from source (`uv pip install .`) gives only the launcher; `ppp` then says the binary is
  missing and exits 1.

#### Project creation

Status: implemented (#159). `packpack/.../dependency/middleware/DefaultMiddlewareInitTest.kt` covers the
generated `README.md` and `LICENSE`, (with the real uv) that an ancestor `pyproject.toml` is left
untouched, that `--target` is recorded as `platforms`, that an unknown target or an existing project is
refused before uv runs, and the other-tool warning; `cli/.../InitCommandTest.kt` covers the options
reaching the middleware.

```bash
pypackpack init [<path>] [--python <python version>] [--name <project name>] [--package] [--target <target>...]
```

- Project initialization is performed based on `uv init --bare --no-workspace`.
- `init` never modifies a project above the new one. Without `--no-workspace`, uv adds the new project
  as a workspace member of the nearest ancestor `pyproject.toml` and writes `[tool.uv.workspace]` into that
  file, even when it belongs to an unrelated repository. A package inside a ppp workspace is created with
  `package add`, not `init`.
- `path`, `--python`, `--name`, `--package` are passed as `uv init` options, but `uv` only generates `pyproject.toml`, and ppp generates the other necessary files.
- `--target` (the library API's `targets`) records the canonical targets in `[tool.ppp.dependencies].platforms` of the new `pyproject.toml`. An unknown target is refused before `uv init` runs, so nothing is created.
- After `uv init --bare`, ppp additionally generates `.gitignore`, `README.md`, and `LICENSE`. `.python-version` is only generated when `--python` is passed.
- A directory that already has a `pyproject.toml` is refused before `uv init` runs, and the file is left as
  it is. The message says whether it is already a pypackpack project (`[tool.ppp]`), another tool's
  (`[tool.poetry]`, `pdm`, `hatch`, `flit`, `setuptools`), or a plain Python project.
- Another packaging tool's files in the directory (`setup.py`, `setup.cfg`, `Pipfile`, `poetry.lock`,
  `pdm.lock`, `environment.yml`) do not stop `init`; its output ends with a warning naming them, since
  pypackpack reads only `pyproject.toml` (agent proposal, not confirmed: warn rather than refuse).

#### Changing the project's Python version

Status: implemented. `packpack/.../dependency/middleware/DefaultMiddlewareInitTest.kt`
(`changePythonVersionRecreatesTheRootVenvWithTheNewVersionAndSyncs`, against a recording backend).

```bash
pypackpack python use <python version>
```

- Deletes `.venv` at the project root, recreates it with the new Python version, and performs `uv sync`.
- Before that, deletes each package's compile trees (see *Cleaning up subpackage build directories on
  `python use`*).

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

- The runtime is CPython 3.15t (Scope decisions). `3.15` and `3.15t` are shorthands for `3.15.0t`, the
  free-threaded build. The GIL builds 3.14.7, 3.13.0 and 3.15.0 (desktop and mobile) are no longer offered (#72, #124).
- Every (version, canonical target) pair maps to one archive URL and one pinned SHA-256 in
  `dependency/backend/PythonDistributions.kt`, which cites where each digest came from:

| Version | Targets | Source | Digest provenance |
|---|---|---|---|
| 3.15.0t | `aarch64-apple-darwin`, `x86_64-apple-darwin`, `x86_64-unknown-linux-gnu`, `aarch64-unknown-linux-gnu`, `x86_64-pc-windows-msvc`, `aarch64-pc-windows-msvc` | python-build-standalone `20261013`, `cpython-3.15.0+20261013-<triple>-freethreaded-install_only.tar.gz` | `shasum -a 256` of the download, equal to the release's `SHA256SUMS` |
| 3.15.0t | `aarch64-linux-android`, `x86_64-linux-android` | `thisisthepy/python-multiplatform` release `cpython-3.15.0t-mobile`, `python-3.15.0t-<target>.tar.gz` | `thisisthepy/python-multiplatform` `cpython-3.15.0t-mobile` `SHA256SUMS` |
| 3.15.0t | `arm64-apple-ios`, `arm64-apple-ios-simulator`, `x86_64-apple-ios-simulator` | `thisisthepy/python-multiplatform` release `cpython-3.15.0t-mobile`, `python-3.15.0t-iOS-XCframework.tar.gz` (one XCframework for all three) | `thisisthepy/python-multiplatform` `cpython-3.15.0t-mobile` `SHA256SUMS` |
| 3.15.0t | `wasm32-emscripten` | **Interim (#82 (c), team lead 2026-11-27):** `python-3.15.0t-wasm32-emscripten.tar.gz` packed from a python-multiplatform checkout's build output (its `scripts/wasm/build-cpython.sh`, Emscripten 5.0.3) by `.github/scripts/wasm/pack_pmp_runtime.py`, installed from its `file:` URL into `wasm32_emscripten/` (`include/python3.15t`, `lib/libpython3.15t.a`, `lib/python3.15t/_sysconfigdata*`, `runtime/`). Its release asset `cpython-3.15.0t-wasm` (design (a)) waits for the owner's OK; switching replaces `PythonDistributions.wasm315`'s body | the manifest the script writes (`{archive, sha256, source}`), named by `PPP_WASM_RUNTIME_MANIFEST` (or `-Dppp.wasmRuntimeManifest`); without one the install fails naming the script. `DefaultBackendTest.installPython_wasmComesFromTheManifestsLocalArchive`, `PythonDistributionsTest.wasm315tNeedsTheInterimRuntimeManifest` |

- A pair not in the table fails with `Python <version> is not available for <target>. Supported: <version/target, ...>`.
- Every row carries a pin: `PythonDistribution.sha256` is not nullable, so an unpinned archive cannot be
  added.

Limitation

- Sigstore verification (available in python-multiplatform's build) is not offered here.

##### Wheel tags

Status: implemented. `packpack/.../bundle/PythonTagsTest.kt` (the mapping), and the `single`, `fat` and
`patch` bundler tests (`bundle_tagsFollowTheTargetInterpreterVersionAndBuild`,
`bundle_derivesAbi3AndAbi3tWheelTagsFromPayloadExtensions`, and their siblings).

- `BundleRequest.pythonVersion` names the target interpreter: `X.Y` or `X.Y.Z`, with a trailing `t` for
  the free-threaded build (`3.15`, `3.15.0`, `3.15t`, `3.15.0t`). Anything else fails the bundle.
- When the payload contains native extension filenames (`.so` / `.pyd`), `PythonTags.forPayload` derives
  the python and ABI tags from the extension suffixes:
  - A PEP 803 free-threaded stable-ABI extension (`*.abi3t.so`, `*.abi3t-*.so`, `*.abi3t.pyd`) uses
    `abi3.abi3t` as the ABI tag, with the python tag from `BundleRequest.pythonVersion` or `[project] requires-python`'s
    `>=X.Y`/`==X.Y` floor (defaulting to `cp315`).
  - A PEP 384 stable-ABI extension (`*.abi3.so`, `*.abi3-*.so`, `*.abi3.pyd`) uses `abi3` as the ABI tag,
    with the python tag from `BundleRequest.pythonVersion` or `[project] requires-python`'s `>=X.Y`/`==X.Y`
    floor (defaulting to `cp315`).
  - A free-threaded version-specific extension (`*.cpython-<XY>t*.so`, `*.cp<XY>t-*.pyd`) derives
    `cp<XY>-cp<XY>t` when `BundleRequest.pythonVersion` is omitted (and uses `BundleRequest.pythonVersion`
    when explicitly given).
- Otherwise (pure Python or non-`abi3`/`abi3t` non-free-threaded extensions), the `single`, `fat` and `patch`
  bundlers write `cpXY`/`cpXY` for a GIL build and `cpXYt`/`cpXYt` for a free-threaded one (`3.15t` gives
  `cp315-cp315t`) in the wheel file name, the `WHEEL` file's `Tag:` line and the patch archive name. No
  version means the ecosystem interpreter, free-threaded 3.15 (`cp315t`). The old fixed `cp313` tag is gone.

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
- (Fixed by #170.) `Downloader` used to hold the whole archive in memory; it now streams it to `<name>.part` with the
  JDK's `java.net.http` client (redirects followed) and renames it into place when complete.
- packpack runs inside Gradle (toolchain's plugin), where Gradle's embedded kotlin-stdlib wins (2.0.20 in Gradle
  8.11). Nothing on packpack's runtime classpath may need a newer stdlib: packpack's own classes are capped at
  `apiVersion` 1.9, and no library may reference `kotlin.coroutines.jvm.internal.SpillingKt` (stdlib 2.2+). ktor 3.4
  did, which is why the downloader no longer uses ktor (#170). `GradleStdlibCompatTest` scans the runtime classpath;
  `DownloaderTest` covers the redirect and an error status.
- `utils/Archive.kt` has no `.tar.xz` support. It reads ustar names (100-byte name + 155-byte prefix) and takes a
  longer name or link target from a PAX `x` record (`path`, `linkpath`) or a GNU `L`/`K` record; PAX global (`g`)
  records are skipped. python-multiplatform's iOS XCframework archive needs this (its header paths pass 100 bytes;
  run 44973431802). `ArchiveTest.extractArchive_takesLongNamesAndLinksFromPaxAndGnuRecords`.

### Package management features

#### Adding/removing packages

Status: implemented. `packpack/.../dependency/middleware/environment/CrossEnvTest.kt`
(`addPackageCreatesSourceFoldersForInheritedWorkspaceTargets` for `add`,
`removePackageDeletesItsDirectoryAndWorkspaceMember` for `remove`).

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

Status: implemented. `cli/.../TargetListCommandTest.kt` (every `SUPPORTED_TARGETS` entry listed once,
under its family).

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

#### Middleware layer

Decided 2026-10-17 (#69, through the project lead); done in #86.

- The middleware layer stays: it is the CLI's path. `toolchain` calls the backend only (`AGENTS.md`
  rule 13), so this decision does not reach it.
- `CrossEnv` stays the one environment class. It holds the layer's logic: per-target markers
  (`MarkerPolicy`), `build/crossenv/<target>` directories, package scaffolding and workspace propagation.
- `DevEnv` was folded into `DefaultMiddleware` without a behaviour change, and `DevEnv.kt` is gone. Each of
  its functions located the project and made one backend call; the one with logic of its own,
  `changePythonVersion` (delete `.venv`, recreate it, sync), was pinned by
  `DefaultMiddlewarePythonVersionTest` before it moved.
- Both still locate the project through `user.dir`, which the CLI may do and a daemon-hosted caller may not.

#### Policy notes

> - `pypackpack add/remove` works on the project `.venv` (DefaultMiddleware) and operates without a `packageName`
> - `pypackpack <package> add/remove/sync/tree` is CrossEnv-only and requires a `packageName`
> - CrossEnv target dependencies are normalized based on `Platforms.kt`, then managed via `uv add` or `pyproject.toml` editing using a per-target marker
> - Input that directly includes a marker in the dependency string (e.g. `numpy; ...`) is prohibited; only `--target` is allowed
> - `remove --target` does not remove the whole package, only the specified target scope
> - The CLI `--target` input uses a space-separated format (e.g. `--target windows linux`)
> - `tree` is an inspection-only action that shows the resolution result based on the host target or a specified target
> - No per-target *virtual environment* (interpreter) is created; `sync` installs each target's dependencies into a plain directory, `build/crossenv/<target>`

#### Dev environment dependency management

Status: implemented (#159). `cli/.../DependencyCommandTest.kt` and `cli/.../CommandExtensionTest.kt` cover
argument parsing and forwarding to the middleware (against a recording middleware);
`packpack/.../dependency/middleware/DevEnvRealUvTest.kt` runs the root `add`, `tree`, `sync` and `remove`
with the real uv on a real project and checks `pyproject.toml` and the root `.venv` (network: PyPI);
`packpack/.../dependency/backend/UVBackendTest.kt` (`syncDependencies_*`) checks the venv `sync` targets.

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

- Performs `uv sync` targeting the project root's `.venv`. `UVBackend.syncDependencies` names the venv it
  is given through uv's `UV_PROJECT_ENVIRONMENT`; a blank path (the per-package sync) keeps uv's default.

```bash
pypackpack tree [--target <target1> <target2> ...]
```

- When there is no package name, calls `uv tree --python-platform <target>` for each of the host target or the specified targets.
- If no target is specified, uses the single target corresponding to the current host as the default.

#### Per-package target dependency management

Status: implemented. See the per-command lines below. `cli/.../DynamicPackageCommandTest.kt`,
`cli/.../PackageCommandTest.kt` and `cli/.../TargetOptionParsingTest.kt` cover the CLI parsing and
forwarding for all four commands.

```bash
pypackpack <package name> add <pypi name> [--target <target1> <target2> ...]
```

Status: implemented. `packpack/.../dependency/middleware/environment/CrossEnvTest.kt`
(`addDependenciesRunsUvAddOncePerTargetWithThatTargetsMarker`,
`addDependenciesWithoutTargetsOrPlatformsOrWithAMarkerIsRefusedBeforeUv`).

```bash
# example
pypackpack mypackage add numpy --target windows linux
```

- The dynamic package command `pypackpack <package> add/remove/sync/tree` is supported.
- `pypackpack package sync <name> [--target ...]` and `pypackpack package tree <name> [--target ...]` invoke the same logic as an explicit alternative to the dynamic `sync`/`tree` forms.
- The target package is resolved among workspace members by name (member directory name or `[project].name`) or relative path. In a single-package repository without `[tool.uv.workspace]`, `<package name>` resolves to the root project when given its `[project].name`, its root directory name, or `.`.
- `add` computes a marker for each target (`platform_system == '<system>' and platform_machine == '<machine>'`) and repeatedly calls `uv add --package <name> --marker <marker>`.
- `<machine>` is the value uv evaluates for that target under `--python-platform`: `arm64` on macOS and iOS, `AMD64`, `ARM64` and `x86` on Windows, the triple's own `aarch64`/`x86_64`/`riscv64` on Linux and Android, `wasm32` for Emscripten. Status: implemented. `packpack/.../dependency/middleware/MarkerPolicyUvTest.kt` resolves each canonical target's marker with `uv pip compile` against a local wheel and requires it to select exactly its own target.
- Minimum uv version for `x86_64-pc-windows-msvc` target resolution is `0.13.6` (astral-sh/uv#22935 changed `--python-platform x86_64-pc-windows-msvc` from `platform_machine == 'x86_64'` to `'AMD64'`; #107, #109). When `add`, `sync`, or `tree` includes `x86_64-pc-windows-msvc` and the detected uv version is older than `0.13.6`, the command fails before running uv (`MarkerPolicy.requireUvForTargets`), rather than silently skipping `AMD64`-marked dependencies (`MarkerPolicyTest`, `CrossEnvTest.syncDependenciesRefusesUvOlderThan0136ForX8664Windows`).
- Markers written before #49 or #107 (`arm64` for Linux/Android/Windows aarch64, `x86_64` for 64-bit x86 Windows, `i686` for 32-bit Windows) do not match under current uv (`0.13.6+`), so those dependencies are not installed. `remove --target` still finds them under the corrected target (`MarkerPolicyTest`); to install them, remove and add them again.
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
- `BackendInterface.installRequirementsToVenv(venvPath: String, requirements: List<String>, noIndex: Boolean = false, findLinks: String? = null, extraArgs: Map<String, String>? = null, workingDir: File? = null)`: installs named requirement specifiers into an existing virtual environment (`uv pip install --python <venvPath> [--no-index] [--find-links <findLinks>] [--<extraArgs>...] <requirements>...`), without reading or modifying `pyproject.toml` (#40). A blank `venvPath`, a blank or `-`-leading entry in `requirements`, or an empty `requirements` list without `-r`/`requirement` in `extraArgs` fails before running `uv`. Status: implemented. `UVBackendTest` (`installRequirementsToVenv_*`) and, tagged `network`, `UVBackendRealInstallTest.installRequirementsToVenv_withNoIndexAndMissingWheel_failsWithoutReachingPyPI`.
- uv selects wheels by `--python-platform` and by the Python version, which defaults to the
  interpreter uv finds on the host, not the target runtime's. Pass `--python-version` (the runtime's,
  e.g. `3.15`) through the extra arguments when the host's differs. A dependency with no wheel for the
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

Status: implemented. `packpack/.../dependency/middleware/environment/CrossEnvTest.kt`
(`showDependencyTreeRunsUvTreePerTargetAndDefaultsToTheHost`) and the CLI forwarding tests.

- `tree` calls `uv tree --package <name> --python-platform <target>` for each target and prints the results in sequence.
- If `--target` is absent, uses a single host target as the default.

#### Cross build environment (sdist-only dependencies)

Core item (2), "멀티플랫폼 코드를 위한 crossenv 지원". How it is built is the owner's (2026-11-22 05:45, relayed by
the team lead in the toolchain report, "Owner answers"): "직접 구현", pypackpack's own cross environment, a
`_sysconfigdata` override plus a startup hook so the host 3.15t reports the target, then the sdist is built
with the target compiler; no `crossenv` dependency. Recipes live in pypackpack (same source: "pypackpack
안에"). The details below are an agent proposal, not confirmed.

Status: partial. Unit tests: `packpack/.../dependency/backend/CrossBuildTest.kt` (the configuration, the
commands and the recipe handling against a recording runner) and `CrossEnvTest.syncBuildsAnSdistOnlyDependencyInTheCrossEnvironmentAndRetries`.
The real build (`CrossSdistRealBuildTest`, env-gated `PPP_CROSS_SDIST_REAL_BUILD=1`, test.yml `real-builds`)
builds an sdist-only C package for `aarch64-linux-android` with the runner's NDK, once per backend
(setuptools, meson-python, scikit-build-core); the setuptools build is the completion criterion. The same test
builds a setuptools sdist for `aarch64-unknown-linux-gnu` (`zig cc`, tag `manylinux_2_17_aarch64`) on the ubuntu
runner and for `arm64-apple-ios-simulator` (Xcode, tag `ios_13_0_arm64_iphonesimulator`) in `real-builds-host` on
macOS.

- **When:** `sync` installs a target's dependencies (above). When uv fails with `NoWheelForTargetException`
  and only an sdist exists (`SdistAvailability.ONLY_SDIST`), `sync` builds that sdist for the target in the
  cross environment, then repeats the install with `--find-links <the built wheels>`. It repeats until the
  install succeeds or a package fails again after its wheel was built (then it fails naming it). A package
  with no sdist (`NO_SDIST`) or an install with building disabled (`UNKNOWN`) fails as before. The host
  target never uses it (uv builds host sdists itself).
- **Where:** `<package>/build/packpack/cross/<canonical target>/`, not `build/crossenv/<target>`: that
  directory is `uv pip install --target` output and the `resource` bundle vendors it. It holds `env/` (the
  environment), `sdists/` (downloaded sdists), `src/` (unpacked sources: deleted once their wheel is built, kept
  when the build fails, #170) and `wheels/` (the built wheels, the
  `--find-links` directory). The host CPython (`installPython(<version>, <host target>)`, the pinned
  python-build-standalone 3.15t) goes to `<package>/build/packpack/cross/host-python/` and is reused.
- **The environment:** `uv venv --python <host python> env`. Inside it:
  - `ppp-cross/prefix/` is a synthesized prefix of the target CPython: `include/python3.Xt` links to the target's
    headers and `lib` to its library directory, the layout `sysconfig` expects. The target CPython comes from
    `ppp python install <version> <target>` (`locateTargetPython`), as for the Cargo and Meson cross builds.
  - `ppp-cross/sysconfig/` holds a copy of the target's `_sysconfigdata_*.py`.
  - `ppp-cross/config.json` names the target: the wheel platform (`android-24-arm64_v8a`,
    `ios-13.0-arm64-iphoneos`, `manylinux_2_17_aarch64`, ...), `sys.platform`, the multiarch, the `os.uname()`
    system and machine, and the Android API level.
  - `site-packages/_ppp_cross.pth` imports `_ppp_cross`, which does nothing unless `PPP_CROSS_CONFIG` names
    that file. When it does, it imports `subprocess` first (it decides at import whether the platform has
    processes, and `ios` has none), sets `_PYTHON_SYSCONFIGDATA_NAME` and `_PYTHON_HOST_PLATFORM` (the wheel
    platform), makes `sysconfig.get_platform()` return the wheel platform (CPython versions read
    `_PYTHON_HOST_PLATFORM` differently), puts
    `ppp-cross/sysconfig` on `sys.path`, sets `sys.platform`, `sys.implementation._multiarch`,
    `sys.cross_compiling`, `sys.base_prefix` / `sys.base_exec_prefix` (the synthesized prefix), `os.uname()`
    and, for Android, `sys.getandroidapilevel()`; it answers `platform.android_ver()` / `platform.ios_ver()`
    from the config (they would otherwise query the device), and makes `sysconfig` reload its variables.
- **The build:** a PEP 517 frontend of pypackpack's own (`ppp-cross/pep517.py`, standard library only) runs
  the sdist's backend (`[build-system].build-backend`, default `setuptools.build_meta:__legacy__`). First, in
  host mode, uv installs `[build-system].requires` into the environment (`uv pip install --python`), then the
  backend's `get_requires_for_build_wheel`. Then `build_wheel` runs in cross mode (`PPP_CROSS_CONFIG` set)
  with `CC`, `CXX`, `AR`, `LDSHARED` (`$CC -shared`) and `LDFLAGS` (the target's link arguments) set to the
  target toolchain the Meson cross build uses (`MesonCross.forTarget`: the NDK's clang for API 24, `zig cc`
  for Linux cross targets, `xcrun --sdk ... clang` for iOS). uv's own frontend is not used because uv would
  query the interpreter and see the faked platform. Backends that read `sysconfig` and these variables
  (setuptools, and so most C sdists) build. meson-python gets the Meson cross build's cross file
  (`ppp-cross/meson-cross.ini`, with `python` set to the environment's interpreter, which reports the target
  in cross mode) and the target's link arguments (`c_link_args`), through the config setting `setup-args = ["--cross-file=..."]`.
  scikit-build-core gets a CMake toolchain file (`ppp-cross/cmake-toolchain.cmake`, the config setting
  `cmake.define.CMAKE_TOOLCHAIN_FILE`): the target system (`Linux` for Android and Linux, `iOS` with its sysroot,
  architecture and deployment target) and processor, and the target compilers and `ar`, each behind a one-line
  wrapper script because CMake takes one executable. It finds Python through the interpreter, which reports the
  target in cross mode: FindPython runs an interpreter while cross-compiling only through
  `CMAKE_CROSSCOMPILING_EMULATOR` under policy CMP0190 NEW (CMake 4.1+), so the settings ask for
  `cmake.version = ">=4.1"` (scikit-build-core installs CMake when the host's is older) and
  `cmake.define.CMAKE_POLICY_DEFAULT_CMP0190 = "NEW"`, and the toolchain names `ppp-cross/bin/emulator`, which
  runs the environment's interpreter and refuses any other program (a target binary cannot run on the build
  host). The backend's dynamic requirements are asked with the same settings.
- **The sdist:** `name==version` from uv's message, fetched through PyPI's JSON API
  (`https://pypi.org/pypi/<name>/<version>/json`), the `sdist` entry, its `sha256` checked.
- **Recipes** (`pip-jit`, owner: "pypackpack 안에"): `[tool.ppp.recipes.<name>]` in the package's
  `pyproject.toml`, keyed by the normalized distribution name: `patches = ["recipes/foo.patch", ...]` (paths
  relative to the package, applied in order with `patch -p1 --forward --batch` in the unpacked source before
  the build; `patch` must be on `PATH`) and `env = { KEY = "value" }` (added to the build's environment).
  A patch that does not apply fails the build naming it.
- **Recipe directories** (#168; format approved by the leader on 2026-11-25, Decision 16 in the toolchain report:
  "`<name>.toml`, keys as `[tool.ppp.recipes.<name>]`"): `CrossSdistBuilder.buildFromPyPI` / `buildSdist` take
  recipe directories. A recipe for `<name>` is the file `<dir>/<name>.toml` (the normalized name, its `_` spelling
  or the name as uv printed it) holding `patches` and `env` at its top level. Its patch paths are relative to that
  directory. The package's own `[tool.ppp.recipes.<name>]` wins over every file, then the first directory that
  has one (agent proposal, not confirmed). A key other than `patches` and `env`, a table, or a missing patch fails
  naming the file, before anything is built. toolchain's `pip { jit { localRecipes } }` passes these directories
  (toolchain#152).
- **`PATH`:** the build runs with the environment's `bin` first on `PATH`, so the build requirements' programs
  (Cython, meson, ninja, maturin's binary) are the environment's, not the machine's (#168: a Homebrew Cython 0.29
  made meson reject numpy's `cython`; maturin's hooks found no `maturin`).
- **maturin** (#168): an sdist whose backend is `maturin` is cross-compiled by Cargo, for Android only
  (`aarch64-linux-android`, `x86_64-linux-android`, which are also Rust's triples). The build adds
  `MATURIN_PEP517_ARGS = "--target <triple>"`, `PYO3_CROSS_LIB_DIR` (the target CPython's library directory,
  whose `_sysconfigdata` gives PyO3 the free-threaded ABI), `PYO3_CROSS_PYTHON_VERSION` (`3.15`),
  `PYO3_CROSS_PYTHON_IMPLEMENTATION = CPython`, Cargo's `CARGO_TARGET_<TRIPLE>_LINKER` and the `cc` crate's
  `CC_<triple>` / `CXX_<triple>` / `AR_<triple>` (the target compilers behind one-line wrappers), and
  `CARGO_TARGET_<TRIPLE>_RUSTFLAGS` with the target's link arguments (`-C link-arg=-lpython3.15t`), as the Cargo
  backend's Android path sets them. The Rust target must be installed (`rustup target add <triple>`). Another
  cross target is refused naming it. Status: unit test `CrossBuildTest.maturinGetsTheRustTargetAndThePyO3CrossEnvironmentOnAndroidOnly`;
  real build `CrossSdistRealBuildTest` (a PyO3 0.30 crate, wheel `cp315t-android_24_arm64_v8a`, aarch64 ELF).
  **iOS** (#182; team lead 2026-11-27; stable Rust): `arm64-apple-ios`, `arm64-apple-ios-simulator` and
  `x86_64-apple-ios-simulator` too, with Rust's triples (`aarch64-apple-ios`, `aarch64-apple-ios-sim`, `x86_64-apple-ios`).
  `PYO3_CROSS_LIB_DIR` is the slice's own architecture directory (`lib-arm64`; a simulator slice holds two),
  `IPHONEOS_DEPLOYMENT_TARGET` is 13.0 (the `ios_13_0_*` tag), and the link arguments name Python.framework
  (`-F<slice> -framework Python`). `CrossBuildTest.maturinGetsTheRustTargetAndThePyO3CrossEnvironmentOnAndroidOnly` (its iOS
  half), `maturinForTheIosSimulatorUsesItsRustTriple`; real build `CrossSdistRealBuildTest` on a macOS host (CI's
  `real-builds-host`, which adds the `aarch64-apple-ios-sim` target): wheel `cp315t-ios_13_0_arm64_iphonesimulator`, an
  arm64 Mach-O. Run locally (2026-11-27): passed.
- **iOS compiler shims** (#182): meson-python adds its own iOS cross file naming CPython's iOS compiler shims
  (`<target>-clang`, `-clang++`, `-cpp`, `-ar`, `-strip`, which CPython's Apple build puts on `PATH`), so the cross
  environment writes them into its `bin` (first on `PATH`), running the cross build's `xcrun` commands. Without them
  numpy 2.5.5 stopped with "Unknown compiler(s): [['arm64-apple-ios-simulator-clang']]". `CrossBuildTest.anIosSimulatorTargetTakesItsOwnArchitecturesSysconfigData`.
  numpy 2.5.5 from PyPI then builds for the arm64 simulator with a recipe defaulting `longdouble_format` to
  `IEEE_DOUBLE_LE` (Apple arm64's long double is a double; Android's recipe is `IEEE_QUAD_LE`): `CrossSdistRealBuildTest`
  (`PPP_NUMPY_IOS_REAL_BUILD=1`, not run in CI: numpy is long). Run locally (2026-11-27): without the recipe the build
  stops at numpy's long double probe ("Can not run test applications in this cross environment"); with it the wheel is
  `numpy-2.5.5-cp315-cp315t-ios_13_0_arm64_iphonesimulator.whl` with an arm64 `_multiarray_umath`. Not yet imported on a
  simulator.
- **Installing for a target from the backend layer** (#168): `CrossTargetInstaller.install(packageDir, targetDir,
  platform, requirements, uvOptions, workingDir, recipeDirs)`, for callers in a Gradle daemon (every directory is a
  parameter). It pins the requirements for the target with `uv pip compile --python-platform` (which reads sdist
  metadata and builds nothing), installs the pins with `--only-binary :all: --no-deps --target`, and for each pin uv
  reports as having no usable wheel (`numpy==2.5.5 has no usable wheels`) builds it with `buildFromPyPI` and the
  recipe directories, then repeats with `--find-links <the built wheels>` beside the caller's own `find-links`. A
  pin that still has no wheel after it was built ends it, naming it. Every package built here is named with
  `--reinstall-package` on that retry: a rebuild (after a recipe change) keeps the version and so the wheel's file
  name, and uv otherwise installs the `--find-links` wheel it saw before from its cache, even into a cleared target
  (toolchain team, 2026-11-27; leader-approved fix). `CrossBuildTest.aPackageBuiltInTheCrossEnvironmentIsReinstalledNotTakenFromUvsCache`;
  checked through toolchain's `pip { jit }` with a pyyaml 6.0.4 recipe: after its marker went from A to B, develop
  installed A again although the rebuilt wheel held B; with this change the next edit (C) was installed. Unlike `sync`, nothing is first built with the
  host compiler, which for numpy or a Rust package costs minutes. The cross environment lives under
  `<packageDir>/build/packpack/cross/<target>/`. Before its first build it installs the target's free-threaded
  CPython (`<python-version>t` from the uv options, else 3.15t) into `<packageDir>/<installDirName>`
  (`android_arm64`, ...) with `installPython`, unless one is found at or above `packageDir`, because the build
  reads that CPython's headers and `EXT_SUFFIX` there (#170; toolchain's `pip { jit }` passes a fresh directory).
  An install that builds nothing fetches no CPython; a failed CPython install fails naming the target. Status:
  unit tests `CrossBuildTest.theTargetInstallPinsThenBuildsEachPinWithoutAWheelAndRetries` and
  `theTargetInstallFetchesTheTargetsCPythonBeforeItsFirstBuildOnly`.

#### Cleaning up subpackage build directories on `python use`

Status: implemented (#159). `packpack/.../dependency/middleware/DefaultMiddlewareInitTest.kt`
(`changePythonVersionDeletesEachPackagesCompileTreesAndKeepsTheRest`,
`changePythonVersionInASinglePackageRepositoryDeletesTheRootCompileTree`).

- `python use` deletes `build/packpack/` in every workspace member listed in `[tool.uv.workspace].members`
  (a path relative to the project root; glob patterns are not expanded), or in the project root when it has
  no workspace members. Those are the Meson build trees `pypackpack build` configures against the
  project's interpreter, so they are stale once the Python version changes.
- Nothing else is deleted: a package's other `build/` contents, its `dist/` (built artefacts), and the
  root's `build/crossenv/` (reinstalled per target by `sync`) stay. Symbolic links are not followed.
- This runs before the `.venv` is deleted; if a tree cannot be deleted, `python use` fails and leaves the
  `.venv` as it was. A listed member that does not exist is skipped.

### Build, bundling, deployment

ppp distinguishes four stages: `build`, `compile`, `bundle`, `deploy`.

- `compile`: Converts source into an executable intermediate artifact.
- `bundle`: Packages the compile artifact together with resource/dependency/runtime metadata into a single deployment unit.
- `build`: The upper-level orchestration stage that coordinates dependency verification, compile, and bundle.
- `deploy`: Uploads the bundle artifact to external targets such as PyPI, FastTrack, ResourceHub, etc.

Of these four stages, the `pypackpack build` CLI command drives `compile` (via Meson) and, given a bundle kind, `bundle` (see *Package build*); it does not perform dependency verification. `bundle` is also a library API (`BundlerInterface.create(BundleType)`); there is no separate `bundle` command. `pypackpack deploy` exists, but every deploy type refuses.

#### Package build

Status: partial. `packpack/.../compile/backend/external/MesonTest.kt` (meson.build generation,
meson/ninja auto-install), `packpack/.../compile/backend/DefaultBackendTest.kt` (build and install
directories per type, level and target) and `packpack/.../compile/frontend/BuildCommandTest.kt` (the bundle
forms, #81).

```bash
pypackpack build <package name> source [<bundle type: default binary>] [--type <build type: default debug>] [--level <build level: default instant>] [--target <target name>] [--overwrite]
pypackpack build <package name> resource [--target <target name>] [--type <build type>] [--level <build level>] [--overwrite]
pypackpack build <package name> [--type <build type>] [--level <build level>] [--target <target name>] [--overwrite]
```

The first two are the maintainer's spec sheet's (`spec.md`, 686b1ad): `build` takes the bundle as an
argument, and there is no `bundle` command (#81). The third, with no bundle kind, only compiles.

- `<package name>` is resolved among workspace members by path, directory name, or `[project].name`. In a single-package repository without `[tool.uv.workspace]`, `<package name>` resolves to the root project when given its `[project].name`, its root directory name, or `.`.
- `source [<bundle type>]` compiles the package (below) for the canonical target triple, then bundles it
  with `BundlerInterface.create(<bundle type>)`: `binary` (the default unless `[tool.ppp].bundle` names another; see *Bundle Type*),
  `fat`, `single` or `patch`. A failed compile stops before the bundle.
- `resource` bundles the package *source* with the `resource` bundler; nothing is compiled, because that
  bundle reads source (*Bundle Type*).
- With a bundle kind, `--target` is normalized to the canonical triple (an alias such as `macos` is
  accepted) and defaults to this machine's; an unknown target is refused before anything runs. The
  result line names the artifact (or, for `resource`, the output directory), and each of the bundle's
  `warnings` (the GIL warning) is printed as `warning: ...` on standard error.
- An unknown bundle kind, a bundle type after `resource`, or `resource` as a source bundle type is
  refused (exit 1) before anything runs.

- Auto-generates a `meson.build` for the package by scanning the first source root containing Python packages in order: `[tool.ppp].python-dir` (when configured in `pyproject.toml`), `src/main`, `src`, `python`, `<package>/python`, and the package root. Inside each Python package, `.c`/`.cc`/`.cpp`/`.cxx` files become Meson `py.extension_module()` targets when `[tool.ppp].c-extensions` lists their project-relative path, or (when `[tool.ppp].c-extensions` is omitted) when their name starts with `_` or their source references `PyInit_`, `PyModExport_`, or `Python.h` (`c-extensions = []` disables C/C++ extension scanning completely, #144); all other non-excluded files (including `.py`, `.pyi`, `py.typed`, package data files, and data/template `.c`/`.cpp` files) become `py.install_sources()`, while `__pycache__`, `*.pyc`, `*.pyo`, dot-entries, `Thumbs.db`, `meson.build`, and Rust crate directories (`Cargo.toml`) are excluded.
- Before anything is built, verifies the package's dependencies (the sheet's orchestration, #159):
  `uv sync --check` in the workspace root (`--package <[project].name>` in a workspace,
  `compile/backend/DefaultBackend.kt`'s `UvDependencyCheck`). An environment out of sync with the lock and
  `pyproject.toml` stops the build with a message naming `pypackpack sync` and uv's output
  (`DefaultBackendTest`: `compile_verifiesDependenciesBeforeBuildingAndStopsOnAFailedCheck`,
  `uvDependencyCheck_runsUvSyncCheckForTheMember`).
- The compile backend takes the workspace directory explicitly (`BackendInterface.compile(..., workingDir)`,
  AGENTS.md rule 13); only the CLI's middleware passes `user.dir`. The tests point `user.dir` elsewhere.
- Runs `meson setup` / `meson compile` / `meson install` for the package by shelling out to the `meson` CLI. `setup` probes `meson --version`/`ninja --version` first (`Meson.isMesonInstalled()`, which also looks in `uv`'s tool directory) and calls `Meson.installMeson()` (`uv tool install meson`/`ninja`) when either is missing, so `meson`/`ninja` do not need to be pre-installed on `PATH`.
- `--type` is passed to Meson as `--buildtype=<type>`. `--type`, `--level` and `--target` select the directories: the Meson build directory is `<package>/build/packpack/single/<type>/<level>/<target>`, and `meson install` writes to `<package>/dist/<target>/<type>/<level>` (`<target>` is `default` when `--target` is omitted).
- `--overwrite` regenerates `meson.build` (erroring otherwise if one already exists) and clears the build directory first.
- Each `py.extension_module()` sets `limited_api:` derived from `pyproject.toml`: `[project].requires-python`'s
  `>=3.<minor>` / `==3.<minor>` floor, else `[tool.ppp.rust]`'s `abi3-py3<minor>` / `abi3t-py3<minor>` ABI,
  defaulting to `'3.15'` (#142; so `meson.build` asks for Meson 1.3.0 or later). On a free-threaded 3.15
  interpreter, `'3.15'` defines `Py_LIMITED_API` with `Py_GIL_DISABLED`, which CPython's
  `pyabi.h` turns into PEP 803's `abi3t`: the module is `<name>.abi3t-<platform>.so`. abi3t sources must
  export their module with PEP 793's `PyModExport_<name>` slots (`PyModuleDef_HEAD_INIT` is hidden), so a
  package with classic sources opts out with Meson's own `-Dpython.allow_limited_api=false`. Test:
  `MesonTest.makeMesonBuild_targetsTheStableAbiFrom315ByDefault`,
  `MesonTest.makeMesonBuild_derivesLimitedApiFromRequiresPythonOrRustAbi`.
- `meson setup` builds against the nearest `.venv` interpreter above the package (the same lookup the
  `bytecode` level uses), passed through a native file `build/packpack/meson-python.ini`
  (`[binaries] python = ...`). Without a `.venv`, Meson uses the interpreter it runs on itself, uv's tool
  Python, which rejects `limited_api: '3.15'` below 3.15. Test: `MesonTest.setup_buildsAgainstTheProjectVenvInterpreter`.

Limitation

- `--level` changes only directory names (the levels act in the bundlers). `--target` cross-compiles (#155): a target that is not this machine's builds with a Meson cross file (`build/packpack/meson-cross-<target>.ini`, `compile/backend/external/MesonCross.kt`) and the native slot's compilers for it (zig cc for Linux, the NDK for Android, `xcrun --sdk` for iOS; Windows only on its own machine), against the target's CPython found where `pypackpack python install 3.15t <target>` puts it (`TargetPython.kt`, the Cargo backend's layout). Meson cannot run the target interpreter, so that `meson.build` names the target's headers and `EXT_SUFFIX` with `shared_module` and `install_data` instead of `import('python')`. Tests: `MesonCrossTest`, `DefaultBackendTest.compile_crossBuildsANonHostTargetAndRefusesAnUnknownOne`; a real aarch64 Linux build in CI is `MesonCrossRealBuildTest` (test.yml `real-builds`).
- `--target` is normalized to its canonical triple (an alias such as `android_24_arm64` is accepted) before anything runs, so the output lands in `dist/<canonical target>/...` where the bundlers look; an unknown target is refused.
- Only the Meson and Cargo backends are implemented; the MSVC/XCode/Emscripten backend adapters and the minification middleware are empty placeholder files (Clang and NDK are the native slot's adapters; TypedPython, not Nuitka, Cython or Lpython, generates the C it compiles), so C/C++ extensions (Meson, any target #155 lists) and Rust extensions (Cargo: host, Linux manylinux, Windows on its own machine, Android, iOS) compile, and there is no pure-Python compilation/optimization/minification.
- A second compile without `--overwrite` fails on the existing `meson.build` (see `--overwrite` above).

Package metadata (#159). The sheet: "패키지의 pyproject.toml에 빌드해야 할 의존성 패키지의 범위 기술 필요 (기본적으로는
전부 다 같은 레벨로 빌드)" and "단일 파일로 전부 다 묶는건지 따로 따로 빌드할건지 기술 필요 (메타 데이터)". The keys are an
agent proposal, not confirmed. Status: implemented.

- `[tool.ppp].build-dependencies = ["<distribution>", ...]` names the installed dependencies the build level
  applies to in the `resource` bundle, which carries installed dependencies (the wheel bundlers carry
  workspace packages, not installed ones). Names are compared PEP 503-normalized. Absent, every dependency is built at the same level (the
  sheet's default); `[]` builds none. A dependency outside the list ships as source: its top-level entries
  (from its `*.dist-info/RECORD`) are skipped by `compileall` and keep their `.py` in `release`. A listed
  name that is not installed is a bundle warning. `ResourceBundlerTest`:
  `bundle_buildDependenciesLimitsWhichDependenciesAreCompiled`, `bundle_withoutBuildDependenciesEveryDependencyIsCompiled`.
- `[tool.ppp].bundle = "binary" | "fat" | "single" | "patch"` is the bundle `build <package> source` uses
  when the command names none: `fat` puts the package and its dependencies in one file, `single` the package
  alone. The command line wins; an unknown value is refused before compiling; absent, `binary`.
  `cli/.../BuildCommandTest.kt`.

Not yet implemented (target). Status: planned, in 1.0 (#158, blocked on a server)

- Server-side patch version tracking (base version ledger, server management page).

#### Rust extension modules (Cargo backend)

Status: implemented for the host (stage 1, #92), Linux cross-build (`manylinux_2_17`, #138), Android and iOS (stage 2, #94), ABI / feature configuration (#135), and Windows on its own machine (#150): `compile/backend/external/Cargo.kt`;
`packpack/.../compile/backend/external/CargoTest.kt` (discovery both ways and its refusals, `[tool.ppp.rust]` ABI and feature options, torchnative's
layout, the maturin and cargo commands and environments per target, the placement, every refusal) and
`CargoRealBuildTest.kt` (real builds, run with `PPP_CARGO_REAL_BUILD=1`: the host crate imported on 3.15t
without the GIL; with `PPP_ANDROID_PYTHON` and an NDK, an Android cross build; with `PPP_IOS_PYTHON` and
Xcode, the iOS device and simulator builds), and `compile/backend/DefaultBackendTest.kt` (`build` runs it
after Meson, with Meson's destdir). Decisions of 2026-10-17 (#65, through the project lead). The first user
is torchnative (thisisthepy/torchnative#53).

##### Stage 1: the host (implemented)

- `pypackpack build <package>` (and `build <package> source ...`) builds the package's crates after Meson.
  With no crate nothing runs. A target other than the host takes one of the paths below, or is refused.
- Each crate: `uv tool run --from maturin==1.15.0 maturin build --manifest-path <crate>/Cargo.toml
  --interpreter <.venv python> --out build/packpack/cargo/<triple>/<module> --target-dir
  build/packpack/cargo/target [--no-default-features] [--features <features>] [--release]` (`--release` for
  build type `release`). By default `--features pyo3/abi3t-py315` is passed; configured `abi`, `features`, and
  `default-features = false` / `no-default-features = true` in `[tool.ppp.rust]` adjust the flags, and
  version-pinned ABIs (`cp3<minor>[t]`) omit any `pyo3/abi3*` feature.
  `uv tool run` installs and links nothing; Cargo's build directory stays out of the source tree. With the
  default `abi3t-py315`, the wheel is tagged `cp315-abi3.abi3t-<platform>` (PEP 803).
- The wheel's one extension file is placed at the module's path in the `site-packages` Meson installed the
  package into, under `dist/<target>/<build type>/<build level>` (`core/_fast.abi3t.so`). The crate's module
  is the package's own, like a Meson C extension, so `single` and `fat` carry it the same way (checked end to
  end: `build core source single` gives a wheel with `core/__init__.py` and `core/_fast.abi3t.so`). Only that
  file: for a dotted module name maturin's wheel holds `<last>/__init__.py` and `<last>/<last>.abi3t.so`, which
  would import as the wrong module (checked with maturin 1.15.0).
- This differs from the plan below, which had the wheel installed into `build/crossenv/<triple>` like a
  third-party prebuilt wheel: `fat` vendors from there only what a `dist-info` RECORD lists, so a loose
  extension there reached no bundle. Like a C extension, it does not reach the `resource` bundle, which
  reads source (*Bundle Type*).
- The extension's name must be the module's last segment (PyO3's `PyInit_<name>`): otherwise the build is
  refused, naming the `[lib] name` to set.
- Refusals before maturin runs: no `cargo` on `PATH` (names rustup), no `uv`, no `.venv` interpreter (names
  `pypackpack python install 3.15t`), no single `site-packages` under the destdir. A failed maturin build
  fails with its output.
- A crate directory inside a Python package is skipped by Meson's scan and left out of the `resource`
  payload (`MesonTest.makeMesonBuild_skipsRustCrateDirectories`,
  `ResourceBundlerTest.bundle_leavesRustCrateDirectoriesOutOfThePayload`).

##### Stage 2: Android and iOS (implemented)

The target Python is the one `pypackpack python install <version> <target>` puts in the project
(`android_arm64`, `android_x86_64`, `ios_arm64`, `ios_simulator_arm64`, `ios_simulator_x86_64`), found in the
nearest such directory above the package (`pypackpack python install 3.15t <target>` installs
`thisisthepy/python-multiplatform` `cpython-3.15.0t-mobile`; #163, #328, #124).
Each target needs its Rust target installed (`rustup target add <triple>`); a missing one is refused,
naming that command, before anything builds.

- **Android** (`aarch64-linux-android`, `x86_64-linux-android`): maturin with `--target <triple>` and no
  interpreter. The target Python comes through `PYO3_CROSS_LIB_DIR=<prefix>/lib` and
  `PYO3_CROSS_PYTHON_VERSION=<abi version>` (`3.15` for `abi3t-py315` and `cp315t`, `3.13` for `abi3-py313`);
  the NDK's `<triple>24-clang` (API 24, PEP 738's floor) is the linker
  and C compiler and `llvm-ar` the archiver. The NDK is `ANDROID_NDK_HOME`, else the newest
  `ndk/<version>` under `ANDROID_HOME` (or `ANDROID_SDK_ROOT`); none is refused, naming both. The extension
  links the target's `LDLIBRARY` (`libpython3.15t.so`) through `CARGO_TARGET_<TRIPLE>_RUSTFLAGS`, as
  CPython's own Android extensions do: PyO3's abi3 link name, `python3`, is a library without symbols that
  the linker drops, and an app that opens libpython privately would leave the extension's symbols
  unresolved. Checked with NDK r29: the wheel is `...-cp315-abi3.abi3t-android_24_arm64_v8a.whl`, and its
  AArch64 extension needs `libpython3.15t.so` and exports `PyModExport__fast`. Not run on a device.
- **iOS** (`arm64-apple-ios`, `arm64-apple-ios-simulator`, `x86_64-apple-ios-simulator`): `cargo rustc --lib
  --target <aarch64-apple-ios | aarch64-apple-ios-sim | x86_64-apple-ios>` (with `--no-default-features` and
  `--features` per the crate's configuration), not maturin, which builds iOS only
  inside a cross virtual environment that python.org's XCframework does not provide (this is the fallback the
  plan below names). pypackpack writes the PyO3 config (`build/packpack/cargo/<triple>/pyo3-config.txt`:
  CPython `<abi version>`, `target_abi=CPython-abi3t-3.<minor>` for `abi3t` or `CPython-abi3-3.<minor>` for
  `abi3`, omitted for version-pinned `cp3<minor>[t]`, shared, `Py_GIL_DISABLED` when the target is
  free-threaded, link lines suppressed) and links the XCframework slice's `Python.framework`
  (`-F<slice> -framework Python`) with `-install_name @rpath/<file>`, for iOS 13.0
  (`IPHONEOS_DEPLOYMENT_TARGET`). `lib<last>.dylib` is placed as `<last>.abi3t.so` (`abi3t`),
  `<last>.abi3.so` (`abi3`), or `<last><EXT_SUFFIX>` (version-pinned, reading `EXT_SUFFIX` from the slice's
  `_sysconfigdata` or falling back to `.cpython-3<minor>[t]-darwin.so`); another lib name is refused, naming
  the `[lib] name` to set. No Xcode (`xcrun --sdk iphoneos`) is refused. Checked: the device build is an iOS
  binary (minimum 13.0) and the arm64 simulator build an iOS-simulator binary (minimum 14.0, the lowest
  Rust's `aarch64-apple-ios-sim` allows), each linking `@rpath/Python.framework/Python` like CPython's own
  iOS extensions. Not run on a device or simulator; turning the extension into a framework for the app is the
  Xcode phase's work, as for any binary module.

##### Linux cross-build (`manylinux_2_17`) (implemented, #138)

- Linux cross targets (`x86_64-unknown-linux-gnu`, `aarch64-unknown-linux-gnu`, `x86_64-manylinux2014`,
  `x86_64-manylinux_2_17`, `x86_64-manylinux_2_28`, `aarch64-manylinux_2_17`, `aarch64-manylinux_2_28`) build
  through `uv tool run --from maturin==1.15.0 --with ziglang maturin build --zig --compatibility
  <manylinux_2_17|manylinux_2_28> --target <rust-triple>` with no `--interpreter`, reading the target Python from
  `<project>/linux_x86_64/lib` or `<project>/linux_arm64/lib` (`PYO3_CROSS_LIB_DIR`,
  `PYO3_CROSS_PYTHON_VERSION`). When the canonical target matches the host (`x86_64-unknown-linux-gnu` or
  `aarch64-unknown-linux-gnu` on the same Linux host, or `target = null`), the host `.venv` maturin build is
  kept. Missing `rustup target add <rust-triple>` or missing target Python directory is refused before maturin
  runs.
- Owner decision (2026-11-19): Windows builds stay host-only on Windows runners (no `cargo-xwin` or `llvm`
  cross-toolchain), and WASM (`wasm32-emscripten`) waits for `python-multiplatform#175`.

##### Windows on its own machine (implemented, #150)

- On Windows on ARM the CLI runs as the `win_amd64` native image under x64 emulation (GraalVM ships no Windows
  aarch64 build, #147), so its host is `x86_64-pc-windows-msvc`. A Windows target (`x86_64-pc-windows-msvc`,
  `aarch64-pc-windows-msvc`) other than that host builds when it is the machine's own architecture, read from
  `PROCESSOR_ARCHITEW6432`, `PROCESSOR_ARCHITECTURE` or `PROCESSOR_IDENTIFIER` (`ARMv8 ...`), which an emulated
  process still sees. This is a native MSVC build on the machine, not a cross toolchain, so the owner decision
  above holds.
- The build is `maturin build --target <triple> --interpreter <project>/<windows_arm64|windows_amd64>/python.exe`,
  the target Python `pypackpack python install 3.15t <triple>` put in the project (it runs natively). maturin
  tags the wheel `win_arm64` (or `win_amd64`); the `.pyd` is placed as for the host, and the `single` bundle tags
  its wheel from the target (*Wheel tags*).
- Refused before maturin runs: a Windows target on a machine of another architecture (naming the machine),
  a missing Rust target (naming `rustup target add <triple>`), and a missing target Python (naming
  `pypackpack python install 3.15t <triple>`).
- Tests: `CargoTest` (`a Windows target that is the machine's own builds with maturin against the target Python`,
  the refusals, and the environment detection). A real build is checked by torchnative's `publish-pypi` dry run
  on `windows-11-arm`; not yet run.

Limitation

- Cargo writes `Cargo.lock` beside the crate's `Cargo.toml`, as it always does.

##### The plan and the decisions

A Rust crate becomes a CPython extension module for each target and ships in the app through the
`resource` bundle. Kotlin reaches it through python-multiplatform like any other Python module; calling
the crate from Kotlin directly (cinterop, JNI) is out of scope.

- **Build: maturin and PyO3.** maturin already knows PyO3's build configuration, the cross-compile
  environment (`PYO3_CROSS_*`) and the platform wheel tags. It is installed with `uv tool install maturin`,
  as meson and ninja are. Each crate gives one wheel per target, which then takes the path a library's
  prebuilt native wheel takes: installed into `build/crossenv/<triple>`, carried by the `resource` bundle
  (`libDirs`). No new bundler code.
  - maturin's mobile support is shipped, not declined. iOS: "Adding support for iOS" (PyO3/maturin#1742)
    closed as completed by "Initial iOS support" (PyO3/maturin#2102, merged 2024-06-06), then wheel
    naming, cross virtual environments and simulator tags (PyO3/maturin#2827, #2828, #2851, merged
    2025-11). Android: "Implement Android platform tag support" (PyO3/maturin#2900, merged 2025-12-30),
    then tag detection and abi3 cross-compilation fixes (PyO3/maturin#3023, #3130, merged 2026). Sources:
    <https://github.com/PyO3/maturin/pull/2102>, <https://github.com/PyO3/maturin/pull/2900>.
  - Fallback, if a mobile target turns out not to build through maturin: pypackpack calls `cargo build`
    for that target directly and writes the wheel itself, with its own tag rule (*Wheel tags*,
    `bundle/PythonTags.kt`) and the PEP 730 / PEP 738 platform tags `SingleWheelBundler` already writes.
- **ABI and Cargo features (#135).** Three ABI modes are supported via `abi` (in `[tool.ppp.rust]` at
  package level or per crate entry):
  - `abi3t-py315` (shorthand `abi3t`, the default): PEP 803 free-threaded stable ABI, passing
    `pyo3/abi3t-py315` (`pyo3/abi3t-py3<minor>` for `minor >= 15`).
  - `abi3-py3<minor>` (for example `abi3-py313`): PEP 384 stable ABI, passing `pyo3/abi3-py3<minor>` (`minor >= 7`).
  - `cp3<minor>[t]` (for example `cp315t`, `cp315`, or aliases `version` / `cpython` defaulting to `cp315t`):
    version-pinned CPython ABI with no `pyo3/abi3*` feature flag.
  Extra Cargo features (`features = [...]`) and disabling Cargo's default features (`default-features = false`
  or `no-default-features = true`) can be set at package level (inherited by both convention crates and
  `[tool.ppp.rust]` entries) or per crate in an inline table.
- **Targets, in order:** the host desktop target, then `aarch64-linux-android`, then iOS (`aarch64-apple-ios`,
  `aarch64-apple-ios-sim`, `x86_64-apple-ios`), the order the compile slot (#60) uses. Then the remaining
  Rust triples: `aarch64-apple-darwin`, `x86_64-apple-darwin`, `x86_64-unknown-linux-gnu`,
  `aarch64-unknown-linux-gnu`, `x86_64-pc-windows-msvc`, `x86_64-linux-android`.
- **Toolchains: pypackpack acquires or locates them**, never `tcl` or `toolchain`. Rust targets with
  `rustup target add`; no `rustup` is a refusal that names it. The Android NDK from `ANDROID_NDK_HOME`, then
  `$ANDROID_HOME/ndk/<version>`, as the compile slot does. Apple: the Xcode command-line tools.
- **Separate from the compile slot (#60):** a crate is the input (not generated C) and a wheel into crossenv
  is the path (not `ExtensionCompilerInterface`). Shared: the target triples, `PythonAbi`, the ABI-tag rule
  and the Android extraction constraint (#60 §7).
- **Declaring a crate.** By convention, a directory holding a `Cargo.toml` inside a Python package is that
  package's extension module, named after the directory: `src/main/core/_fast/Cargo.toml` builds
  `core._fast` (scanning `[tool.ppp].python-dir`, `src/main`, `src`, `python`, `<package>/python`, and the
  package root in order). This is the rule Meson's scan uses, and it needs no `toolchain` DSL. For a crate outside
  the Python packages, a `[tool.ppp.rust]` table in the package's `pyproject.toml` maps a module name to a
  crate directory, relative to the package, either as a path string or as an inline table specifying `path`
  and optional `abi`, `features`, and `default-features` / `no-default-features` overrides:

  ```toml
  [tool.ppp.rust]
  abi = "abi3-py313"
  features = ["shared"]
  "torch._C" = "rust/torch_c"
  "torch._exec" = { path = "rust/torch_exec", abi = "cp315t", features = ["executorch"], default-features = false }
  ```

  Package-level `abi`, `features`, and `default-features` / `no-default-features` keys in `[tool.ppp.rust]`
  apply as defaults to both convention crates and `[tool.ppp.rust]` entries.
  The convention comes first; the table is for the exceptions. Crates that are neither inside a Python
  package nor named in the table are not built (torchnative's `rust/vulkan_probe` and `rust/wasm_probe`
  stay out). Naming the same module both ways is an error.

#### Build Level

- `instant`: Bundles the Python source almost as-is.
  - Status: implemented. For bundle types `resource`, `single`, `fat` and `patch`
    (`packpack/.../bundle/resource/ResourceBundlerTest.kt`,
    `packpack/.../bundle/single/SingleWheelBundlerTest.kt`, `packpack/.../bundle/fat/FatWheelBundlerTest.kt`,
    `packpack/.../bundle/patch/WheelPatchBundlerTest.kt`).
- `bytecode`: Converts the Python source to `.pyc` and bundles it.
  - Status: partial. For `resource` (`packpack/.../bundle/resource/ResourceBundlerTest.kt`:
    `bundle_bytecodeLevelKeepsBothPyAndPycForDebugBuildType`,
    `bundle_bytecodeLevelKeepsOnlyPycForReleaseBuildType`, `bundle_bytecodeLevelProducesARealLoadablePycFile`,
    `bundle_bytecodeLevelFailsClearlyWithoutAVenvInterpreter`), every `.py` in the payload is compiled
    with `compileall -b` (sibling `foo.pyc`, not `__pycache__/`) by the interpreter in the nearest
    `<project>/.venv`; `debug` keeps the `.py` beside the `.pyc`, `release` keeps only the `.pyc`.
    `single`, `fat` and `patch` accept `bytecode` and package the compiled output under
    `dist/<canonical target>/<build type>/bytecode`.
- `native`: native code where it exists. The developer's modules become CPython extensions built
  from the C TypedPython generated for them (the compile slot, `docs/design/compile-slot.md`, #19, #20, #60).
  A developer module without C is allowed as an exception: it ships as bytecode, and the build
  warns, naming the module and the reason (`NotNativeModule`: `NOT_MARKED` or `NOTHING_COMPILED`).
  C that fails to compile fails the build (`ModuleCompileException`). Libraries ship their prebuilt
  native wheels.
  - wasm (`wasm32-emscripten`, #82): `compile/backend/external/Emscripten.kt`. An extension is an Emscripten side
    module for python-multiplatform's 3.15t runtime: `emcc` compiles and links with that runtime's ABI flags
    (`-fwasm-exceptions -sSUPPORT_LONGJMP=wasm`, PEP 783 pyemscripten_2026_0's unwinding ABI) and `-pthread`, `-fPIC`, and
    links `-sSIDE_MODULE=1` without libpython (python.wasm provides it when the module loads). `emcc` is
    `$EMSDK/upstream/emscripten/emcc`, else on `PATH`, and must be Emscripten 5.0.3 (`Platforms.EMSDK_VERSION`), the SDK the
    runtime is built with; otherwise the target is refused naming both versions. The headers come from the installed
    runtime (`wasm32_emscripten/include/python3.15t`). `ExtensionCompilerTest.compile_wasmBuildsAnEmscriptenSideModuleWithTheRuntimesAbiFlags`,
    `checkSupport_wasmNeedsEmccAndTheRuntimesEmscriptenVersion`; the real build `EmscriptenRealBuildTest`
    (`PPP_WASM_REAL_BUILD=1` with `EMSDK` and `PPP_WASM_RUNTIME_MANIFEST`; off in CI until python-multiplatform publishes
    the runtime, team lead 2026-11-27): a `PyInit_` module is a wasm side module (`dylink.0`) and bundles as
    `cp315-cp315t-pyemscripten_2026_0_wasm32`; its `setjmp`/`longjmp` imports the runtime's `__c_longjmp` tag, and a C++
    module (`.cpp`, the same adapter) that throws and catches a `std::runtime_error` imports `__cpp_exception`, the two
    tags the runtime exports (`aCppExtensionUsesTheRuntimesCppExceptionTag`). Run locally (2026-11-27, Emscripten 5.0.3,
    runtime from python-multiplatform e5c4a058): both passed; the C wheel is byte-identical across rebuilds (sha256
    df57a28e...). Both wheels loaded in that runtime (its build tree's python.sh): the C module returned 42 and 7 through
    `longjmp`, the C++ module caught 'boom 3'. They are python-multiplatform's `WasmCompiledWheelTest` fixtures (team lead
    2026-11-27: checked in there, rebuilt from this test with `PPP_WASM_WHEEL_OUT` when Emscripten or the ABI changes).
  - Status: partial. Implemented for bundle type `resource` on macOS (`aarch64-apple-darwin`,
    `x86_64-apple-darwin` via `Clang.kt`), Linux host architecture (`x86_64-unknown-linux-gnu`,
    `aarch64-unknown-linux-gnu` via `Clang.kt`), and Android `aarch64-linux-android` (via `NDK.kt`)
    (`packpack/.../compile/extension/ExtensionCompilerTest.kt`,
    `packpack/.../bundle/resource/ResourceBundlerTest.kt`). Since #155 the slot also builds (command and
    support tests in `ExtensionCompilerTest`: `checkSupport_acceptsWindowsOnItsOwnMachineIosWithItsFrameworkAndX86Android`,
    `compile_buildsEachNewTargetWithItsCompilerFlagsAndLink`; real builds, opt-in with `PPP_NATIVE_SLOT_REAL_BUILD=1`,
    in `NativeSlotRealBuildTest` (test.yml `real-builds` for Android, `real-builds-host` on macOS for iOS and on
    Windows, where the `.pyd` is imported); `partial` until those jobs have passed):
    - `x86_64-linux-android` through `NDK.kt`, like arm64;
    - every other Linux target (another architecture, musl, riscv64) with `zig cc -target <triple>` (glibc
      2.17, riscv64 2.27), the ziglang the Cargo backend already uses, run through `uv tool run`;
    - Windows on a machine of the target's architecture only (owner decision 2026-11-19, as for Cargo) with
      LLVM `clang --target=<triple>`, linked `-shared` against `libDir/libraryName` (`python315t.lib`);
    - iOS device and simulators on a macOS host with `xcrun --sdk iphoneos|iphonesimulator clang -target
      <arch>-apple-ios13.0[-simulator]`, linked `-dynamiclib -F <libDir> -framework Python` (`libDir` is the
      XCframework slice that holds `Python.framework`).
    wasm is refused with `NoCompileBackendException` until #82. `single`, `fat` and `patch` accept `native` and package the compiled
    output under `dist/<canonical target>/<build type>/native`.
- `mixed`: bytecode for the developer's code, native code for libraries. The developer's modules ship
  as bytecode and TypedPython's C is not applied; libraries ship their prebuilt native wheels.
  - Status: partial. Implemented for bundle type `resource`
    (`packpack/.../bundle/resource/ResourceBundlerTest.kt`: `bundle_mixedLevelCompilesDeveloperModulesToBytecodeAndKeepsLibraryExtensions`).
    `single`, `fat` and `patch` accept `mixed` and package the compiled output under
    `dist/<canonical target>/<build type>/mixed`.

The two levels' meaning is the user's (2026-10-03), reading `toolchain`'s user-authored
`(플러그인예시)build.gradle.kts`, which is specification (`AGENTS.md` rule 6):
`compileLevel = "native"  // (native code only) or "mixed" (byte code (개발자 코드) + native code (라이브러리))`.

The level is chosen per bundle request (`BundleRequest.buildLevel`) and names the output directory
`<package>/build/packpack/<bundle type>/<build type>/<build level>`. `pypackpack build --level` does
not select a level (see *Package build*).

#### Bundle Type

Bundling is a library API, `BundlerInterface.create(BundleType.<TYPE>).bundle(BundleRequest(...))`
(`bundle/BundlerInterface.kt`), which `pypackpack build <package> source|resource` calls (*Package build*).
There is no `pypackpack bundle` command.

- `binary`: Produces a target-specific binary layout suitable for execution or embedding in an app. The
  sheet: "binary (.exe, etcs)". The layout below is an agent proposal, not confirmed (#156).
  - Inputs: the compiled package (`dist/<canonical target>/<build type>/<build level>`'s `site-packages`, as
    for `single`), the target's installed dependencies from `pypackpack <package> sync`
    (`<package>/build/crossenv/<canonical target>`, when present), and the target's CPython
    (`installPython(<pythonVersion, default 3.15t>, <target>, <installDir>)`, installed once into
    `<package>/build/packpack/runtime/<canonical target>/<version>`).
  - Output, `<package>/build/packpack/binary/<build type>/<build level>/<canonical target>/<name>/`:
    `runtime/` (the CPython tree), `app/` (the package, then its dependencies; a path in both is refused),
    `binary.json` (name, version, target, entry point, interpreter path) and, on desktop targets, a launcher:
    `bin/<name>` (POSIX `sh`) on macOS and Linux, `<name>.cmd` on Windows. The launcher runs the runtime's
    interpreter with `-s` and `PYTHONPATH=app`, from any working directory, passing its arguments through.
    Android, iOS and wasm get no launcher: the layout is for embedding (python-multiplatform loads it).
  - Entry point: the first `[project.scripts]` entry (`module:function`, called and its return value used as
    the exit status), else `python -m <[project].name>`.
  - The archive (`artifactFile`): `<name>-<version>-<canonical target>.tar.gz` (`.zip` for Windows) of that
    directory, deterministic (sorted entries, fixed timestamps), with the launcher's executable bit kept.
  - Refused with a message: no compiled output (naming the `build` command, as `single` does), a runtime
    that cannot be installed for the target, or a runtime without an interpreter.
  - Status: implemented. `packpack/.../bundle/binary/BinaryBundlerTest.kt` (layout, entry point, archive,
    refusals, and running the host launcher).
- `fat`: Produces a wheel-like archive that includes the current package together with its dependencies.
  - Status: implemented. `packpack/.../bundle/fat/FatWheelBundlerTest.kt`.
  - Reads the package's own compiled output from `<package>/dist/<canonical target>/<build type>/<build level>` (its `site-packages`).
  - Vendors workspace-local dependencies by name (matched against `tool.uv.workspace.members` entries) from their own `dist/<canonical target>/<build type>/<build level>`.
  - Vendors third-party dependencies, declared or transitive, from `<package>/build/crossenv/<canonical target>`, where `pypackpack <package> sync --target <target>` installs them via `uv pip install --target --python-platform`. That `sync` must have been run first.
  - Renders full PEP 621 `[project]` metadata into `<dist-info>/METADATA` (`Metadata-Version: 2.4`) and copies declared `license-files` into `<dist-info>/licenses/`.
  - Supports the `instant`, `bytecode`, `mixed` and `native` build levels.
- `single`: Produces a wheel-like archive that includes only the current package.
  - Status: implemented. `packpack/.../bundle/single/SingleWheelBundlerTest.kt`.
  - Reads `<package>/dist/<canonical target>/<build type>/<build level>` (its `site-packages`) and keeps native extension modules.
  - The Android platform tag uses the declared `minSdk`, falling back to API 24, the level every Android extension is linked for (#118), and rejects a `minSdk` below PEP 738's floor (21). The iOS tag is `ios_13_0_*`, except the arm64 simulator, `ios_14_0_arm64_iphonesimulator`: Rust's `aarch64-apple-ios-sim` cannot target lower (#118). The WASM tag is `emscripten_${Platforms.EMSCRIPTEN_VERSION_TAG}_wasm32` (PEP 783/CPython sysconfig spelling, `emscripten_4_0_19_wasm32`, #82). The same rules hold in the `fat` and `patch` bundlers.
  - Renders full PEP 621 `[project]` metadata into `<dist-info>/METADATA` (`Metadata-Version: 2.4`) and copies declared `license-files` into `<dist-info>/licenses/`.
  - Supports the `instant`, `bytecode`, `mixed` and `native` build levels.
- `patch`: Produces a patch archive containing only the changes relative to the existing primary bundle.
  - Status: implemented. `packpack/.../bundle/patch/WheelPatchBundlerTest.kt`.
  - Reads the baseline wheel from `<package>/build/packpack/single/<buildType>/<buildLevel>`.
  - Compares current package files (from `<package>/dist/<canonical target>/<build type>/<build level>`) against baseline entries by path and SHA-256.
  - Records added and modified files in full; removed files in a text manifest (`PATCH-MANIFEST`).
  - Supports the `instant`, `bytecode`, `mixed` and `native` build levels.
  - Patch *version tracking* (base version, patch number) is planned.
- `resource`: Produces a resource layout consumable by python-multiplatform/toolchain.
  - Status: implemented. `packpack/.../bundle/resource/ResourceBundlerTest.kt`.
  - A directory, not an archive: `python/` (the payload, meant to be placed on `sys.path`) beside `resource-manifest.json` (identity, target, per-file SHA-256; sorted, no timestamp).
  - Reads the package *source*, not the compiled `dist/` output: the common source root (`[tool.ppp].python-dir` when configured, `src/main`, `src`, `python`, `<package>/python`, or the package root) first, then `src/<family>` on top, then each `BundleRequest.metaDirs` entry, then each `libDirs` entry; a later stage wins for the same path.
  - Drops `__pycache__/`, `*.pyc`/`*.pyo`/`*.pyd`, dot-entries, `build/`, `dist/` and `node_modules/`. Because it reads source, extension modules that `build` compiles never reach it, unlike the wheel bundlers.
  - Supports the `instant`, `bytecode`, `mixed` and `native` build levels (see *Build Level*). At `native`, `resource-manifest.json` also records `pythonAbi` (`"cp315t"`), `extensionSuffix`, `nativeModules` (`module`, `path`) and `tools` (`cc`).
  - Validates a declared `minSdk` and records it in the manifest. It does not select a CPython build by API level, because no target-aware download exists here.
  - Records `BundleRequest.versionName` / `versionCode` (the app's payload version, from toolchain's `defaultConfig`) as `"versionName"` / `"versionCode"` beside the package's own `"version"`, only when set.
  - It is the one bundle type another repository consumes: `toolchain` calls it, and the payload it writes reaches the desktop jar's root and the APK's `assets/python/`.

##### GIL warning

Status: implemented for `resource`. `packpack/.../bundle/GilCheckTest.kt` (tags, module names, the probe's
process per module, timeouts and failures) and `ResourceBundlerTest.kt`
(`bundle_warnsAboutGilTaggedExtensionsOnAnyTarget`, `bundle_probesHostExtensionsWithTheVenvInterpreterUnlessTurnedOff`).

The Scope decisions' warning for dependencies that run the app with the GIL (`bundle/GilCheck.kt`). It
never fails the bundle; the findings are `BundleResult.warnings`, for the caller to show.

- Every target: an extension whose file name is tagged for a GIL CPython (`cpython-3XY-<platform>`,
  `cp3XY-<platform>`, `abi3`) is named, with the reason that CPython 3.15t will not load it. A
  free-threaded interpreter's import suffixes do not include those tags.
- The host target only: `Py_mod_gil` lives in the module's data, not its name, so each free-threaded
  extension (`cpython-3XYt-`, `cp3XYt-`, `abi3t`) is imported in its own isolated (`-I`) process of the
  project's `.venv` interpreter, with a 30 s timeout, and `sys._is_gil_enabled()` says whether the import
  turned the GIL on. A module that did is named as re-enabling the GIL; a timeout or a failed import is a
  warning that names the module and says it could not be checked. If the `.venv` interpreter is missing or
  is not free-threaded, one warning says how many extensions were not checked.
- The import runs the package's code. `BundleRequest.gilImportProbe = false` turns the probe off; the tag
  check still runs.

Limitation

- Cross targets get the tag check only: their extensions cannot be imported on the host.
- Untagged `name.so` files are neither probed nor warned about.
- `single`, `fat` and `patch` do not run either check yet.

##### Android runtime libraries

Status: implemented. `packpack/.../bundle/AndroidRuntimeLibrariesTest.kt`.

An extension that NDK clang builds with C++ lists `libc++_shared.so` in DT_NEEDED. Neither a wheel nor
the Android CPython carries that library. An app that does not ship it gets whatever copy the device has:
on a Galaxy Z Flip4 the loader picked an old `/vendor/lib64/libc++_shared.so` and failed on a missing
symbol. The NDK's guidance is one shared libc++ per app. The leader approved packaging the NDK's copy per
ABI into the APK's jniLibs on 2026-11-25 (Decision 20 (a), toolchain report). `toolchain` does the
packaging; `bundle/AndroidRuntimeLibraries.kt` says what to package:

- `elfNeededLibraries(file)` returns the DT_NEEDED entries of an ELF64 file in file order, or `null` for
  a file that is not ELF. It reads the program headers, as the loader does, so a fully stripped file
  reads the same.
- `androidRuntimeLibraries(payloadDir, canonicalTarget, ndkRoot)` scans every `.so` under the payload.
  For each library in `NDK_SHIPPED_RUNTIME_LIBRARIES` (only `libc++_shared.so`) that one of them needs and
  that the payload does not carry, it returns the library with:
  - its source: `<ndk>/toolchains/llvm/prebuilt/<host>/sysroot/usr/lib/<triple>/`;
  - its ABI: `arm64-v8a` or `x86_64`;
  - the payload paths that need it.
- If nothing is needed, the result is empty and no NDK is required.
- If something is needed but no NDK is found, or the NDK lacks the file, the result is a failed
  `Result`. The message names the library, the `.so` that needs it and where it looked.
- A non-Android target is refused.
- `androidNdkRoot(environment)` finds the NDK the way the cross build does (`CargoEnvironment.locateNdk`):
  `ANDROID_NDK_HOME`, else the newest `<ANDROID_HOME or ANDROID_SDK_ROOT>/ndk/<version>`. That is how the
  shipped copy matches the NDK that the jit-built wheels were linked against.

Limitation

- Only 64-bit ELF is read, because both Android targets (`aarch64-linux-android`, `x86_64-linux-android`)
  are 64-bit.
- A prebuilt Android wheel linked against an older NDK gets the cross build's NDK copy. libc++ keeps
  backward compatibility, but that copy is not the wheel's own.

#### Code minification

Status: planned, built after 1.0 (owner, 2026-11-21, relayed by the team lead (toolchain report, "Owner decision (2026-11-21 21:55)"): "the minifier becomes its own independent spec
section (to be built later; keep it as planned with its own criterion)"). `compile/middleware/minification/
BaseMinifyInterface.kt` is an empty placeholder.

What it is (agent proposal, not confirmed): a step that rewrites a package's own `.py` modules to a smaller
equivalent (comments, docstrings unless kept, blank lines and redundant whitespace removed; local names
shortened; public names, `__all__` and anything reached through `getattr`/strings kept), selectable per
bundle like the build levels and applied before `bytecode`/`mixed` compile it.

Done when (agent proposal, not confirmed): for a fixture package, the minified modules are smaller than the
originals, a test runs the fixture's own tests against both and gets the same results, and a module that
uses a dynamic attribute name is left with that name intact.

#### Package deployment

```bash
pypackpack deploy <package name> [<deploy type: code (alias source) | resource | weight; default code>] [<bundle type: default binary>] [--type <build type: default debug>] [--level <build level: default instant>] [--target <target name>] [--publish-url <url>] [--dry-run]
```

The package is found as `build` finds it (a workspace member, from the working directory). The
destination follows the artefact (`docs/INTENT.md` §4.4): a library's wheel goes to PyPI, an app's
`binary` or `patch` bundle to FastTrack. `DeployInterface.create(DeployType)` returns `CodeAPI`,
`ResourceAPI` or `WeightAPI`; `CodeAPI` routes by bundle type.

##### Code to PyPI: `deploy <package> code single|fat`

Status: implemented (#90). `packpack/.../deploy/code/PyPIPublishAPITest.kt`, `cli/.../DeployCommandTest.kt`.

- `PyPIPublishAPI` uploads the one wheel in `<package>/build/packpack/<single|fat>/<build type>/<build
  level>`, which `pypackpack build <package> source single|fat` made, with pypackpack's own uv:
  `uv publish [--publish-url <url>] [--dry-run] <wheel>`.
- No token is stored or passed on the command line: uv authenticates itself (`UV_PUBLISH_TOKEN`,
  trusted publishing). `--publish-url` (TestPyPI, for example) and `--dry-run` pass through.
- No wheel: refused, naming the `pypackpack build ... source <bundle> --type ... --level ...` command that
  makes it. More than one wheel: refused, listing them, with that command and `--overwrite`. A failed upload
  fails with uv's output.
- The tests use a fake runner only; nothing uploads. Verified by hand with `--dry-run` against TestPyPI's
  URL (2026-10-17), which uploaded nothing.

##### Not implemented

Status: planned. Each refuses with a "not implemented" message and exit code 1
(`packpack/.../deploy/DeployInterfaceTest.kt`, `PyPIPublishAPITest`, `cli/.../DeployCommandTest.kt`).

- `code binary|patch` → FastTrack (`FastTrackAPI`), for apps: no FastTrack server exists. After 1.0 (owner,
  2026-11-21, relayed by the team lead: "FastTrack deploy must exist, after 1.0").
- `resource` → ResourceHub (`ResourceAPI`, `ResourceHubAPI`) and `weight` → BrainWave (`WeightAPI`,
  `BrainWaveAPI`): no server exists. In 1.0 (*1.0 scope*); blocked on the servers.
- Implement patch feature
  - Let's go with a git-like concept for patch uploads (the concern is speed, parallel processing)
  - Need change upload, a server management page, client code
