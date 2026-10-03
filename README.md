English | [한국어](https://github.com/thisisthepy/pypackpack/blob/develop/docs/locale/README_ko.md)

<div align="center">

# pypackpack

**Build a Python project once. Ship it to every platform.**

[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-0f9d76.svg)](https://github.com/thisisthepy/pypackpack/blob/develop/LICENSE)
[![PyPI](https://img.shields.io/pypi/v/pypackpack.svg?color=0f9d76)](https://pypi.org/project/pypackpack/)
![Targets](https://img.shields.io/badge/targets-Android%20%7C%20iOS%20%7C%20Desktop-0f9d76.svg)
![Status](https://img.shields.io/badge/status-alpha-orange.svg)

[Guide](https://thisisthepy.github.io/pypackpack/) · [Getting started](https://thisisthepy.github.io/pypackpack/getting-started.html) · [Status](https://thisisthepy.github.io/pypackpack/status.html)

</div>

---

## 💡 Why

A Python project that has to run on a phone as well as a desktop needs more than installing packages:
dependencies resolved for a platform that is not the one you build on, extension modules compiled
for it, and the result packaged so an app can carry it. `pypackpack` (`ppp`) does that work, so you
never configure a Python, a JVM or a cross-compiler by hand.

```
pypackpack = crossenv + compiler + bundler + codepush
```

It delegates instead of reimplementing: dependency resolution and installation are
[`uv`](https://github.com/astral-sh/uv)'s, and pypackpack adds targets, per-target markers and
per-target install directories on top.

## ✨ Features

- 🎯 **Targets as triples.** Android, iOS, macOS, Linux and Windows, by canonical target triple
  (`aarch64-linux-android`, `arm64-apple-ios`, …) or a few aliases.
- 📦 **Dependencies per target.** `ppp <package> add numpy --target aarch64-linux-android` writes the
  marker uv evaluates for that target, and `sync` installs each target's wheels into
  `build/crossenv/<triple>/`.
- 🐍 **Pinned interpreters.** `ppp python install 3.14 <target>` fetches a CPython build for the
  target and checks its SHA-256 before unpacking anything.
- 🛠️ **C/C++ extensions.** `ppp build` generates a `meson.build` and compiles them.
- 🧳 **Bundles.** The `resource` payload that
  [python-multiplatform](https://github.com/thisisthepy/python-multiplatform) runs, plus single, fat
  and patch wheels.
- ☕ **One codebase, two front doors.** The `pypackpack` command line, and the same work as a JVM
  library that [toolchain](https://github.com/thisisthepy/toolchain) (the Gradle plugin) calls.

## 🚀 Install

```shell
uv tool install pypackpack    # puts pypackpack and ppp on PATH
pypackpack --help             # alias: ppp
uvx pypackpack --help         # or run it without installing
```

The wheel carries the command line as a native binary (GraalVM Native Image), one wheel per
platform: macOS 11+ on Apple silicon, Linux x86_64 and aarch64 (glibc), Windows x86_64. On any other
platform the installer finds no matching wheel. There is deliberately no source distribution:
building needs a JDK and GraalVM, and an sdist would install the launcher without the binary.

`uv` is needed at run time. When none is on `PATH`, pypackpack downloads one into
`~/.pypackpack/uv`.

<details>
<summary>Build from a checkout instead</summary>

```shell
git clone -b develop https://github.com/thisisthepy/pypackpack
cd pypackpack
./gradlew :cli:installDist                 # JDK 21
alias ppp="$PWD/cli/build/install/cli/bin/cli"
./gradlew :cli:nativeCompile               # the native binary; needs GraalVM as JAVA_HOME
```

</details>

## ⚡ Quick start

```shell
ppp init myapp --python 3.13
cd myapp
ppp package add packages/core
ppp target add aarch64-linux-android arm64-apple-ios

ppp core add requests                                     # every target of the package
ppp core add six --target arm64-apple-ios                 # one target only
ppp core sync --target aarch64-linux-android arm64-apple-ios \
    --python-version 3.14 --only-binary :all:          # → packages/core/build/crossenv/<triple>/

ppp build core --target aarch64-apple-darwin              # Meson; host only for now
```

Without `--target`, `sync` and `tree` use the host's target only. Put your modules in a package directory such as `packages/core/src/main/core/`; platform-specific
code goes in `src/android/`, `src/ios/` and so on.

## ☕ As a library

```kotlin
repositories { mavenLocal() }    // ./gradlew :packpack:publishToMavenLocal
dependencies { implementation("org.thisisthepy.python.multiplatform:packpack:0.1.0") }
```

```kotlin
BundlerInterface.create(BundleType.RESOURCE).bundle(
    BundleRequest(packageDir = packageDir, target = "aarch64-linux-android", buildLevel = "bytecode")
)
```

The [library page](https://thisisthepy.github.io/pypackpack/guide-library.html) of the guide covers
bundling, Python installs into a directory you choose, and per-target dependency installs.

## 🧭 Where it fits

| Repository | Owns |
|---|---|
| **pypackpack** | The work: acquiring Python, resolving dependencies, cross-compiling, bundling |
| [toolchain](https://github.com/thisisthepy/toolchain) | The Gradle `python { }` block that turns into pypackpack calls |
| [python-multiplatform](https://github.com/thisisthepy/python-multiplatform) | The runtime: CPython embedded in Kotlin Multiplatform, which loads the `resource` bundle |

## 📊 Status

An honest summary. The [status page](https://thisisthepy.github.io/pypackpack/status.html) has the
item-by-item contract.

| Area | State |
|---|---|
| Python distributions (install, list, find, uninstall; pinned SHA-256) | ✅ implemented |
| Targets and per-target dependencies (`add`, `remove`, `sync`, `tree`) | 🟡 working; part of it has no test yet |
| C/C++ extensions through Meson | 🟡 host only, no cross-compiling yet |
| Bundles: `resource`, `single`, `fat`, `patch` | ✅ implemented |
| Build levels: `instant` / `bytecode` | ✅ / 🟡 (`resource` only) |
| Build levels: `native` / `mixed` | ⏳ planned; semantics decided, compile slot in draft |
| `deploy`, code push, `binary` bundle | ⏳ planned |

## 📖 Documentation

- **[Guide](https://thisisthepy.github.io/pypackpack/)**: getting started, targets and dependencies,
  Python distributions, build levels and bundles, the library API. English and 한국어.
- **[한국어 README](https://github.com/thisisthepy/pypackpack/blob/develop/docs/locale/README_ko.md)**

## 🤝 Contributing

Work here runs intent → spec → test → code: a behaviour change starts as a spec change, its test is
written and seen failing, then the code makes it pass. The guide's
[contributing notes](https://thisisthepy.github.io/pypackpack/status.html#contributing) say how to run
the tests.

## 📄 License

[Apache-2.0](https://github.com/thisisthepy/pypackpack/blob/develop/LICENSE) © thisisthepy
