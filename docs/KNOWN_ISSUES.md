## Dependency

- Dependency-related Python commands do not work correctly
- Fix the execution path for dynamic commands
- Target markers vary depending on the target when adding dependencies to pyproject.toml (sys_platform vs platform_system)
- Whether to inherit the target list from the parent/upper directory
- Need to install .whl files when adding package dependencies to lint platform-specific code (<package>/crossenv/<target>)
- Should all packages share a single venv for the runtime dev environment, or should each package have its own? (Having separate venvs per package seems better)
- Whether a .whl exists for a specific target can only be verified by actually attempting to install it. (If it doesn't exist, uv will fail to build and throw an error.)
