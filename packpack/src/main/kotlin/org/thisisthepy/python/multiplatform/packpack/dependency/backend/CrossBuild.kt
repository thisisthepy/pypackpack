package org.thisisthepy.python.multiplatform.packpack.dependency.backend

import kotlinx.coroutines.runBlocking
import org.thisisthepy.python.multiplatform.packpack.compile.backend.external.ANDROID_API_LEVEL
import org.thisisthepy.python.multiplatform.packpack.compile.backend.external.ANDROID_DIRS
import org.thisisthepy.python.multiplatform.packpack.compile.backend.external.IOS_TARGETS
import org.thisisthepy.python.multiplatform.packpack.compile.backend.external.LINUX_CROSS_TARGETS
import org.thisisthepy.python.multiplatform.packpack.compile.backend.external.MesonCross
import org.thisisthepy.python.multiplatform.packpack.compile.extension.CompileProcessResult
import org.thisisthepy.python.multiplatform.packpack.compile.extension.runCompileProcess
import org.thisisthepy.python.multiplatform.packpack.utils.Platforms
import org.thisisthepy.python.multiplatform.packpack.utils.deleteWithoutFollowingLinks
import org.thisisthepy.python.multiplatform.packpack.utils.extractArchive
import org.thisisthepy.python.multiplatform.packpack.utils.toml.TomlEditor
import org.thisisthepy.python.multiplatform.packpack.utils.toml.TomlValue
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Duration

/**
 * How the cross environment's interpreter reports [canonicalTarget] (docs/SPEC.md *Cross build environment*):
 * [hostPlatform] (the wheel's platform, `_PYTHON_HOST_PLATFORM` and what the hook makes
 * `sysconfig.get_platform()` return), `sys.platform`, the multiarch and `os.uname()`. The hook answers
 * `get_platform()` itself because CPython versions read `_PYTHON_HOST_PLATFORM` differently: 3.13 returns it
 * as it is, 3.15 splits it and maps the Android machine again (runs 44980924111, 44989636124, 45003070103).
 */
data class CrossTargetIdentity(
    val hostPlatform: String,
    val sysPlatform: String,
    val multiarch: String,
    val unameSystem: String,
    val unameMachine: String,
    val androidApiLevel: Int? = null,
)

/** The identity for [canonicalTarget], or a failure for a target the cross environment does not build. */
fun crossTargetIdentity(canonicalTarget: String): CrossTargetIdentity {
    val arm = canonicalTarget.startsWith("aarch64") || canonicalTarget.startsWith("arm64")
    val machine = if (arm) "aarch64" else "x86_64"
    if (canonicalTarget in ANDROID_DIRS) {
        val abi = if (arm) "arm64_v8a" else "x86_64"
        return CrossTargetIdentity("android-$ANDROID_API_LEVEL-$abi", "android", canonicalTarget, "Linux", machine, ANDROID_API_LEVEL)
    }
    if (canonicalTarget in IOS_TARGETS) {
        val sdk = if (canonicalTarget.endsWith("-simulator")) "iphonesimulator" else "iphoneos"
        val arch = if (arm) "arm64" else "x86_64"
        return CrossTargetIdentity("ios-$IOS_MIN_VERSION-$arch-$sdk", "ios", "$arch-$sdk", "iOS", arch)
    }
    LINUX_CROSS_TARGETS[canonicalTarget]?.let { linux ->
        val cpu = linux.rustTriple.substringBefore('-')
        return CrossTargetIdentity("${linux.compatibility}_$cpu", "linux", "$cpu-linux-gnu", "Linux", cpu)
    }
    throw IllegalArgumentException("The cross build environment does not build for $canonicalTarget (Android, iOS and Linux cross targets only).")
}

/** The iOS deployment target of the wheels the environment builds (the `ios_13_0_*` tag uv installs). */
const val IOS_MIN_VERSION = "13.0"

/** PEP 503 normalization: `Foo_Bar.baz` and `foo-bar-baz` are the same distribution. */
fun normalizeDistributionName(name: String): String = name.trim().lowercase().replace(Regex("[-_.]+"), "-")

/** A recipe (`[tool.ppp.recipes.<name>]`): patches applied before the build and extra environment variables. */
data class CrossRecipe(
    val patches: List<File>,
    val env: Map<String, String>,
)

/** The keys a recipe has, in `[tool.ppp.recipes.<name>]` and in a recipe file alike. */
private val RECIPE_KEYS = listOf("patches", "env")

