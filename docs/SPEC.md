# PyPackPack Spec Sheet

## Feature Overview

- A multi-platform build system for Python

#### Goals to achieve

No OS dependency (Desktop, macOS, linux) via GraalVM Native Image
Must be easy to install and use (let's not make users configure paths for Python, JVM, etc.)
Must be fast (build speed matters)

### <pypackpack> core

- (1). Support building Python packages written in C, C++, Rust (except when patches are required)
- (2). Support crossenv for multi-platform code
- (3). Support Python code compilation/optimization/minification (nuitka, lpython, etc.)
- (4). Support code update fast track API

### PyPackPack Companion (tools designed to be used together)

#### <toolchain>

- (1). pypackpack wrapping
- (2). Generate a Kotlin library that includes Python code
- (3). Final binary generation feature
- (4). Extend pypackpack's code update fast track to also allow hot-reload during the development stage

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

```
- pypackpack
  - packpack
    - src/main/kotlin/org/thisisthepy/python/multiplatform/packpack
      - cli
        - Command.kt               # main entry point, root command, dynamic package command dispatch
        - CommandExtension.kt      # CLI helper, validation, progress display
        - DependencyCommand.kt     # handles root-level 'add', 'remove', 'sync', 'tree'
        - DynamicPackageCommand.kt # handles '<package> add/remove/sync/tree'
        - PackageCommand.kt        # handles 'package add/remove/sync/tree'
        - ProjectCommand.kt        # handles 'version', 'init'
        - PythonCommand.kt         # handles 'python' related commands
        - TargetCommand.kt         # handles 'target' related commands
        - BuildCommand.kt          # handles 'build' command
        - DeployCommand.kt         # placeholder for 'deploy' command (empty, not registered yet)
      - utils
        - Platforms.kt
        - Downloader.kt  # external tool downloader (URL downloader, pip downloader); also defines DownloadSpec
        - Archive.kt  # zip/tar.gz/tar.zst archive extraction
        - Workspace.kt  # project/workspace root discovery, workspace member listing
        - toml
          - TomlEditor.kt  # style-preserving TOML editor (tables/arrays/values)
          - TomlValue.kt   # TOML value type hierarchy
      - dependency
        - frontend
          - BaseInterface.kt  # factory pattern
          - Cli.kt  # CLI interface
          - Gradle.kt  # Gradle interface
        - middleware
          - BaseInterface.kt  # factory pattern
          - DefaultInterface.kt  # strategy pattern
          - environment
            - DevEnv.kt  # manages dev/build environment venv
            - CrossEnv.kt  # manages cross environment venv
        - backend
          - external
            - UV.kt  # uv downloader
          - BaseInterface.kt  # factory pattern
          - DefaultInterface.kt  # shared Python-version install/list/find/uninstall logic
          - UVInterface.kt
      - compile
        - frontend
          - BaseInterface.kt  # factory pattern
          - Cli.kt
          - Gradle.kt
        - middleware
          - external
            - Nuitka.kt  # nuitka downloader (c++ converter)
            - Cython.kt  # cython downloader (c converter)
            - Lpython.kt  # lpython downloader (llvm converter)
          - BaseInterface.kt  # factory pattern
          - DefaultInterface.kt  # decorator pattern
          - transcompile
            - BaseTransInterface.kt  # strategy pattern
            - NuitkaTransInterface.kt
            - CythonTransInterface.kt
          - minification
            - BaseMinifyInterface.kt  # strategy pattern
            - ...
        - backend
          - external
            - Clang.kt
            - MSVC.kt
            - NDK.kt  # adapter pattern (Clang.kt)
            - XCode.kt  # adapter pattern (Clang.kt)
            - Emscripten.kt  # adapter pattern (Clang.kt or MSVC.kt)
            - Cargo.kt  # adapter pattern (Clang.kt, MSVC.kt, NDK.kt, XCode.kt)
            - Meson.kt  # adapter pattern (Clang.kt, MSVC.kt, NDK.kt, XCode.kt)
          - BaseInterface.kt  # factory pattern
          - DefaultInterface.kt  # strategy pattern
      - bundle
        - BaseInterface.kt  # factory pattern
        - DefaultInterface.kt  # strategy pattern
        - binary
          - BinaryBundler.kt  # .exe, etcs
        - fat
          - FatWheelBundler.kt  # .whl
        - single
          - SingleWheelBundler.kt  # .whl, just package except dependent libs
        - patch
          - WheelPatchBundler.kt  # .whl.patch
      - deploy
        - BaseInterface.kt  # factory pattern
        - DefaultInterface.kt  # decorator pattern
        - resource
          - BaseAPI.kt
          - ResourceHubAPI.kt  # deploy client
        - code
          - BaseAPI.kt
          - PyPIPublishAPI.kt  (uv publish)
          - FastTrackAPI.kt  # deploy client
        - weight
          - BaseAPI.kt  # factory pattern
          - BrainWaveAPI.kt  # deploy client

  - usage-example

```

### User directory structure

```
- <project>  # multi-package project
  - .venv  # venv where dev dependencies for building the project are managed
  - <package1>  # package 1 (a single gradle module in the toolchain)
    - build  # stores build artifacts
      - crossenv
        - android_21_arm64   # venv for android
        - android_21_x86_64
        - windows_amd64      # venv for windows
        - macos_arm64
      - packpack
        - binary
          - debug
            - <build level>  # instant(.py), bytecode(.py+.pyc), native, mixed
          - release
            - <build level>  # instant(.py), bytecode(.pyc), native, mixed
    - src  # stores source code
      - android  # android-specific code
        - __init__.py
        - ...
      - main  # platform-common code
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

### Criteria for interpreting the dependency spec

- Items related to dependency in this document are classified into the following three states.
- `Implemented`: Items whose behavior is currently confirmed in `packpack/src/main/kotlin/.../dependency` and the connected CLI
- `Partially implemented`: Items where the core flow exists, but there are constraints or omissions relative to the documented goal
- `Not yet implemented (target)`: Not yet reflected in code, but a direction we want to maintain
- In dependency-related sections, the `current implementation` is described as the baseline fact, and future plans are separated out.
- The same three-state convention is reused for the Build, bundling, deployment section below.

### Basic features

#### CLI Help (`Implemented`)

```bash
pypackpack --help
pypackpack -h
```

- Prints CLI usage

Limitation

- A bare `pypackpack help` (no dashes) is not a registered subcommand and fails with `no such subcommand help`; only the `--help`/`-h` eager options are wired up.

#### CLI Version (`Implemented`)

```bash
pypackpack version
pypackpack --version
pypackpack -v
```

- Prints the currently used pypackpack version and the detected uv version
- If uv is not detected, installs uv internally under .pypackpack and uses it

#### Project creation (`Partially implemented`)

```bash
pypackpack init [<path>] [--python <python version>] [--name <project name>] [--package]
```

- Per spec, project initialization is performed based on `uv init --bare`.
- `path`, `--python`, `--name`, `--package` are passed as `uv init` options, but `uv` only generates `pyproject.toml`, and ppp generates the other necessary files.
- If `targets` is given, updates `[tool.ppp.dependencies].platforms` in the root `pyproject.toml`.
- The current implementation initializes with `uv init --bare`, after which ppp additionally generates `.gitignore`, `README.md`, and `LICENSE`. `.python-version` is only generated when `--python` is passed.

Limitation

- There is not yet a check for whether an existing `pyproject.toml` is a ppp project, nor a determination of conflicts with other packaging tools.
- The `init` CLI command does not expose a way to pass `targets`, so `[tool.ppp.dependencies].platforms` cannot be set from `pypackpack init` today even though the middleware supports it; targets must be added afterward via `target add`.

#### Changing the project's Python version (`Partially implemented`)

```bash
pypackpack python use <python version>
```

- Deletes `.venv` at the project root, recreates it with the new Python version, and performs `uv sync`.

Limitation

- There is not yet cleanup logic for subpackage `build` directories.

#### Additional project Python-related features (`Partially implemented`)

```bash
pypackpack python list
pypackpack python find <python version>
pypackpack python install <python version> [<target platform>]
pypackpack python uninstall <python version>
```

- These commands do not forward to `uv python`; they manage a separate, ppp-specific Python distribution instead.
- `list`/`find`/`uninstall` look for installs under `~/.pypackpack/python/<version>`.
- `install` downloads a prebuilt CPython distribution from `thisisthepy/python-multiplatform`'s GitHub release binaries for the given (or host) target platform, installing it to `<project>/.venv` (host target) or `<project>/<target-dir-name>` (cross target, e.g. `windows_amd64`).

Limitation

- `install` only accepts Python `3.13`; any other version is rejected ("Only Python 3.13 is supported due to python-multiplatform limitations").
- `install`'s download destination (`<project>/.venv` or `<project>/<target-dir-name>`) does not match where `list`/`find`/`uninstall` look (`~/.pypackpack/python/<version>`), so a version downloaded via `install` is not visible to `list`/`find`/`uninstall`.
- Final placement after download/extraction is incomplete (marked `TODO` in code).

### Package management features

#### Adding/removing packages (`Partially implemented`)

```bash
pypackpack package add <package path> [--path <workspace root>]
pypackpack package remove <package path> [--path <workspace root>]
```

- `<package path>` allows a relative path based on the workspace root. Example: `packages/core`
- The package path cannot go outside the workspace.
- Per spec, `package add` initialization creates a directory at the specified path, then in that directory runs `uv init --bare --package --name <leaf dir name>` to generate only `pyproject.toml`, and ppp generates the other necessary files.
- `package remove` recursively deletes the package directory and removes the corresponding relative path from `[tool.uv.workspace].members` in the root `pyproject.toml`.
- `<package path>` is resolved as a literal path relative to the workspace root, not by matching workspace member names — a bare leaf name only works when the package actually sits directly under the workspace root (e.g. `package remove core` fails for a package registered at `packages/core`; the full relative path must be used there instead).
- The current implementation, after `uv init --bare`, generates scaffolding for the package's `README.md`, `src/main/__init__.py`, `src/test/test_import.py`, `build/crossenv`, `build/packpack`, and also automatically registers it as a workspace member.
- The `package` command additionally exposes `sync` and `tree` subcommands for per-package dependency operations — see Per-package target dependency management.

Limitation

- There is not yet a reserved-word check for package names, nor additional consistency validation on remove.

### Package build target management features

#### Viewing/adding/removing build target platforms (`Implemented`)

```bash
pypackpack target list
pypackpack target add <target name>...
pypackpack target remove <target name>...
pypackpack <package> target add <target name>...
pypackpack <package> target remove <target name>...
```

- `target list` groups `Platforms.SUPPORTED_TARGETS` by alias and platform family and prints them.
- `target add/remove` modifies the `[tool.ppp.dependencies].platforms` array in the `pyproject.toml` of the current directory.
- `pypackpack <package> target add/remove` finds the workspace member package by package name or path and adds/removes the target for that package.
- Targets are normalized via `Platforms.normalizeTargetsOrThrow`.

### Dependency management features

#### Policy notes

> - `pypackpack add/remove` is DevEnv-only and operates without a `packageName`
> - `pypackpack <package> add/remove/sync/tree` is CrossEnv-only and requires a `packageName`
> - CrossEnv target dependencies are normalized based on `Platforms.kt`, then managed via `uv add` or `pyproject.toml` editing using a per-target marker
> - Input that directly includes a marker in the dependency string (e.g. `numpy; ...`) is prohibited; only `--target` is allowed
> - `remove --target` does not remove the whole package, only the specified target scope
> - The current implementation's CLI `--target` input uses a space-separated format (e.g. `--target windows linux`)
> - `tree` is an inspection-only action that shows the resolution result based on the host target or a specified target
> - Creating/installing a dedicated venv per target is not yet within the scope of the dependency implementation

#### Dev environment dependency management (`Partially implemented`)

```bash
pypackpack add <pypi name>...
```

- Finds the project root, then runs `uv add` in the root working directory.

Limitation

- None of the root-level dependency commands (`add`, `remove`, `sync`, `tree`) expose pass-through flags — each command's `run()` hardcodes `extraArgs = null`. Earlier drafts of this spec documented `--dev`, `--editable`, `--no-sync`, `--upgrade`, `--reinstall`, `--refresh`, `--frozen`, `--locked`, `--preview`, `--raw-sources`, `--quiet`, `--verbose` on `add`; none of these are wired up today (`pypackpack add requests --dev` fails with `no such option --dev`). The backend (`UVInterface`) still accepts an arbitrary `extraArgs` map and forwards each entry as a generic `--key [value]` flag to `uv`, so this is a CLI-layer gap rather than a backend limitation.

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

- The actual backend call operates based on the working directory, and the `venvPath` argument is currently not passed as an argument to the `uv sync` command.

```bash
pypackpack tree [--target <target1> <target2> ...]
```

- When there is no package name, calls `uv tree --python-platform <target>` for each of the host target or the specified targets.
- If no target is specified, uses the single target corresponding to the current host as the default.

#### Per-package target dependency management (`Partially implemented`)

```bash
pypackpack <package name> add <pypi name> [--target <target1> <target2> ...]
```

```bash
# example
pypackpack mypackage add numpy --target windows linux
```

- The dynamic package command `pypackpack <package> add/remove/sync/tree` is supported.
- `pypackpack package sync <name> [--target ...]` and `pypackpack package tree <name> [--target ...]` invoke the same logic as an explicit alternative to the dynamic `sync`/`tree` forms.
- The target package is resolved among workspace members by name or relative path.
- `add` computes a marker for each target and repeatedly calls `uv add --package <name> --marker <marker>`.
- If `--target` is absent, uses the `[tool.ppp.dependencies].platforms` value from the package's `pyproject.toml` as the default target.
- If there is no default target and `--target` is also empty, raises an error.

Limitation

- Like the root-level commands, none of `add`/`remove`/`sync`/`tree` expose pass-through flags today — e.g. `pypackpack mypackage add numpy --target windows linux --extra-index-url https://pypi.org/simple` fails with `no such option --extra-index-url`, even though earlier drafts of this spec showed it as a working example.

```bash
pypackpack <package name> remove <pypi name> [--target <target1> <target2> ...]
```

- `remove` directly edits `project.dependencies` in the package's `pyproject.toml`, removing only entries where both the requested package name and the target marker match.
- After removal, performs `uv lock` at the workspace root.

Limitation

- Handled via TOML editing rather than calling `uv remove --marker`.
- The marker recomputed for matching (`platform_system` + `platform_machine`, via the internal `MarkerPolicy`) does not always match the marker text `uv add` actually persists in `pyproject.toml` (observed as `platform_machine` + `sys_platform` instead), so `remove --target` can fail with "No matching target-scoped dependencies found to remove" even for a dependency that was added with that same target (tracked in `docs/KNOWN_ISSUES.md`).

```bash
pypackpack <package name> sync [--target <target1> <target2> ...]
```

- `sync` first calls `uv sync --package <name>`, then calls `uv tree --package <name> --python-platform <target>` for each target to verify it can be resolved.
- If `--target` is absent, uses a single host target as the default.

Limitation

- Does not create a per-target virtual environment.

```bash
pypackpack <package name> tree [--target <target1> <target2> ...]
```

- `tree` calls `uv tree --package <name> --python-platform <target>` for each target and prints the results in sequence.
- If `--target` is absent, uses a single host target as the default.

#### Not yet implemented (target)

- Creating and maintaining a dedicated venv per target for CrossEnv dependencies
- A feature to preserve per-target install results in separate environments during the `sync` step
- Cleaning up subpackage build directories on `python use`

### Build, bundling, deployment

ppp distinguishes four stages: `build`, `compile`, `bundle`, `deploy`.

- `compile`: Converts source into an executable intermediate artifact.
- `bundle`: Packages the compile artifact together with resource/dependency/runtime metadata into a single deployment unit.
- `build`: The upper-level orchestration stage that coordinates dependency verification, compile, and bundle.
- `deploy`: Uploads the bundle artifact to external targets such as PyPI, FastTrack, ResourceHub, etc.

Of these four stages, the `pypackpack build` CLI command currently only drives `compile` (via Meson); it does not perform dependency verification or invoke `bundle`, and there is no CLI entry point for `deploy` yet.

#### Package build (`Partially implemented`)

```bash
pypackpack build <package name> [--type <build type: default debug>] [--level <build level: default instant>] [--target <target name>] [--overwrite]
```

- Auto-generates a `meson.build` for the package by scanning `src/main` (falling back to `src`, then the package root) for Python packages: `.c`/`.cc`/`.cpp`/`.cxx` files become Meson `py.extension_module()` targets and `.py`/`.pyi`/`py.typed` files become `py.install_sources()`.
- Runs `meson setup` / `meson compile` / `meson install` for the package by shelling out to the `meson` CLI directly.
- `--overwrite` regenerates `meson.build` (erroring otherwise if one already exists) and clears the build directory first.
- There is no `source`/`resource` bundle-type subcommand yet; `pypackpack build <package name> resource` does not exist.

Limitation

- `--type`, `--level`, and `--target` are accepted as CLI options but are not yet forwarded into the compile step; the build output path is currently hardcoded to `<package>/build/packpack/single/debug`, installed into `<package>/dist`.
- `meson`/`ninja` are not auto-installed: `Meson.installMeson()` (which runs `uv tool install meson`/`ninja`) and `isMesonInstalled()` exist but are never called before `setup`/`compile`/`install`, so `meson` must already be on `PATH` or the build fails.
- Only the Meson backend is implemented; the Clang/MSVC/NDK/XCode/Emscripten/Cargo backend adapters and the Nuitka/Cython/Lpython compilers and minification middleware are all empty placeholder files, so only C/C++ extension compilation works today (no Rust, no pure-Python compilation/optimization/minification).
- `build` does not invoke the `bundle` stage automatically; it produces compiled/installed files under `dist/`, not a `.whl`.

Not yet implemented (target)

- Describing, in the package's `pyproject.toml`, the scope of dependency packages that need to be built (today everything under the package is built at the same level).
- Selecting whether to bundle everything into a single file or build separately, as metadata.
- Bundle compression (`.whl`) plus incrementally updating only the changed parts (`.whl.patch`), including tracking which primary version a patch is based on and its patch number.

#### Build Level (`Not yet implemented (target)`)

- `instant`: Bundles the Python source almost as-is.
- `bytecode`: Converts the Python source to `.pyc` and bundles it.
- `native`: Converts Python/native source into a native artifact.
- `mixed`: Converts only some modules to native, keeping the rest as source or bytecode.

- These are design targets; the current `build` command does not yet select between them (the `--level` CLI option is accepted but ignored).

#### Bundle Type (`Not yet implemented (target)`)

- `binary`: Produces a target-specific binary layout suitable for execution or embedding in an app.
- `fat`: Produces a wheel-like archive that includes the current package together with its dependencies.
- `single`: Produces a wheel-like archive that includes only the current package.
- `patch`: Produces a patch archive containing only the changes relative to the existing primary bundle.
- `resource`: Produces a resource layout consumable by python-multiplatform/toolchain.

- `bundle/binary/BinaryBundler.kt`, `bundle/fat/FatWheelBundler.kt`, `bundle/single/SingleWheelBundler.kt`, and `bundle/patch/WheelPatchBundler.kt` exist as empty placeholder classes; none are called from any command yet.

#### Package deployment (`Not yet implemented (target)`)

```bash
pypackpack deploy <package name> source [<bundle type: default binary>] [--type <build type: default debug>] [--level <build level: default instant>] [--target <target name>] [<etcs>]
pypackpack deploy <package name> resource [--target <target name>] [<etcs>]
```

- `DeployCommand` is an empty placeholder class and is not registered as a CLI subcommand, so `pypackpack deploy` does not run at all today.
- The `deploy` backend (`BaseInterface`/`DefaultInterface` and the `code`/`resource`/`weight` `BaseAPI`/`PyPIPublishAPI`/`FastTrackAPI`/`ResourceHubAPI`/`BrainWaveAPI` classes) are all empty placeholder files.
- Need to specify the target deploy server (PyPI or FastTrack)
- Implement patch feature
  - Let's go with a git-like concept for patch uploads (the concern is speed, parallel processing)
  - Need change upload, a server management page, client code
