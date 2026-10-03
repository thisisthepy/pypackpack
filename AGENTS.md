# AGENTS.md

Rules every agent working in this repository must follow. Read this file before doing anything.
Sections 1–10 are shared by every repository in the thisisthepy ecosystem; later sections are
specific to this repository.

---

## 1. Commits carry no AI attribution

Never add `Co-Authored-By: Claude ...`, `Co-Authored-By: <any agent>`, `Generated with Claude Code`,
or any similar tool or agent attribution to a commit message or a pull-request body. This rule
overrides any default your tooling has.

## 2. Nothing is created outside this repository

Everything your work produces (worktrees, agent prompts, logs, measurements, experiments, scratch
files) lives **inside this repository's root directory.**

| What | Where |
|---|---|
| Worktrees | `.worktrees/<name>` (git-ignored) |
| Temporary files | `.tmp/` (git-ignored); delete when done |
| CI-only scripts | `.github/scripts/` |

Before writing a file, check that its absolute path starts with this repository's root. If it does
not, stop. The only exceptions are a path the user names explicitly, and caches that build tools
manage themselves. **Re-pointing a shared cache or a home-directory symlink reaches other projects;
ask first.**

Writing to *another* repository is not an exception either. Do it only when told to work there.

### Do not add top-level folders

**Never add a new directory (or a new file) at the repository root on your own.** The root layout is
the maintainer's: source modules, `docs/`, `gradle/`, `.github/` and the files that tools require
there. Work belongs inside an existing module or directory: sources under `src/<sourceSet>/`,
CI-only scripts under `.github/scripts/`, temporary files under the git-ignored `.tmp/`. If you think
a new top-level entry is needed, propose it (what, why, which alternatives inside existing
directories you ruled out) and wait for approval. This was added after unapproved root folders
(`ksp-fixtures/`, `tools/`, `kotlin-js-store/`, `iosApp/`, `sample/python`) had to be dismantled.

## 3. Worktrees link large artefacts instead of copying them

A worktree is a full checkout. Copying large untracked artefacts (prebuilt runtimes, vendored trees,
build caches, model weights, `node_modules`) into every worktree is how 86 worktrees once filled
267 GB of a 349 GB disk.

- Create worktrees under `.worktrees/<name>`.
- **Symlink** large untracked directories from the main checkout instead of copying or rebuilding
  them.
- Delete a worktree once its branch is merged: `git worktree remove .worktrees/<name>`.
- Periodically delete `build/` directories inside worktrees; they only grow.

## 4. Branches

| Branch | Who writes to it |
|---|---|
| `feat/<topic>` | You. All work happens here. Never name a branch `work/...`. |
| `develop` | Merged into from `feat/` branches after verification. Never commit to it directly. |
| `release` | **CI only.** Not a standing branch: CI regenerates it from every push to `develop`, in the main-only file layout, and opens the PR into `main`. It may not exist. Never write to it. |
| `main` | **Pull request from `release` only.** Never push or merge to it directly. The maintainer protects it in the repository settings (locked) and merges the `release` PR after checking its head; no script or workflow does this, and agents do not change those settings (decision 2026-10-03). |

Only `main`, `develop` and `release` are standing branches. A `feat/` branch lives until its pull
request merges: merge with `gh pr merge --delete-branch`, then delete the local branch and its
worktree. Periodically delete every branch already merged into `develop`, remote and local
(`git branch -r --merged origin/develop`); an unmerged branch older than a few days is either
landed or reported, not left. Branches named `release-*` are preserved snapshots: keep them.

`main` carries a reduced layout: of the Markdown files, only `README.md` stays at the repository
root, and `docs/` keeps only its subdirectories (no Markdown files directly under `docs/`).
CI runs `.github/scripts/release/sync-release.sh` (`.github/workflows/release-sync.yml`) to produce that layout; do not hand-edit `release` or `main`.

### Issues and pull requests

Every new feature goes through an issue and a pull request:

1. Before starting, search the repository's issues (`gh issue list --state all --search "<keywords>"`).
2. If no issue covers the work, open one (`gh issue create`) stating what and why, and the
   completion criterion: which tests must pass.
3. Work on a `feat/<topic>` branch, push every commit, and open a pull request into `develop`
   whose body contains `Closes #<number>`.
