## Dependency

- Dependency-related Python commands do not work correctly
- Fix the execution path for dynamic commands
- ~~Target markers vary depending on the target when adding dependencies to pyproject.toml (sys_platform vs platform_system)~~ **FIXED**: `MarkerPolicy.targetKeyFor()` in `dependency/middleware/DefaultMiddleware.kt` now tolerates both `platform_system` and `sys_platform` spellings when parsing persisted markers (lines 375-388), and comparison logic handles both forms equivalently.
- Whether to inherit the target list from the parent/upper directory
- Need to install .whl files when adding package dependencies to lint platform-specific code (<package>/crossenv/<target>)
- Should all packages share a single venv for the runtime dev environment, or should each package have its own? (Having separate venvs per package seems better)
- Whether a .whl exists for a specific target can only be verified by actually attempting to install it. (If it doesn't exist, uv will fail to build and throw an error.)
- **FIXED (2d673a1)**: TOML quote character handling in `utils/toml/TomlEditor.kt` (lines 475-490) previously used `String.trim()` which stripped all quote runs at string edges, corrupting marker values like `"... sys_platform == 'win32'"`. Fixed via `unwrapOuterQuotes()` to remove exactly one matching quote pair.
