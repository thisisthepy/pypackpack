package org.thisisthepy.python.multiplatform.packpack.dependency.backend

/**
 * The Python files a cross build environment carries (docs/SPEC.md *Cross build environment*). Kept as
 * strings so the GraalVM native image needs no resource configuration.
 */
internal object CrossBuildScripts {
    /** `site-packages/_ppp_cross.pth`: imports the hook at every start of the environment's interpreter. */
    const val PTH = "import _ppp_cross\n"

    /**
     * `site-packages/_ppp_cross.py`: does nothing unless `PPP_CROSS_CONFIG` names a config file; then the
     * interpreter reports the target (sysconfig data, platform, prefix, uname) for the build.
     */
    val HOOK =
        """
        |# pypackpack cross build hook (docs/SPEC.md *Cross build environment*). Inactive unless PPP_CROSS_CONFIG is set.
        |import os
        |import sys
        |
        |
        |def _activate():
        |    path = os.environ.get("PPP_CROSS_CONFIG")
        |    if not path:
        |        return
        |    import json
        |
        |    with open(path, encoding="utf-8") as f:
        |        cfg = json.load(f)
        |    sys.path.insert(0, cfg["sysconfig_dir"])
        |    os.environ["_PYTHON_SYSCONFIGDATA_NAME"] = cfg["sysconfigdata_name"]
        |    os.environ["_PYTHON_HOST_PLATFORM"] = cfg["host_platform"]
        |    sys.platform = cfg["sys_platform"]
        |    sys.implementation._multiarch = cfg["multiarch"]
        |    sys.cross_compiling = True
        |    sys.base_prefix = sys.base_exec_prefix = cfg["prefix"]
        |    real = os.uname()
        |    fake = os.uname_result((cfg["uname_system"], real.nodename, cfg["uname_release"], real.version, cfg["uname_machine"]))
        |    os.uname = lambda: fake
        |    level = cfg.get("android_api_level")
        |    if level:
        |        sys.getandroidapilevel = lambda: level
        |    if "sysconfig" in sys.modules:
        |        sc = sys.modules["sysconfig"]
        |        for name in ("_BASE_PREFIX", "_BASE_EXEC_PREFIX"):
        |            if hasattr(sc, name):
        |                setattr(sc, name, os.path.normpath(cfg["prefix"]))
        |        sc._CONFIG_VARS = None
        |        if hasattr(sc, "_CONFIG_VARS_INITIALIZED"):
        |            sc._CONFIG_VARS_INITIALIZED = False
        |
        |
        |_activate()
        |""".trimMargin()

    /**
     * `ppp-cross/pep517.py <mode> <source dir> <out>`: a standard-library PEP 517 frontend. `static` writes
     * `[build-system].requires` as JSON to the file `<out>`, `dynamic` the backend's
     * `get_requires_for_build_wheel` (the backend may print to stdout, hence a file), and `build` builds the
     * wheel into the directory `<out>` and prints `PPP_WHEEL=<name>`.
     */
    val PEP517 =
        """
        |# pypackpack PEP 517 frontend (docs/SPEC.md *Cross build environment*).
        |import importlib
        |import json
        |import os
        |import sys
        |import tomllib
        |
        |mode, src = sys.argv[1], os.path.abspath(sys.argv[2])
        |system = {}
        |pyproject = os.path.join(src, "pyproject.toml")
        |if os.path.isfile(pyproject):
        |    with open(pyproject, "rb") as f:
        |        system = tomllib.load(f).get("build-system", {})
        |
        |
        |def write(value):
        |    with open(sys.argv[3], "w", encoding="utf-8") as f:
        |        json.dump(value, f)
        |
        |
        |if mode == "static":
        |    write(system.get("requires", ["setuptools>=40.8.0"]))
        |    sys.exit(0)
        |for entry in reversed(system.get("backend-path", [])):
        |    sys.path.insert(0, os.path.join(src, entry))
        |os.chdir(src)
        |module, _, attrs = system.get("build-backend", "setuptools.build_meta:__legacy__").partition(":")
        |backend = importlib.import_module(module)
        |for attr in filter(None, attrs.split(".")):
        |    backend = getattr(backend, attr)
        |if mode == "dynamic":
        |    hook = getattr(backend, "get_requires_for_build_wheel", None)
        |    write(list(hook({})) if hook else [])
        |elif mode == "build":
        |    out = os.path.abspath(sys.argv[3])
        |    os.makedirs(out, exist_ok=True)
        |    print("PPP_WHEEL=" + backend.build_wheel(out, {}))
        |else:
        |    sys.exit("unknown mode " + mode)
        |""".trimMargin()
}