/** The spellings a recipe for [distribution] may be named by: the PEP 503 name, its `_` form and the name as uv printed it. */
private fun recipeNames(distribution: String): List<String> {
    val normalized = normalizeDistributionName(distribution)
    return listOf(normalized, normalized.replace('-', '_'), distribution.trim()).distinct().filter { !it.contains('.') }
}

/**
 * [distribution]'s recipe, or `null`. The package's own `pyproject.toml` is read first: the table
 * `[tool.ppp.recipes.<name>]`, with patch paths relative to [packageDir]. Then each of [recipeDirs] in order:
 * the file `<dir>/<name>.toml`, holding the same keys at its top level, with patch paths relative to that
 * directory (#168; the recipe-directory format toolchain's `pip { jit { localRecipes } }` points at, approved
 * by the leader on 2026-11-25). `<name>` is the PEP 503 name (`foo-bar`), its `_` spelling or the name as uv
 * printed it. A listed patch that does not exist, or a key a recipe does not have, fails here, before
 * anything is built.
 */
fun readCrossRecipe(
    packageDir: File,
    distribution: String,
    recipeDirs: List<File> = emptyList(),
): CrossRecipe? {
    val names = recipeNames(distribution)
    File(packageDir, "pyproject.toml").takeIf { it.isFile }?.let { pyproject ->
        val editor = TomlEditor(pyproject.readText())
        names.map { "tool.ppp.recipes.$it" }.firstOrNull { editor.hasTable(it) }?.let { table ->
            return parseRecipe(editor, table, packageDir, "[$table]")
        }
    }
    for (dir in recipeDirs) {
        val file = names.map { File(dir, "$it.toml") }.firstOrNull { it.isFile } ?: continue
        val text = file.readText()
        require(text.lines().none { Regex("""^\s*\[\[?[A-Za-z_"']""").containsMatchIn(it) }) {
            "Recipe ${file.path} has a table; a recipe file holds only top-level keys (${RECIPE_KEYS.joinToString()})."
        }
        // The keys sit at the file's top level; TomlEditor reads tables, so they are read as one.
        val editor = TomlEditor("[recipe]\n$text")
        val unknown = editor.getTableKeys("recipe").filter { it !in RECIPE_KEYS }
        require(unknown.isEmpty()) {
            "Recipe ${file.path} has ${unknown.joinToString { "'$it'" }}; a recipe's keys are ${RECIPE_KEYS.joinToString()}."
        }
        return parseRecipe(editor, "recipe", dir, "Recipe ${file.path}")
    }
    return null
}

/** The recipe in [table] of [editor], its patch paths resolved against [base]. */
private fun parseRecipe(
    editor: TomlEditor,
    table: String,
    base: File,
    where: String,
): CrossRecipe {
    val patches =
        when (val value = editor.getValue(table, "patches")) {
            null -> emptyList()
            is TomlValue.Array -> value.items.map { path ->
                File(base, path).also { require(it.isFile) { "$where: patch not found: ${it.path}" } }
            }
            else -> throw IllegalArgumentException("$where: patches must be an array of paths.")
        }
    val env =
        when (val value = editor.getValue(table, "env")) {
            null -> emptyMap()
            is TomlValue.InlineTable -> value.pairs.mapValues { (name, v) ->
                (v as? TomlValue.String)?.value ?: throw IllegalArgumentException("$where: env.$name must be a string.")
            }
            else -> throw IllegalArgumentException("$where: env must be an inline table of strings.")
        }
    return CrossRecipe(patches, env)
}

/** An sdist on PyPI: where it is and its SHA-256. */
data class SdistSource(
    val fileName: String,
    val url: String,
    val sha256: String,
)

/** The `sdist` entry of a PyPI JSON API response (`/pypi/<name>/<version>/json`), or a failure naming the package. */
fun sdistFromPyPIJson(
    json: String,
    packageSpec: String,
): SdistSource {
    val root = MiniJson.parse(json) as? Map<*, *> ?: throw IllegalStateException("PyPI's answer for $packageSpec is not a JSON object.")
    val urls = root["urls"] as? List<*> ?: emptyList<Any?>()
    val sdist = urls.filterIsInstance<Map<*, *>>().firstOrNull { it["packagetype"] == "sdist" }
        ?: throw IllegalStateException("PyPI lists no sdist for $packageSpec.")
    val sha = (sdist["digests"] as? Map<*, *>)?.get("sha256") as? String
        ?: throw IllegalStateException("PyPI gives no sha256 for $packageSpec's sdist.")
    return SdistSource(sdist["filename"] as? String ?: "$packageSpec.tar.gz", sdist["url"] as String, sha)
}

