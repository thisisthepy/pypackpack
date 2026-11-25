#!/usr/bin/env python3
"""Run the GraalVM native `pypackpack` binary through every command group (docs/SPEC.md, the GraalVM goal; #160).

    python3 .github/scripts/native/native_cli_suite.py packpack/build/native/nativeCompile/pypackpack[.exe]

The JVM test suites (`:packpack:test`, `:packpack:cliTest`) cannot see what native-image leaves out
(reflection, resources, the coroutine and HTTPS download paths), so this runs the real binary in a scratch
project: version, help, init with targets, target list/add/remove, package add, root add/tree/sync/remove
(uv and PyPI), python list/find, and build <package> resource. It fails (exit 1) on the first command
that exits non-zero or does not print what it should, showing its output.

Everything is created under a temporary directory that is deleted afterwards. Needs network access (uv,
PyPI) like the commands themselves.
"""
from __future__ import annotations

import os
import shutil
import subprocess
import sys
import tempfile
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]


def run(exe: Path, args: list[str], cwd: Path, expect: str | None = None, files: list[str] = ()) -> str:
    result = subprocess.run([str(exe), *args], cwd=cwd, capture_output=True, text=True)
    shown = "pypackpack " + " ".join(args)
    out = result.stdout + result.stderr
    missing = [f for f in files if not (cwd / f).exists()]
    if result.returncode != 0 or (expect and expect not in out) or missing:
        print(f"FAIL {shown}: exit {result.returncode}, expected {expect!r}, missing {missing}\n{out}")
        sys.exit(1)
    print(f"ok   {shown}")
    return out


def main() -> int:
    exe = Path(sys.argv[1]).resolve()
    if not exe.is_file():
        print(f"no binary at {exe}")
        return 1
    version = tomllib.loads((ROOT / "pyproject.toml").read_text(encoding="utf-8"))["project"]["version"]
    tmp = Path(tempfile.mkdtemp(prefix="ppp-native-"))
    try:
        run(exe, ["--version"], tmp, f"PyPackPack version {version}")
        run(exe, ["version"], tmp, "UV version")
        run(exe, ["--help"], tmp, "Usage")
        run(exe, ["init", "demo", "--name", "demo", "--target", "aarch64-linux-android"], tmp, files=["demo/pyproject.toml", "demo/README.md"])
        project = tmp / "demo"
        if "aarch64-linux-android" not in (project / "pyproject.toml").read_text(encoding="utf-8"):
            print("FAIL init --target did not record the platform")
            return 1
        refused = subprocess.run([str(exe), "init", "demo"], cwd=tmp, capture_output=True, text=True)
        if refused.returncode == 0:
            print("FAIL init into an existing project was not refused")
            return 1
        print("ok   pypackpack init demo (refused: existing project)")
        run(exe, ["target", "add", "arm64-apple-ios"], project)
        run(exe, ["target", "list"], project, "arm64-apple-ios")
        run(exe, ["target", "remove", "arm64-apple-ios"], project)
        run(exe, ["package", "add", "core"], project)
        core = next(p.parent for p in project.rglob("pyproject.toml") if p.parent.name == "core")
        (core / "src/main/core").mkdir(parents=True, exist_ok=True)
        (core / "src/main/core/__init__.py").write_text("VALUE = 1\n", encoding="utf-8")
        run(exe, ["add", "six==1.17.0"], project)
        run(exe, ["tree"], project, "six v1.17.0")
        run(exe, ["sync"], project)
        run(exe, ["remove", "six"], project)
        run(exe, ["python", "list"], project)
        run(exe, ["build", "core", "resource"], project, "Bundled 'core' as resource")
        print("native CLI suite passed")
        return 0
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
