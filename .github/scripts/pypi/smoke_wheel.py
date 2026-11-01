#!/usr/bin/env python3
"""Install a built wheel into a fresh venv and check that what a PyPI user runs works.

    python3 .github/scripts/pypi/smoke_wheel.py dist/pypackpack-<v>-<tag>.whl

Fails (exit 1) unless, from that venv:
  1. `pypackpack --help` exits 0 and prints the usage line;
  2. `pypackpack version`, `ppp version` and `python -m pypackpack version` all print
     "PyPackPack version <pyproject.toml's version>";
  3. `pypackpack init smoke --python 3.13` creates a project (a real command, which needs uv:
     pypackpack finds it on PATH or downloads it into ~/.pypackpack/uv).
"""
from __future__ import annotations

import os
import subprocess
import sys
import tempfile
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]


def check(cmd: list[str], expect: str | None, cwd: Path | None = None) -> None:
    result = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True)
    shown = " ".join(Path(c).name if i == 0 else c for i, c in enumerate(cmd))
    if result.returncode != 0 or (expect and expect not in result.stdout):
        print(f"FAIL {shown}: exit {result.returncode}\n{result.stdout}{result.stderr}")
        sys.exit(1)
    print(f"ok   {shown}")


def main() -> int:
    wheel = Path(sys.argv[1]).resolve()
    version = tomllib.loads((ROOT / "pyproject.toml").read_text(encoding="utf-8"))["project"]["version"]
    with tempfile.TemporaryDirectory() as tmp:
        venv = Path(tmp) / "venv"
        subprocess.run([sys.executable, "-m", "venv", str(venv)], check=True)
        bin_dir = venv / ("Scripts" if os.name == "nt" else "bin")
        exe = ".exe" if os.name == "nt" else ""
        python = bin_dir / f"python{exe}"
        subprocess.run([str(python), "-m", "pip", "install", "--quiet", "--no-index", str(wheel)], check=True)

        check([str(bin_dir / f"pypackpack{exe}"), "--help"], "Usage: pypackpack")
        expected = f"PyPackPack version {version}"
        check([str(bin_dir / f"pypackpack{exe}"), "version"], expected)
        check([str(bin_dir / f"ppp{exe}"), "version"], expected)
        check([str(python), "-m", "pypackpack", "version"], expected)

        work = Path(tmp) / "work"
        work.mkdir()
        check([str(bin_dir / f"pypackpack{exe}"), "init", "smoke", "--python", "3.13"], None, cwd=work)
        if not (work / "smoke" / "pyproject.toml").is_file():
            print("FAIL pypackpack init: no smoke/pyproject.toml")
            return 1
        print("ok   init created smoke/pyproject.toml")
    return 0


if __name__ == "__main__":
    sys.exit(main())