/**
 * Builds sdists for a cross target in pypackpack's own cross environment (docs/SPEC.md *Cross build
 * environment*). Every directory comes from the caller (AGENTS.md rule 13). The seams run processes, fetch
 * URLs, install the host CPython and pick the target toolchain.
 */
open class CrossSdistBuilder(
    private val run: (command: List<String>, workingDir: File, env: Map<String, String>) -> CompileProcessResult = ::runCompileProcess,
    private val fetch: (url: String) -> ByteArray = ::httpGet,
    private val installHostPython: (version: String, target: String, dir: File) -> Result<Unit> = { version, target, dir ->
        runBlocking { UVBackend().installPython(version, target, dir) }.map { }
    },
    private val crossFor: (packageDir: File, canonicalTarget: String) -> MesonCross = { dir, target ->
        MesonCross.forTarget(dir, target, Platforms.detectHostTarget())
            ?: throw IllegalArgumentException("$target is this machine's own target; uv builds its sdists itself.")
    },
    private val hostTarget: () -> String = Platforms::detectHostTarget,
    private val pythonVersion: String = "3.15t",
) {
    /** `<package>/build/packpack/cross/<target>`. */
    fun crossDir(packageDir: File, canonicalTarget: String): File = File(packageDir, "build/packpack/cross/$canonicalTarget")

    /** The `--find-links` directory: the wheels built so far for [canonicalTarget]. */
    fun wheelsDir(packageDir: File, canonicalTarget: String): File = File(crossDir(packageDir, canonicalTarget), "wheels")

    /**
     * Fetches `name==version` from PyPI, builds it for [canonicalTarget] and returns the wheel. Its recipe comes
     * from [packageDir]'s `pyproject.toml` or [recipeDirs] ([readCrossRecipe]).
     */
    open fun buildFromPyPI(
        packageDir: File,
        canonicalTarget: String,
        packageSpec: String,
        recipeDirs: List<File> = emptyList(),
    ): Result<File> =
        runCatching {
            val (name, version) = packageSpec.split("==", limit = 2).map { it.trim() }.takeIf { it.size == 2 && it.all(String::isNotEmpty) }
                ?: throw IllegalArgumentException("Expected name==version, got '$packageSpec'.")
            val source = sdistFromPyPIJson(String(fetch("https://pypi.org/pypi/$name/$version/json")), packageSpec)
            val dir = File(crossDir(packageDir, canonicalTarget), "sdists").apply { mkdirs() }
            val sdist = File(dir, source.fileName)
            if (!sdist.isFile || sha256(sdist) != source.sha256) {
                val bytes = fetch(source.url)
                val actual = sha256(bytes)
                check(actual == source.sha256) { "The sdist of $packageSpec has SHA-256 $actual, PyPI says ${source.sha256}." }
                sdist.writeBytes(bytes)
            }
            buildSdist(packageDir, canonicalTarget, sdist, name, recipeDirs).getOrThrow()
        }

    /**
     * Unpacks [sdist] (a `.tar.gz` or `.zip`), applies [distribution]'s recipe and builds it for [canonicalTarget].
     * The unpacked tree is deleted once the wheel is built (a Rust crate's `target/` reaches hundreds of MB, #170)
     * and kept when the build fails, to read what went wrong; the next build unpacks afresh either way.
     */
    fun buildSdist(
        packageDir: File,
        canonicalTarget: String,
        sdist: File,
        distribution: String,
        recipeDirs: List<File> = emptyList(),
    ): Result<File> =
        runCatching {
            val recipe = readCrossRecipe(packageDir, distribution, recipeDirs)
            val src = File(crossDir(packageDir, canonicalTarget), "src/${sdist.name.removeSuffix(".tar.gz").removeSuffix(".zip")}")
            src.deleteWithoutFollowingLinks()
            src.mkdirs()
            extractArchive(sdist, src, stripComponents = 1)
            recipe?.patches?.forEach { patch ->
                val result = run(listOf("patch", "-p1", "--forward", "--batch", "-i", patch.absolutePath), src, emptyMap())
                check(result.exitCode == 0) { "Recipe patch ${patch.path} does not apply to $distribution:\n${result.output}" }
            }
            buildSource(packageDir, canonicalTarget, src, recipe?.env.orEmpty()).getOrThrow()
                .also { src.deleteWithoutFollowingLinks() }
        }

    /** Builds the source tree [src] into a wheel for [canonicalTarget], in the target's wheels directory. */
    fun buildSource(
        packageDir: File,
        canonicalTarget: String,
        src: File,
        extraEnv: Map<String, String> = emptyMap(),
    ): Result<File> =
        runCatching {
            val cross = crossFor(packageDir, canonicalTarget)
            val env = prepareEnvironment(packageDir, canonicalTarget, cross)
            val python = env.interpreter.absolutePath
            val scratch = File(crossDir(packageDir, canonicalTarget), "requires.json")

            // Host mode: the build requirements run on this machine, so they are the host's wheels.
            exec(listOf(python, env.frontend.absolutePath, "static", src.absolutePath, scratch.absolutePath), src, emptyMap(), "read [build-system]")
            installRequirements(env, MiniJson.parseStringList(scratch.readText()), src)
            exec(listOf(python, env.frontend.absolutePath, "backend", src.absolutePath, scratch.absolutePath), src, emptyMap(), "read the build backend")
            val backendName = MiniJson.parseStringList(scratch.readText()).single()
            val backendEnv = if (isMaturin(backendName)) maturinBuildEnv(env, cross, canonicalTarget) else emptyMap()
            val settings = backendSettings(backendName, env, cross, canonicalTarget)
            // The backend's own requirements depend on the settings (scikit-build-core asks for CMake when the
            // build host's is older than cmake.version).
            exec(
                listOf(python, env.frontend.absolutePath, "dynamic", src.absolutePath, scratch.absolutePath) + listOfNotNull(settings?.absolutePath),
                src,
                emptyMap(),
                "ask the backend for its requirements",
            )
            installRequirements(env, MiniJson.parseStringList(scratch.readText()), src)

            // Cross mode: the backend sees the target and compiles with its toolchain.
            val out = wheelsDir(packageDir, canonicalTarget).apply { mkdirs() }
            val output = exec(
                listOf(python, env.frontend.absolutePath, "build", src.absolutePath, out.absolutePath) + listOfNotNull(settings?.absolutePath),
                src,
                crossBuildEnv(env, cross) + backendEnv + extraEnv,
                "build ${src.name} for $canonicalTarget",
            )
            val wheel = output.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("PPP_WHEEL=") }?.removePrefix("PPP_WHEEL=")
                ?: throw IllegalStateException("The build of ${src.name} named no wheel:\n$output")
            File(out, wheel).also { check(it.isFile) { "The build of ${src.name} reported $wheel, which does not exist." } }
        }

    /** The environment's files. */
    data class Environment(
        val dir: File,
        val interpreter: File,
        val frontend: File,
        val config: File,
    )

    /**
     * Creates (or refreshes) `build/packpack/cross/<target>/env`: a host venv with the target's synthesized
     * prefix, its `_sysconfigdata`, the config and the hook. The host CPython is installed once per package.
     */
    internal fun prepareEnvironment(
        packageDir: File,
        canonicalTarget: String,
        cross: MesonCross,
    ): Environment {
        val identity = crossTargetIdentity(canonicalTarget)
        val base = crossDir(packageDir, canonicalTarget)
        val envDir = File(base, "env")
        val interpreter = venvInterpreter(envDir)
        if (!interpreter.exists()) {
            val hostPython = hostInterpreter(packageDir)
            exec(listOf("uv", "venv", "--python", hostPython.absolutePath, envDir.absolutePath), packageDir, emptyMap(), "create the cross environment")
        }
        // The venv's own site-packages only (lib/python3.Xt/site-packages, or Lib/site-packages on Windows),
        // never one reached through the target prefix's links.
        val sitePackages = (File(envDir, "lib").listFiles().orEmpty().map { File(it, "site-packages") } + File(envDir, "Lib/site-packages"))
            .firstOrNull { it.isDirectory }
            ?: throw IllegalStateException("The cross environment ${envDir.path} has no site-packages.")
        val crossFiles = File(envDir, "ppp-cross").apply { mkdirs() }

        val target = cross.target
        val prefix = File(crossFiles, "prefix")
        prefix.deleteWithoutFollowingLinks()
        File(prefix, "include").mkdirs()
        Files.createSymbolicLink(File(prefix, "include/python${pythonVersion.removeSuffix("t")}t").toPath(), target.includeDir.absoluteFile.toPath())
        val libDir = target.libDir ?: target.includeDir.parentFile.parentFile.let { File(it, "lib") }
        Files.createSymbolicLink(File(prefix, "lib").toPath(), libDir.absoluteFile.toPath())

        // An iOS simulator slice holds lib-arm64 and lib-x86_64: take the target architecture's sysconfig data.
        val sysconfigRoot = IOS_TARGETS[canonicalTarget]?.let { File(libDir, it.libDir) } ?: libDir
        val sysconfigData = findSysconfigData(sysconfigRoot)
            ?: throw IllegalStateException("The CPython for $canonicalTarget in ${sysconfigRoot.path} has no _sysconfigdata_*.py.")
        val sysconfigDir = File(crossFiles, "sysconfig").apply { deleteWithoutFollowingLinks(); mkdirs() }
        sysconfigData.copyTo(File(sysconfigDir, sysconfigData.name), overwrite = true)

        val config = File(crossFiles, "config.json")
        config.writeText(
            MiniJson.write(
                linkedMapOf(
                    "sysconfig_dir" to sysconfigDir.absolutePath,
                    "sysconfigdata_name" to sysconfigData.name.removeSuffix(".py"),
                    "host_platform" to identity.hostPlatform,
                    "sys_platform" to identity.sysPlatform,
                    "multiarch" to identity.multiarch,
                    "prefix" to prefix.absolutePath,
                    "uname_system" to identity.unameSystem,
                    "uname_release" to if (canonicalTarget in IOS_TARGETS) IOS_MIN_VERSION else "",
                    "uname_machine" to identity.unameMachine,
                    "android_api_level" to identity.androidApiLevel,
                ),
            ),
        )
        File(sitePackages, "_ppp_cross.pth").writeText(CrossBuildScripts.PTH)
        File(sitePackages, "_ppp_cross.py").writeText(CrossBuildScripts.HOOK)
        val frontend = File(crossFiles, "pep517.py").apply { writeText(CrossBuildScripts.PEP517) }
        return Environment(envDir, interpreter, frontend, config)
    }

    /**
     * The PEP 517 config settings a backend needs for a cross build, written next to the config, or `null`
     * (setuptools and other backends that read `sysconfig` and `CC`).
     * - meson-python: the Meson cross build's cross file, with `python` set to the environment's interpreter
     *   (which reports the target in cross mode) and the target's link arguments, through `setup-args`.
     * - scikit-build-core: a CMake toolchain file (`cmake.define.CMAKE_TOOLCHAIN_FILE`) naming the target
     *   system and processor and the target compilers, each behind a one-line wrapper script because CMake
     *   takes a single executable. scikit-build-core finds Python through the interpreter, which reports the
     *   target's headers and `SOABI` in cross mode; `LDFLAGS` carries the link arguments.
     */
    internal fun backendSettings(
        backend: String,
        env: Environment,
        cross: MesonCross,
        canonicalTarget: String,
    ): File? {
        val module = backend.substringBefore(':').trim()
        val dir = env.config.parentFile
        fun quote(text: String) = "'" + text.replace("\\", "\\\\").replace("'", "\\'") + "'"
        val settings: Map<String, Any> =
            when {
                module == "mesonpy" || module.startsWith("mesonpy.") -> {
                    val crossFile = File(dir, "meson-cross.ini")
                    val linkArgs = cross.target.linkArgs.joinToString(", ", "[", "]") { quote(it) }
                    crossFile.writeText(
                        cross.crossFileText().replaceFirst("[binaries]\n", "[binaries]\npython = ${quote(env.interpreter.absolutePath)}\n") +
                            "\n[built-in options]\nc_link_args = $linkArgs\ncpp_link_args = $linkArgs\n",
                    )
                    mapOf("setup-args" to listOf("--cross-file=${crossFile.absolutePath}"))
                }
                module == "scikit_build_core" || module.startsWith("scikit_build_core.") -> {
                    val toolchain = File(dir, "cmake-toolchain.cmake")
                    val cc = wrapper(File(dir, "bin/cc"), cross.c)
                    val cxx = wrapper(File(dir, "bin/c++"), cross.cpp)
                    val ar = wrapper(File(dir, "bin/ar"), cross.ar)
                    // FindPython runs the interpreter while cross-compiling only through CMAKE_CROSSCOMPILING_EMULATOR
                    // under policy CMP0190 NEW (CMake 4.1+; without it CMake 4 reports Interpreter missing and 3.x leaves
                    // Python_SOABI empty, run 45003070103). So the build asks for CMake 4.1+ (scikit-build-core installs
                    // it when the host's is older), sets the policy before the project's cmake_minimum_required, and
                    // the emulator runs the environment's interpreter, which answers for the target through the hook.
                    val envBin = env.interpreter.parentFile.absolutePath
                    val emulator = File(dir, "bin/emulator").apply {
                        writeText(
                            "#!/bin/sh\n" +
                                "case \"\$1\" in ${shellWord(envBin)}/*) exec \"\$@\" ;; esac\n" +
                                "echo \"pypackpack cross build: \$1 is a $canonicalTarget program, it cannot run on the build host\" >&2\n" +
                                "exit 1\n",
                        )
                        setExecutable(true)
                    }
                    val identity = crossTargetIdentity(canonicalTarget)
                    val system = if (identity.sysPlatform == "ios") "iOS" else "Linux"
                    toolchain.writeText(
                        buildString {
                            appendLine("# pypackpack cross build environment (docs/SPEC.md *Cross build environment*), $canonicalTarget")
                            appendLine("set(CMAKE_SYSTEM_NAME $system)")
                            appendLine("set(CMAKE_SYSTEM_PROCESSOR ${identity.unameMachine})")
                            appendLine("set(CMAKE_C_COMPILER \"${cc.absolutePath}\")")
                            appendLine("set(CMAKE_CXX_COMPILER \"${cxx.absolutePath}\")")
                            appendLine("set(CMAKE_AR \"${ar.absolutePath}\" CACHE FILEPATH \"\")")
                            appendLine("set(CMAKE_CROSSCOMPILING_EMULATOR \"${emulator.absolutePath}\")")
                            if (system == "iOS") {
                                appendLine("set(CMAKE_OSX_SYSROOT ${if (canonicalTarget.endsWith("-simulator")) "iphonesimulator" else "iphoneos"})")
                                appendLine("set(CMAKE_OSX_ARCHITECTURES ${identity.unameMachine})")
                                appendLine("set(CMAKE_OSX_DEPLOYMENT_TARGET $IOS_MIN_VERSION)")
                            }
                        },
                    )
                    mapOf(
                        "cmake.version" to ">=4.1",
                        "cmake.define.CMAKE_POLICY_DEFAULT_CMP0190" to "NEW",
                        "cmake.define.CMAKE_TOOLCHAIN_FILE" to toolchain.absolutePath,
                    )
                }
                else -> return null
            }
        return File(dir, "settings.json").apply { writeText(MiniJson.write(settings)) }
    }

    /**
     * The variables a `maturin` sdist needs to cross-compile for an Android [canonicalTarget] (#168), as the Cargo
     * backend's Android path sets them (`compile/backend/external/Cargo.kt`):
     * - `MATURIN_PEP517_ARGS`: `--target <rust triple>` (pypackpack's Android triples are Rust's).
     * - `PYO3_CROSS_LIB_DIR` / `PYO3_CROSS_PYTHON_VERSION` / `PYO3_CROSS_PYTHON_IMPLEMENTATION`: the target CPython,
     *   whose `_sysconfigdata` tells PyO3 the ABI (free-threaded for 3.15t).
     * - Cargo's linker and the `cc` crate's `CC_` / `CXX_` / `AR_` for the target: the target compilers, each behind
     *   a one-line wrapper, because Cargo and `cc` take one executable.
     * - `CARGO_TARGET_<TRIPLE>_RUSTFLAGS`: the target's link arguments (Android links libpython), one
     *   `-C link-arg=` each.
     *
     * Other cross targets are refused naming the target: their maturin cross builds are not wired here.
     */
    internal fun maturinBuildEnv(
        env: Environment,
        cross: MesonCross,
        canonicalTarget: String,
    ): Map<String, String> {
        require(canonicalTarget in ANDROID_RUST_TARGETS) {
            "pypackpack cross-builds maturin (Rust) sdists for Android only (${ANDROID_RUST_TARGETS.joinToString()}); " +
                "$canonicalTarget is not one. Build that package for $canonicalTarget elsewhere and install its wheel."
        }
        val dir = env.config.parentFile
        val cc = wrapper(File(dir, "bin/cc"), cross.c)
        val cxx = wrapper(File(dir, "bin/c++"), cross.cpp)
        val ar = wrapper(File(dir, "bin/ar"), cross.ar)
        val lib = cross.target.libDir ?: throw IllegalStateException("The CPython for $canonicalTarget names no library directory.")
        val key = canonicalTarget.replace('-', '_')
        return linkedMapOf(
            "MATURIN_PEP517_ARGS" to "--target $canonicalTarget",
            "PYO3_CROSS_LIB_DIR" to lib.absolutePath,
            "PYO3_CROSS_PYTHON_VERSION" to pythonVersion.removeSuffix("t"),
            "PYO3_CROSS_PYTHON_IMPLEMENTATION" to "CPython",
            "CARGO_TARGET_${key.uppercase()}_LINKER" to cc.absolutePath,
            "CC_$key" to cc.absolutePath,
            "CXX_$key" to cxx.absolutePath,
            "AR_$key" to ar.absolutePath,
            "CARGO_TARGET_${key.uppercase()}_RUSTFLAGS" to cross.target.linkArgs.joinToString(" ") { "-C link-arg=$it" },
        )
    }

    /** A `#!/bin/sh` script at [file] that runs [command] with its own arguments appended. */
    private fun wrapper(
        file: File,
        command: List<String>,
    ): File =
        file.apply {
            parentFile.mkdirs()
            writeText("#!/bin/sh\nexec ${command.joinToString(" ") { shellWord(it) }} \"\$@\"\n")
            setExecutable(true)
        }

    /**
     * The cross-mode environment variables: the environment's `bin` first on `PATH`, the config and the target
     * toolchain. The frontend runs the environment's interpreter without activating it, so the build requirements'
     * programs (Cython, meson, ninja, maturin's own binary) would otherwise be whatever the machine has first on
     * `PATH`: a Homebrew Cython 0.29 made meson reject numpy's `cython` ("cannot compile programs"), and maturin's
     * hooks found no `maturin` at all (#168). pypa's `build` puts its environment's scripts first the same way.
     */
    internal fun crossBuildEnv(
        env: Environment,
        cross: MesonCross,
    ): Map<String, String> {
        val cc = cross.c.joinToString(" ") { shellWord(it) }
        return linkedMapOf(
            "PATH" to env.interpreter.parentFile.absolutePath + File.pathSeparator + System.getenv("PATH").orEmpty(),
            "PPP_CROSS_CONFIG" to env.config.absolutePath,
            "CC" to cc,
            "CXX" to cross.cpp.joinToString(" ") { shellWord(it) },
            "AR" to cross.ar.joinToString(" ") { shellWord(it) },
            "LDSHARED" to "$cc -shared",
            "LDFLAGS" to cross.target.linkArgs.joinToString(" ") { shellWord(it) },
        )
    }

    private fun installRequirements(
        env: Environment,
        requirements: List<String>,
        workingDir: File,
    ) {
        if (requirements.isEmpty()) return
        exec(listOf("uv", "pip", "install", "--python", env.interpreter.absolutePath) + requirements, workingDir, emptyMap(), "install the build requirements $requirements")
    }

    private fun hostInterpreter(packageDir: File): File {
        val host = hostTarget()
        val dir = File(packageDir, "build/packpack/cross/host-python")
        findInterpreter(dir)?.let { return it }
        dir.deleteWithoutFollowingLinks()
        dir.mkdirs()
        installHostPython(pythonVersion, host, dir).getOrElse {
            throw IllegalStateException("Could not install the host CPython $pythonVersion for the cross environment: ${it.message}", it)
        }
        return findInterpreter(dir) ?: throw IllegalStateException("The host CPython in ${dir.path} has no interpreter.")
    }

    private fun exec(
        command: List<String>,
        workingDir: File,
        env: Map<String, String>,
        what: String,
    ): String {
        val result = run(command, workingDir, env)
        check(result.exitCode == 0) { "Could not $what (exit ${result.exitCode}): ${command.joinToString(" ")}\n${result.output}" }
        return result.output
    }

    internal companion object {
        /** The Android targets a `maturin` sdist is cross-built for; pypackpack's triples are Rust's here. */
        val ANDROID_RUST_TARGETS = listOf("aarch64-linux-android", "x86_64-linux-android")

        /** Whether [backend] (`build-backend`) is maturin's PEP 517 backend. */
        fun isMaturin(backend: String): Boolean = backend.substringBefore(':').trim().let { it == "maturin" || it.startsWith("maturin.") }

        fun venvInterpreter(envDir: File): File =
            if (System.getProperty("os.name").lowercase().contains("win")) File(envDir, "Scripts/python.exe") else File(envDir, "bin/python")

        /** `bin/python3.Xt` / `bin/python3` (or `python.exe`) of a CPython tree. */
        fun findInterpreter(dir: File): File? =
            dir.takeIf { it.isDirectory }?.walkTopDown()?.maxDepth(3)
                ?.filter { (it.isFile || Files.isSymbolicLink(it.toPath())) && (it.name == "python.exe" || (it.parentFile.name == "bin" && Regex("""python3(\.\d+t?)?""").matches(it.name))) }
                ?.minByOrNull { it.path.length }

        fun findSysconfigData(libDir: File): File? =
            libDir.walkTopDown().maxDepth(3).firstOrNull { it.isFile && it.name.startsWith("_sysconfigdata") && it.name.endsWith(".py") }

        /** Quotes [word] for `shlex.split`, which setuptools applies to `CC` and friends. */
        fun shellWord(word: String): String = if (word.isNotEmpty() && word.all { it.isLetterOrDigit() || it in "-_./=+:,@%" }) word else "'" + word.replace("'", "'\\''") + "'"
    }
}

