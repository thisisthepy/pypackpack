#!/usr/bin/env python3
"""Packs python-multiplatform's free-threaded wasm runtime for `ppp python install 3.15t wasm32-emscripten` (#82).

    python3 .github/scripts/wasm/pack_pmp_runtime.py <python-multiplatform checkout> <out dir>

Interim (c), team lead 2026-11-27. python-multiplatform builds CPython 3.15t with pthreads on Emscripten 5.0.3
(its scripts/wasm/build-cpython.sh) but publishes no release asset yet. Its `cpython-3.15.0t-wasm` release, design
(a), waits for the owner's OK. This script reads that build's output from a checkout and writes:

- <out dir>/python-3.15.0t-wasm32-emscripten.tar.gz, deterministic (sorted, mtime 0, uid 0), laid out as
      python/include/python3.15t/       CPython's Include/ tree and the build's pyconfig.h
      python/lib/libpython3.15t.a
      python/lib/python3.15t/_sysconfigdata_t_emscripten_wasm32-emscripten.py
      python/runtime/python.wasm, python.mjs, python3.15t.zip   (the staged runtime the library loads)
- <out dir>/wasm-runtime.json: {"archive", "sha256", "source"}. PPP_WASM_RUNTIME_MANIFEST names this file.

It fails, naming the missing piece, if the checkout has no such build. Run build-cpython.sh there first.
"""
from __future__ import annotations

import gzip
import hashlib
import io
import json
import subprocess
import sys
import tarfile
from pathlib import Path

VERSION = "3.15t"
ARCHIVE = "python-3.15.0t-wasm32-emscripten.tar.gz"


def fail(message: str) -> None:
    print(f"pack_pmp_runtime: {message}", file=sys.stderr)
    sys.exit(1)


def git_head(directory: Path) -> str:
    result = subprocess.run(["git", "-C", str(directory), "rev-parse", "HEAD"], capture_output=True, text=True)
    return result.stdout.strip() if result.returncode == 0 else "unknown"


def main() -> None:
    if len(sys.argv) != 3:
        fail(__doc__)
    checkout, out = Path(sys.argv[1]).resolve(), Path(sys.argv[2]).resolve()
    caches = checkout / ".caches"
    cpython = caches / "wasm-build/cpython315t"
    build = cpython / "cross-build/wasm32-emscripten/build/python"
    runtime = caches / "wasm-runtime-315t"
    sysconfig = sorted(build.glob("build/lib.emscripten-*-wasm32-3.15/_sysconfigdata_t_emscripten_wasm32-emscripten.py"))
    pieces = {
        "CPython's Include/Python.h": cpython / "Include/Python.h",
        "the build's pyconfig.h": build / "pyconfig.h",
        "the build's libpython3.15t.a": build / "libpython3.15t.a",
        "the staged runtime's python.wasm": runtime / "python.wasm",
        "the staged runtime's python.mjs": runtime / "python.mjs",
        "the staged runtime's python3.15t.zip": runtime / "python3.15t.zip",
    }
    for what, path in pieces.items():
        if not path.is_file():
            fail(f"{checkout} has no {what} ({path}); run python-multiplatform/scripts/wasm/build-cpython.sh there first")
    if len(sysconfig) != 1:
        fail(f"expected one _sysconfigdata under {build}/build, found {[str(p) for p in sysconfig]}")

    entries: dict[str, Path] = {}
    for header in sorted((cpython / "Include").rglob("*")):
        if header.is_file():
            entries[f"python/include/python{VERSION}/{header.relative_to(cpython / 'Include').as_posix()}"] = header
    entries[f"python/include/python{VERSION}/pyconfig.h"] = build / "pyconfig.h"
    entries["python/lib/libpython3.15t.a"] = build / "libpython3.15t.a"
    entries[f"python/lib/python{VERSION}/{sysconfig[0].name}"] = sysconfig[0]
    for name in ("python.wasm", "python.mjs", "python3.15t.zip"):
        entries[f"python/runtime/{name}"] = runtime / name

    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode="w", format=tarfile.PAX_FORMAT) as tar:
        for name in sorted(entries):
            data = entries[name].read_bytes()
            info = tarfile.TarInfo(name)
            info.size, info.mtime, info.mode, info.uid, info.gid = len(data), 0, 0o644, 0, 0
            tar.addfile(info, io.BytesIO(data))
    out.mkdir(parents=True, exist_ok=True)
    archive = out / ARCHIVE
    with open(archive, "wb") as raw, gzip.GzipFile(fileobj=raw, mode="wb", mtime=0, filename="") as gz:
        gz.write(buffer.getvalue())
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    emsdk = (caches / "emsdk/upstream/emscripten/emscripten-version.txt")
    source = (
        f"python-multiplatform {git_head(checkout)[:12]} build output, CPython {git_head(cpython)[:12]}, "
        f"emsdk {emsdk.read_text().strip().strip(chr(34)) if emsdk.is_file() else 'unknown'}"
    )
    manifest = out / "wasm-runtime.json"
    manifest.write_text(json.dumps({"archive": str(archive), "sha256": digest, "source": source}, indent=2) + "\n")
    print(f"pack_pmp_runtime: {len(entries)} files -> {archive} (sha256 {digest}); manifest {manifest}")


if __name__ == "__main__":
    main()
