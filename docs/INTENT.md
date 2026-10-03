# Intent

This file says what `pypackpack` is for, and what it deliberately is not. It is the boundary:
`docs/SPEC.md` may not promise anything this file does not cover. A request that falls outside it
is a conversation with the maintainer, not an implementation task.

## Where this comes from

| Source | What it says |
|---|---|
| `README.md` | "A multiplatform solution to distribute python project." `pypackpack = crossenv + compiler(nuitka, pyinstaller) + bundler(js webpack) + codepush(js expo)`. Targets: Android (arm64, x86_64), iOS (arm64), macOS (universal), Linux (x86_64), Windows (x86_64), WASM. |
| `docs/SPEC.md` → "Feature Overview" | The goals (no OS dependency via GraalVM Native Image, easy to install, fast) and the four core items (C/C++/Rust packages, crossenv, compilation/optimization/minification, a code update fast track API). |
| `docs/SPEC.md` → "PyPackPack Companion" | The neighbouring projects and what each takes from pypackpack. |
| `python-multiplatform`'s `docs/design/ecosystem.md` §1 and §5 | The split between the thisisthepy repositories: "ppp owns the work, toolchain owns the Gradle vocabulary". |
| `pyproject.toml` | "PyPackPack (PPP) - A multiplatform solution to distribute python project". |

Anything below that is an inference rather than a statement from those sources is marked
`> Inferred: confirm with the maintainer.`

## 1. What pypackpack is for

**A Python project is built once and distributed to every platform it targets (desktop, mobile
and the web) without the developer configuring Python, a JVM or a cross-compiler by hand.**

The README's formula names the four parts, and the SPEC's core items restate them:

1. **crossenv**: per-target environments, so dependencies are resolved and installed for a
   platform other than the host (SPEC core (2)). Targets are declared per project and per package;
   dependencies are scoped to a target.
2. **compiler**: building packages written in C, C++ and Rust (SPEC core (1)), and compiling,
   optimizing or minifying Python code itself (nuitka, lpython, …; SPEC core (3)), at a selectable
   build level (`instant`, `bytecode`, `native`, `mixed`).
3. **bundler**: packaging the result with its resources, dependencies and runtime metadata into one
   deployment unit (`binary`, `single`, `fat`, `patch`, `resource`).
4. **codepush**: a "code update fast track API" (SPEC core (4)): shipping a changed bundle, or a
   patch against an earlier one, to an app that is already installed.

Around those, the SPEC's goals:

5. **Python acquisition**: the developer does not install or point at a Python; pypackpack fetches
   the distributions it builds against and the tools it drives (`uv`, `meson`).
6. **One self-contained binary**: no OS dependency, via GraalVM Native Image.
7. **Speed**: build speed matters.

And it serves two audiences with the same code:

8. **A command line** (`pypackpack`, alias `ppp`) for Python developers.
9. **A JVM library** for `toolchain`, which "wraps pypackpack" (SPEC, PyPackPack Companion) for
   Gradle users. The library is published as `org.thisisthepy.python.multiplatform:packpack`.

## 2. Where pypackpack stops

`pypackpack` is one of several repositories meant to compose into one product
(`ecosystem.md` §1):

| Repository | Owns |
|---|---|
| **`pypackpack` (`ppp`)** | **The work: acquiring Python, resolving dependencies, cross-compiling, bundling.** |
| `toolchain` | The Gradle (and `tcl`) vocabulary for that work: it translates a `python { }` block into pypackpack calls. Generating a Kotlin library that carries Python code, final app binaries, and hot reload during development are its items (SPEC, `<toolchain>`). |
| `python-multiplatform` | The runtime: CPython embedded in Kotlin Multiplatform, and everything at the language boundary. |
| `pythonx-compose` | Compose bound into Python. |

The SPEC also names services that consume pypackpack's output: `pip-jit` (recipes for packages that
need patches to build), `pip-central` (a closed-source service distributing them) and
`brainWave/intelliPush` (a management server using the fast track API). Those are separate projects.

## 3. What pypackpack deliberately is not

- **Not a Gradle plugin.** Gradle DSL, Kotlin source sets and Android/iOS app integration belong to
  `toolchain`. pypackpack exposes a library API that takes explicit directories; it does not read
  Gradle state.
- **Not the Python runtime.** Loading a bundle, `sys.path`, and reacting to a hot-reload signal
  happen in `python-multiplatform`. The `resource` bundle is the handoff format between the two.
- **Not a package index or a deploy server.** `deploy` uploads to servers someone else runs (PyPI,
  FastTrack, ResourceHub); `pip-central` and `brainWave/intelliPush` are separate projects.
  > Inferred: confirm with the maintainer. The SPEC lists the destinations as clients
  > (`ResourceHubAPI.kt # deploy client`, `FastTrackAPI.kt # deploy client`), not as servers to
  > build here.
- **Not a replacement for `uv`.** Dependency resolution and installation are delegated to `uv`;
  pypackpack adds targets, markers and per-target install directories on top.
  > Inferred: confirm with the maintainer. Stated by the code (`UVBackend`) and `ecosystem.md` §5
  > ("dependency resolution (`uv`)"), not by a maintainer-authored sentence.
- **Not a patcher of packages that need source patches to build.** SPEC core (1) excludes them
  ("except when patches are required"); recipes for them are `pip-jit`'s job.

## 4. Open questions for the maintainer

1. **Python versions.** `python install` accepts only 3.13, "due to python-multiplatform
   limitations". Is 3.13 the only version pypackpack intends to support for now, or is the
   restriction expected to follow python-multiplatform?
2. **WASM.** The README lists WASM as a target with no architecture, and `Platforms.kt` accepts
   `wasm32-pyodide2024` (family `wasm`), but `python install` has no WASM distribution and nothing
   compiles for it. Is it in scope for the first release?
3. **`build` and `bundle`.** The SPEC defines `build` as the stage that coordinates dependency
   verification, compile and bundle, but bundling has no CLI command and `build` does not call it.
   Should `build` take a bundle type, or should `bundle` become its own command?
4. **Deploy destinations.** "Need to specify the target deploy server (PyPI or FastTrack)" is still
   open in the SPEC.
5. ~~**The Python wrapper.** Is distributing `pypackpack` through PyPI intended?~~ **Answered
   (2026-10-03):** yes. The wheel carries the native binary per platform; see `docs/SPEC.md`,
   *Distribution through PyPI*.
