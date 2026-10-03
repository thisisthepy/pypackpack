"""Run the native `pypackpack` binary installed beside this interpreter (`ppp`, `python -m pypackpack`)."""
import os
import subprocess
import sys
import sysconfig
from pathlib import Path

_NAME = "pypackpack.exe" if os.name == "nt" else "pypackpack"


def _candidates():
    yield Path(sysconfig.get_path("scripts")) / _NAME
    user_scheme = getattr(sysconfig, "get_preferred_scheme", lambda _: f"{os.name}_user")("user")
    try:
        yield Path(sysconfig.get_path("scripts", user_scheme)) / _NAME
    except KeyError:
        pass


def _binary() -> Path:
    tried = []
    for path in _candidates():
        if path.is_file():
            return path
        tried.append(str(path))
    sys.exit(
        "pypackpack: the native binary is not installed beside this Python "
        f"(looked for {', '.join(tried)}). Install a platform wheel: pip install pypackpack"
    )


def main() -> None:
    exe = _binary()
    args = [str(exe), *sys.argv[1:]]
    if os.name == "nt":
        sys.exit(subprocess.call(args))
    os.execv(str(exe), args)


if __name__ == "__main__":
    main()
