## Dependency

- Dependency-related Python commands do not work correctly
- Fix the execution path for dynamic commands
- ~~Target markers vary depending on the target when adding dependencies to pyproject.toml (sys_platform vs platform_system)~~ **FIXED**: `MarkerPolicy.targetKeyFor()` in `dependency/middleware/DefaultMiddleware.kt` now tolerates both `platform_system` and `sys_platform` spellings when parsing persisted markers (lines 375-388), and comparison logic handles both forms equivalently.
- Whether to inherit the target list from the parent/upper directory
- Need to install .whl files when adding package dependencies to lint platform-specific code (<package>/crossenv/<target>)
- Should all packages share a single venv for the runtime dev environment, or should each package have its own? (Having separate venvs per package seems better)
- ~~Whether a .whl exists for a specific target can only be verified by actually attempting to install it.~~ **PARTLY FIXED (#18)**: it still can only be found by attempting the install, but `UVBackend.installDependenciesToTarget` now parses uv's failure (`dependency/backend/MissingWheel.kt`, `parseMissingWheel`) and throws `NoWheelForTargetException` naming the package, the target and whether only an sdist exists. Text uv words differently is passed through raw.
- **FIXED (2d673a1)**: TOML quote character handling in `utils/toml/TomlEditor.kt` (lines 475-490) previously used `String.trim()` which stripped all quote runs at string edges, corrupting marker values like `"... sys_platform == 'win32'"`. Fixed via `unwrapOuterQuotes()` to remove exactly one matching quote pair.

## Files

- **FIXED (#96)**: deleting a directory followed symbolic links out of it. Kotlin's `deleteRecursively` walks
  into a link to a directory and empties its target, and pypackpack used it for bundle output on `--overwrite`,
  compile's build directory on `--overwrite`, `python use` replacing `.venv`, `python uninstall`, `installPython`
  staging, package removal and the Cargo output. Every one now calls `deleteWithoutFollowingLinks`
  (`utils/FileTree.kt`), which removes a link and never its target; `FileTreeTest` and a regression test per
  call site (a link to an outside directory, which survives) were red before the fix.

