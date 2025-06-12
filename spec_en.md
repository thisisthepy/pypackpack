# PyPackPack Spec Sheet

## Feature Overview

- Multi-platform build system for Python

#### Goals to Achieve

No operating system dependencies (Desktop, macOS, Linux) by GraalVM Native Image
Easy installation and usage (don't make users configure Python or JVM paths)
Fast performance (build speed is important)

### <pypackpack> Main Body

- (1). Support for building Python packages written in C, C++, Rust (excluding cases requiring patches)
- (2). Crossenv support for multi-platform code
- (3). Python code compilation/optimization/minification support (nuitka, lpython, etc.)
- (4). Code update fast track API support

### PyPackPack Companion (Tools designed to be used together)

#### <toolchain>

- (1). pypackpack wrapping
- (2). Kotlin library generation including Python code
- (3). Final binary generation functionality
- (4). Extension of pypackpack's code update fast track to allow hot-reload during development phase

#### <toolchain(python)>

- Complete Kotlin multiplatform project with toolchain applied, compressed as a whl package

#### <pip-jit>

- Project that supports packages that fail to build in pypackpack due to requiring patches by inserting recipes to enable building

#### <pip-central> (Closed source project)

- Service that distributes packages using pip-jit

#### <brainWave/intelliPush>

- Management server using pypackpack's code update fast track API and brain wave API

## Directory Structure

### pypackpack Code Directory Structure

```
- pypackpack
  - packpack
    - src/main/kotlin/org/thisisthepy/python/multiplatform/packpack
      - util
        - CommandLine.kt  # CLI endpoint (Main function)
        - Downloader.kt  # external tool downloader (URL downloader, pip downloader)
        - DownloadSpec.kt  # external tool download spec
      - dependency
        - frontend
          - BaseInterface.kt  # factory pattern
          - Cli.kt  # CLI interface
          - Gradle.kt  # Gradle interface
        - middleware
          - BaseInterface.kt  # factory pattern
          - DefaultInterface.kt  # strategy pattern
          - environment
            - DevEnv.kt  # dev/build environment venv management
            - CrossEnv.kt  # cross environment venv management
        - backend
          - external
            - UV.kt  # uv downloader
          - BaseInterface.kt  # factory pattern
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
        - binary (.exe, etc.)
        - fat (.whl)
        - single (.whl, just package except dependent libs)
        - patch (.whl.patch)
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

### User Directory Structure

```
- <project>  # Multi-package project
  - .venv  # venv where dev dependencies for project build are managed
  - <package1>  # Package 1 (one gradle module in toolchain)
    - build  # Build output storage
      - crossenv
        - android_21_arm64   # Android venv
        - android_21_x86_64
        - windows_amd64      # Windows venv
        - macos_arm64
      - packpack
        - binary
          - debug
            - <build level>  # instant(.py), bytecode(.py+.pyc), native, mixed
          - release
            - <build level>  # instant(.py), bytecode(.pyc), native, mixed
    - src  # Source code storage
      - android  # Android-specific code
        - __init__.py
        - ...
      - main  # Platform common code
        - __init__.py
        - ...
      - windows  # Windows-specific code
        - __init__.py
        - ...
      - test  # Test code
        - test_*.py
    - pyproject.lock  # Detailed package settings by environment
    - pyproject.toml  # Package settings (dependency management, etc.)
  - <package2>
    - ...
  - .gitignore
  - LICENSE
  - README.md
  - pyproject.lock
  - pyproject.toml
```

## Feature Specifications

### Basic Features

#### CLI Help (DevEnv.kt)

```bash
pypackpack help
pypackpack --help
```

- Output CLI usage

#### CLI Version (DevEnv.kt)

```bash
pypackpack version
pypackpack --version
pypackpack -v
```

- Output current pypackpack version and detected uv version

#### Project Creation (DevEnv.kt)

```bash
pypackpack init [<project name>] [<python version>]
```

- Create new project folder below (when project name argument is provided) or set current folder path as project root (when project name is empty)
- If pyproject.toml already exists, check if ppp is being used, and if not using ppp, show error that another packaging tool is in use, and if using it, show error that it's already initialized
- Create basic files in project root (pyproject.toml, LICENSE, README.md, .gitignore)
- Check if uv is installed on system, and if not installed, output command to download latest version of uv
- Download specified Python version through uv and create .venv

#### Project Python Version Change (DevEnv.kt)

```bash
pypackpack python use <python version>
```

- Download specified Python version through uv and reset .venv and entire project build history (delete build folders of sub-packages)

#### Additional Project Python Features (DevEnv.kt)

```bash
pypackpack python list
pypackpack python find <python version>
pypackpack python install <python version>
pypackpack python uninstall <python version>
```

- Command forwarding to uv python ~~

### Package Management Features

#### Package Addition (CrossEnv.kt)

```bash
pypackpack package add <package name>
pypackpack package remove <package name>
```

- Create new package with specified name in project root (init, python, package, target, add, remove, sync, tree, build, bundle, deploy cannot be used as package names -- validation needed)
- Record new package in project root pyproject.toml and create package internal pyproject.toml
- - Configure build settings in project root pyproject.toml to be installable as pip install git+<https://github.com/?/??.git['package> name'] format
- Create src folder inside package, create main folder in src folder, create **init**.py file in main folder
- Create test folder inside src folder in package, create .gitkeep file inside it
- Create build folder inside package, create crossenv folder in build folder, create venv matching current running host platform in crossenv folder

### Package Build Target Management Features

#### Build Target Platform Addition/Removal (CrossEnv.kt)

```bash
pypackpack target add <target name> [<package name>]
pypackpack target remove <target name> [<package name>]
```

- Create target venv for the package and add source directory for the target
- Reinstall all dependency packages for new target and proceed with lock file sync
- Lock file synchronization proceeds by performing uv export from each platform's lock, then generating platform-specific pylock.toml and executing uv pip sync pylock.toml --python-platform <platform name> targeting each platform's venv

### Dependency Management Features

#### Dev Environment Dependency Management (DevEnv.kt)

```bash
pypackpack add <pypi name> [<etc>]
```

- Add dependency to package root pyproject.toml according to user request, then install that dependency in venv
- Execute pip install through uv, with uv running inside .venv to generate uv.lock, and after uv execution completes, copy that file and paste into project root pyproject.lock
- - Before uv execution, also paste pyproject.lock into pyproject.lock inside .venv

```bash
pypackpack remove <pypi name> [<etc>]
```

- Operate opposite to add

```bash
pypackpack sync [<etc>]
```

```bash
pypackpack tree [<etc>]
```

#### Per-Package, Per-Target Build Platform Dependency Management (CrossEnv.kt)

```bash
pypackpack <package name> add <pypi name> [--target <target name>] [<etc>]
```

```bash
# example
pypackpack mypackage add numpy --target windows linux
pypackpack mypackage add numpy --target windows linux --extra-index-url https://pypi.org/simple
```

- Add dependency to specified package
- If target name argument is provided, add to that target; if argument is empty, add to all targets
- Record dependency in package internal pyproject.toml and install dependency in target's venv in crossenv folder in package internal build folder

```bash
pypackpack <package name> remove <pypi name> [--target <target name>] [<etc>]
```

```bash
pypackpack <package name> sync [--target <target name>] [<etc>]
```

```bash
pypackpack <package name> tree [--target <target name>] [<etc>]
```

### Build, Bundling, Deployment

#### Package Build

```bash
pypackpack build <package name> source [<bundle type: default binary>] [--type <build type: default debug>] [--level <build level: default instant>] [--target <target name>] [<etc>]
pypackpack build <package name> resource [--target <target name>] [<etc>]
```

- Need to describe scope of dependency packages to build in package's pyproject.toml (by default, build everything at the same level)
- Build levels (instant, bytecode, native, mixed / debug, release)
- Need to describe whether to bundle everything into single file or build separately (metadata)
- Need to support bundle compression (.whl) + update only changed parts (.whl.patch)
  - Need to consider which primary version to update from and which patch number it is

#### Package Deployment

```bash
pypackpack deploy <package name> source [<bundle type: default binary>] [--type <build type: default debug>] [--level <build level: default instant>] [--target <target name>] [<etc>]
pypackpack deploy <package name> resource [--target <target name>] [<etc>]
```

- Need to specify deployment server target (PyPI or FastTrack)
- Implement patch functionality
  - Let's go with git concept for patch upload (issue is speed, parallel processing)
  - Change upload, server management page needed, client code