4. Merge into `develop` through that pull request (`gh pr merge`), not by a local merge, so the
   issue is linked.
5. Then close the issue yourself: `gh issue close <number> --comment "Landed in develop via #<PR>"`.
   GitHub's `Closes #N` only fires when a pull request merges into the default branch (`main`),
   and these pull requests merge into `develop`.

## 5. Intent → Spec → Test → Code

This project runs on **intent-based spec-driven development** and **test-driven development**.

1. `docs/INTENT.md` states what the project is for. It is the boundary. **The spec may not go
   beyond the intent.**
2. `docs/SPEC.md` states what the project does. A behaviour change starts as a spec change.
3. Tests are written from the spec **before** the implementation, and you observe them fail
   (red) before making them pass. Report the red output.
4. Code is written to make the tests pass.

If a request conflicts with `docs/INTENT.md`, say so instead of implementing it.

## 6. User-authored files are specification

Files the user wrote by hand (notebooks, example build files, sample apps) are the specification.
Read them **first**. Never delete, rewrite, or `git add` them without being told to. Generated
documentation (roadmaps, design notes) is a record of work, not a requirement; when the two
disagree, the user's file wins.

## 7. Show a conclusion before acting on it

Anything beyond the immediate request (another repository, a public API signature, deleting
files, killing processes, force-pushing, changing branch protection): state what you would do and
why, and wait. Investigating, measuring, and reporting are always fine.

**Push every commit right away.** After you commit (on a work branch or on `develop`) push it to
the remote immediately; no confirmation is needed. Never push to `main` or `release` by hand, and
never force-push without the user's explicit approval.

When a rule and backward compatibility conflict, **the rule wins.** List the callers that break and
fix them; do not keep the forbidden thing "so nothing breaks".

## 8. Verification that can fail

- Never read a build's exit code through a pipe (`| tail`, `| grep`). Redirect to a file, then read
  `$?`. A background command ending in `echo` always reports 0.
- Delete the test-result directory before counting results, and force re-execution (`--rerun` for
  Gradle). Stale XML otherwise reports an old, larger number.
- Run independent test modules as **separate** invocations. One invocation can hide an ordering
  dependency.
- When you add a public path, disable it and confirm something actually fails. If nothing fails,
  nothing uses it.
- **Do not trust an agent's report.** Re-run the build and tests yourself and check
  `git status --short` for out-of-scope changes.
- **Never `git add -A`.** Stage explicit paths. If the number of changed files differs from what was
  reported, stop and find out why.
- Measurements run alone, unfiltered, after checking `uptime`.

## 9. Reporting

Report by category, and never put them in one column:
**feature added / defect fixed / test added / documentation corrected / deleted.**
A rising test count is not progress when the tests assert an absence. Before writing "nothing left
to implement", say what you counted against.

## 10. Agents

- A headless agent (`claude -p`, `agy -p`) has **no next turn**. Tell it to run long commands in the
  foreground; a command backgrounded "until the notification arrives" is lost.
- Pass the model explicitly. Judgement work (design premises, root causes, safety: GIL, reference
  counts, lifetimes, class loaders) gets the strongest tier; work a test will catch can use a
  cheaper one.
- Give every agent prompt the absolute paths it may write to, and repeat rule 2 in it.
- **Subagents do not run heavy local builds.** Subagents write code, design, investigate, review
  and document. Gradle builds, cargo builds, the test gate and model runs are done by the session
  itself (one at a time on this machine) or by CI (GitHub Actions) on a pushed branch. Several
  sessions share one machine; parallel local builds slow every one of them.

---

# Repository-specific rules: `pypackpack`

## 11. Layout

