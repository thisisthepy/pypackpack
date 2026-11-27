#!/usr/bin/env python3
"""Measure how fast pypackpack's everyday commands are (docs/SPEC.md, "Must be fast"; #160).

    python3 .github/scripts/native/build_speed.py <pypackpack binary> [--runs 5] [--json out.json]

Times, on a fresh scratch project, each command `--runs` times and reports min / median / max wall time:

  version         pypackpack version (start-up plus the uv probe)
  init            pypackpack init (uv init plus the scaffold)
  add             pypackpack add six==1.17.0 (resolve, lock, install; the uv cache is warm after run 1)
  sync            pypackpack sync after deleting .venv
  build-resource  pypackpack build core resource (bundle a small package)
  build-compile   pypackpack build core (Meson compile of one C extension)

A measurement is only meaningful alone (AGENTS.md rule 8): the script refuses to start when the 1-minute
load average is 1.0 or more, and prints `uptime` first. Nothing outside a temporary directory is written,
except the optional JSON. The first result is recorded in docs/SPEC.md with its date and machine.
"""
from __future__ import annotations

import argparse
import json
import os
import platform
import shutil
import statistics
import subprocess
import sys
import tempfile
import time
from pathlib import Path


def timed(exe: Path, args: list[str], cwd: Path) -> float:
    start = time.perf_counter()
    result = subprocess.run([str(exe), *args], cwd=cwd, capture_output=True, text=True)
    elapsed = time.perf_counter() - start
    if result.returncode != 0:
        print(f"FAIL pypackpack {' '.join(args)}: exit {result.returncode}\n{result.stdout}{result.stderr}")
        sys.exit(1)
    return elapsed


def project(exe: Path, root: Path, name: str) -> Path:
    subprocess.run([str(exe), "init", name, "--name", name], cwd=root, check=True, capture_output=True)
    proj = root / name
    subprocess.run([str(exe), "package", "add", "core"], cwd=proj, check=True, capture_output=True)
    core = next(p.parent for p in proj.rglob("pyproject.toml") if p.parent.name == "core")
    pkg = core / "src/main/core"
    pkg.mkdir(parents=True, exist_ok=True)
    (pkg / "__init__.py").write_text("VALUE = 1\n", encoding="utf-8")
    (pkg / "_fast.c").write_text(
        "#include <Python.h>\n"
        "static struct PyModuleDef def = {PyModuleDef_HEAD_INIT, \"_fast\", NULL, 0, NULL};\n"
        "PyMODINIT_FUNC PyInit__fast(void) { return PyModuleDef_Init(&def); }\n",
        encoding="utf-8",
    )
    return proj


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("binary")
    parser.add_argument("--runs", type=int, default=5)
    parser.add_argument("--json")
    args = parser.parse_args()
    exe = Path(args.binary).resolve()

    if hasattr(os, "getloadavg"):
        load = os.getloadavg()[0]
        print(subprocess.run(["uptime"], capture_output=True, text=True).stdout.strip())
        if load >= 1.0:
            print(f"refusing to measure: 1-minute load average is {load:.2f} (needs < 1.0, AGENTS.md rule 8)")
            return 1

    samples: dict[str, list[float]] = {k: [] for k in ["version", "init", "add", "sync", "build-resource", "build-compile"]}
    for run in range(args.runs):
        root = Path(tempfile.mkdtemp(prefix="ppp-speed-"))
        try:
            samples["version"].append(timed(exe, ["version"], root))
            samples["init"].append(timed(exe, ["init", "timed", "--name", "timed"], root))
            proj = project(exe, root, f"p{run}")
            samples["add"].append(timed(exe, ["add", "six==1.17.0"], proj))
            shutil.rmtree(proj / ".venv", ignore_errors=True)
            samples["sync"].append(timed(exe, ["sync"], proj))
            samples["build-resource"].append(timed(exe, ["build", "core", "resource", "--overwrite"], proj))
            samples["build-compile"].append(timed(exe, ["build", "core", "--overwrite"], proj))
        finally:
            shutil.rmtree(root, ignore_errors=True)

    rows = {k: {"min": min(v), "median": statistics.median(v), "max": max(v)} for k, v in samples.items()}
    print(f"{'command':<16}{'min s':>9}{'median s':>10}{'max s':>9}")
    for k, r in rows.items():
        print(f"{k:<16}{r['min']:>9.2f}{r['median']:>10.2f}{r['max']:>9.2f}")
    if args.json:
        Path(args.json).write_text(
            json.dumps(
                {
                    "date": time.strftime("%Y-%m-%d"),
                    "machine": f"{platform.system()} {platform.release()} {platform.machine()}",
                    "runs": args.runs,
                    "binary": str(exe),
                    "results": rows,
                },
                indent=2,
            )
            + "\n",
            encoding="utf-8",
        )
    return 0


if __name__ == "__main__":
    sys.exit(main())