private fun sha256(file: File): String = sha256(file.readBytes())

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private fun httpGet(url: String): ByteArray {
    val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(30)).build()
    val response = client.send(HttpRequest.newBuilder(URI(url)).timeout(Duration.ofMinutes(5)).GET().build(), HttpResponse.BodyHandlers.ofByteArray())
    check(response.statusCode() == 200) { "GET $url answered ${response.statusCode()}." }
    return response.body()
}

/** The little JSON the cross build reads and writes: PyPI's answer, the frontend's lists and the config. */
internal object MiniJson {
    fun parse(text: String): Any? = Parser(text).run { value().also { skip(); require(i == s.length) { "Trailing data in JSON at $i" } } }

    fun parseStringList(text: String): List<String> =
        (parse(text) as? List<*>)?.map { it as? String ?: throw IllegalArgumentException("Expected a list of strings: $text") }
            ?: throw IllegalArgumentException("Expected a JSON list: $text")

    fun write(value: Any?): String =
        when (value) {
            null -> "null"
            is String -> buildString {
                append('"')
                value.forEach { c ->
                    when {
                        c == '"' -> append("\\\"")
                        c == '\\' -> append("\\\\")
                        c == '\n' -> append("\\n")
                        c < ' ' -> append("\\u%04x".format(c.code))
                        else -> append(c)
                    }
                }
                append('"')
            }
            is Number, is Boolean -> value.toString()
            is Map<*, *> -> value.entries.joinToString(", ", "{", "}") { (k, v) -> write(k.toString()) + ": " + write(v) }
            is List<*> -> value.joinToString(", ", "[", "]") { write(it) }
            else -> throw IllegalArgumentException("Cannot write ${value::class} as JSON")
        }