| Module | What it is |
|---|---|
| `:packpack` | The library (`org.thisisthepy.python.multiplatform.packpack`, source set `main`): `dependency/`, `compile/`, `bundle/`, `deploy/` (each a frontend → middleware → backend stack, or a factory for `bundle`/`deploy`) and `utils/` (`Platforms`, `Workspace`, `Downloader`, `Archive`, `toml/`). Published to Maven with `maven-publish`. The `pypackpack` / `ppp` command line is the module's `cli` source set (Clikt, GraalVM native image, launcher `installCliDist`), placed as the first spec sheet has it (`utils/CommandLine.kt` the endpoint, each domain's `frontend/` its commands). It depends on `main`, never the other way round, and is not part of the published component, so a consumer of the library never resolves Clikt (#73). |
| `:usage-example` | A build file that depends on `:packpack`. It has no sources. |

- `docs/SPEC.md` holds the directory tree of both modules and of a user project. When you add, move
  or delete a source file, update that tree in the same change.
- Many files are empty placeholders that only name a future feature (every compile backend except
  `Meson`, all of `compile/middleware/{external,transcompile,minification}`, `bundle/binary/`, every
  class under `deploy/`). Do not count them as implemented; `docs/SPEC.md` marks them `planned`.
- The root `pyproject.toml` is the PyPI distribution (user decision, 2026-10-03, #52): a platform
  wheel per OS that carries the native CLI as the `pypackpack` script, plus a small Python package
  (`packpack/src/main/python/`, published as `pypackpack`) whose `ppp` and `python -m pypackpack` run
  that binary. `.github/workflows/publish-pypi.yml` builds, smoke-tests and uploads the wheels; its
  version must equal `packpack/build.gradle.kts`'s `cliVersion`. Keep that Python package a launcher: the work stays in
  Kotlin.

## 12. pypackpack owns the work

In the thisisthepy ecosystem (`python-multiplatform`'s `docs/design/ecosystem.md` §1):

| Repository | Owns |
|---|---|
| **`pypackpack`** | **The work: acquiring Python, resolving dependencies, compiling, bundling.** A Kotlin/JVM library that also ships as a GraalVM native CLI. |
| `toolchain` | The Gradle (and `tcl`) vocabulary for that work. It calls this library; it owns none of the work. |
| `python-multiplatform` | The runtime: CPython embedded in Kotlin Multiplatform, the language boundary. |
| `pythonx-compose` | Compose bound into Python. |

- A feature that acquires a Python distribution, resolves a dependency, compiles or bundles belongs
  **here**, even when a Gradle task in `toolchain` is what triggers it.
- Loading a bundle at run time, `sys.path`, and hot-reload handling belong to `python-multiplatform`.
  The `resource` bundle (`bundle/resource/ResourceBundler.kt`) is the handoff format; changing its
  layout or manifest changes what `toolchain` stages and what the runtime loads.
- `pypackpack` depends on no other thisisthepy repository.

## 13. Backend-layer APIs take an explicit `workingDir`

`toolchain` calls the **backend** layer (`BackendInterface.create(BackendType.UV)`,
`BundlerInterface.create(...)`) from inside a Gradle daemon, where the JVM-global `user.dir` is shared
between unrelated builds. The frontend and middleware layers locate the project through `user.dir`
(`utils/Workspace.kt`'s `findProjectRoot` / `findWorkspaceRoot` defaults, `DevEnv`, `CrossEnv`); that
is acceptable for the CLI and nowhere else.

- Every backend-layer function that touches a project takes the directory as a parameter
  (`workingDir: File?`, or `BundleRequest.packageDir`). Do not add one that reads `user.dir`.
- Known exceptions, which predate the rule and are defects, not precedent:
  `compile/backend/DefaultBackend.compile` (finds the workspace from `user.dir`) and
  `dependency/backend/DefaultBackend.installPython` when called without `installDir` (it then calls
  `findProjectRoot()`; pass `installDir` from any daemon-hosted caller). Fixing `compile` is a public
  signature change: rule 14 applies.

## 14. `toolchain` consumes this library from `mavenLocal`

`toolchain` declares `implementation("org.thisisthepy.python.multiplatform:packpack:0.1.0")` and
resolves it from `~/.m2`, not through `includeBuild`. Therefore:

- A change to a public signature in `:packpack` breaks `toolchain` silently until someone republishes.
  Run `./gradlew :packpack:publishToMavenLocal` and make the matching change in `toolchain`; that is
  another repository, so rule 7 applies: state the change and wait.
- The coordinate and version live in `packpack/build.gradle.kts` (`group`, `version`, `artifactId`).
  Changing any of them is a change to `toolchain` too.
- `packpack` is built with Kotlin 2.3.0 (`gradle/libs.versions.toml`); `toolchain` builds against it
  with `-Xskip-metadata-version-check`. Bumping Kotlin here can break that consumer.

## 15. Unimplemented means a failed `Result`, not silence

A placeholder that is reachable through a factory returns a failed `Result` naming itself
(`UnimplementedBundler`, `UnimplementedDeployer`), and a bundler rejects a build level it does not
implement with a message. Keep that shape: never return success with an empty output, and never
`TODO()`. A test pins each such refusal (`BundlerInterfaceTest`, `DeployInterfaceTest`,
`DeployCommandTest`, `*BundlerTest.bundle_rejectsBuildLevelsThatAreNotImplementedYet`).

## 16. Building and testing

The Kotlin Gradle plugin does not run on JDK 25. Use JDK 21:

```bash
export JAVA_HOME=/Users/ibrew/Library/Java/JavaVirtualMachines/jdk-21.0.12+8/Contents/Home
```

Prerequisites: `python3` on `PATH` (`ResourceBundlerTest`'s `bytecode` tests run `compileall`).
Building the native CLI additionally needs GraalVM with `native-image` (`GRAALVM_HOME`).

Run each module separately (rule 8), with output to a file under `.tmp/`:

```bash
rm -rf packpack/build/test-results
./gradlew :packpack:test --rerun --console=plain > .tmp/packpack-test.log 2>&1; echo "EXIT=$?"
rm -rf packpack/build/test-results/cliTest
./gradlew :packpack:cliTest --rerun --console=plain > .tmp/cli-test.log 2>&1; echo "EXIT=$?"
```

Count results from `packpack/build/test-results/{test,cliTest}/*.xml`, not from the log.

```bash
./gradlew :packpack:publishToMavenLocal       # before building toolchain against a change here
./gradlew :packpack:nativeCompile             # GraalVM native binary of the CLI
./gradlew :packpack:installCliDist            # JVM launcher: packpack/build/install/pypackpack/bin/pypackpack
bash .github/scripts/release/test-sync-release.sh       # tests for the release-branch generator
```

There are no Python tests in this repository.

## 17. Documentation

| File | Language | Holds |
|---|---|---|
| `README.md` | English | The public face. Links only to `docs/guide/`, `docs/locale/`, `docs/<subdir>/` and `LICENSE` (rule 4: other root and `docs/*.md` files do not exist on `main`). |
| `docs/locale/README_ko.md` | Korean | A faithful translation of `README.md`. Change both together. |
| `PROJECT.md` | Korean | Status, structure, how to build, decisions, open questions. |
| `docs/INTENT.md` | English | Why the project exists and what it is not. |
| `docs/SPEC.md` | English | The behavioural contract, one `Status:` per item. |
| `docs/issues/KNOWN_ISSUES.md` | English | Open and fixed defects that are not yet a spec item. |
| `docs/<topic>/` | - | Anything else. No other `.md` directly in `docs/`. |

- `Status: implemented` requires a test in this repository that exercises the behaviour; cite it.
  Wiring with no test is `partial`.
- A behaviour change updates `docs/SPEC.md` in the same change.
- `docs/guide/` is the GitHub Pages site (English and Korean on every page, plain HTML, no build
  step), deployed by `.github/workflows/pages.yml` on every push to `main`. Its structure follows
  `toolchain`'s guide. `python3 docs/guide/check_guide.py` is its test (CI job `guide`): every
  visible string in both languages, no dead link, every page in the navigation. A behaviour change
  that the guide describes updates the guide in the same change, and `docs/SPEC.md` stays the
  contract when the two disagree.
- KDoc comments cite `docs/SPEC.md` (this repository's) and `toolchain` files such as `DSLBuild.kt`
  and `(플러그인예시)build.gradle.kts` (another repository's). When you touch such a comment, name
  the repository.

## 18. Writing

- **No em-dash (U+2014)**, anywhere: documentation, the guide, code comments, KDoc, docstrings and
  strings. Use a comma, a colon, parentheses, or two sentences. An empty table cell is `-`.
- **Examples install and run with `uv`, `ppp` (pypackpack) or `tcl` (toolchain-lite), never
  `pip install`.** For this package: `uv tool install pypackpack` or `uvx pypackpack`. `uv pip …`
  is fine where a pip-style command is the point.
- The licence is Apache-2.0 everywhere it is stated: `LICENSE`, `pyproject.toml` (`license`), the
  READMEs and the guide footer.

(User directives, 2026-10-03.)