    private class Parser(val s: String) {
        var i = 0

        fun skip() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            skip()
            require(i < s.length) { "Unexpected end of JSON" }
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> num()
            }
        }

        private fun literal(word: String, v: Any?): Any? {
            require(s.startsWith(word, i)) { "Bad JSON literal at $i" }
            i += word.length
            return v
        }

        private fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++
            skip()
            if (s[i] == '}') return m.also { i++ }
            while (true) {
                skip()
                val k = str()
                skip()
                require(s[i] == ':') { "Expected ':' in JSON at $i" }
                i++
                m[k] = value()
                skip()
                when (s[i++]) {
                    ',' -> continue
                    '}' -> return m
                    else -> throw IllegalArgumentException("Expected ',' or '}' in JSON at ${i - 1}")
                }
            }
        }

        private fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++
            skip()
            if (s[i] == ']') return l.also { i++ }
            while (true) {
                l += value()
                skip()
                when (s[i++]) {
                    ',' -> continue
                    ']' -> return l
                    else -> throw IllegalArgumentException("Expected ',' or ']' in JSON at ${i - 1}")
                }
            }
        }

        private fun str(): String {
            require(s[i] == '"') { "Expected a JSON string at $i" }
            i++
            val b = StringBuilder()
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return b.toString()
                    '\\' -> {
                        when (val e = s[i++]) {
                            'n' -> b.append('\n')
                            't' -> b.append('\t')
                            'r' -> b.append('\r')
                            'b' -> b.append('\b')
                            'f' -> b.append('\u000c')
                            'u' -> b.append(s.substring(i, i + 4).toInt(16).toChar()).also { i += 4 }
                            else -> b.append(e)
                        }
                    }
                    else -> b.append(c)
                }
            }
        }

        private fun num(): Number {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            val t = s.substring(start, i)
            require(t.isNotEmpty()) { "Bad JSON value at $start" }
            return t.toLongOrNull() ?: t.toDouble()
        }
    }
}
