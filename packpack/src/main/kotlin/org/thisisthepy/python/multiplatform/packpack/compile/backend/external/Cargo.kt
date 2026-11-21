package org.thisisthepy.python.multiplatform.packpack.compile.backend.external

import org.thisisthepy.python.multiplatform.packpack.utils.deleteWithoutFollowingLinks
import org.thisisthepy.python.multiplatform.packpack.utils.Platforms
import org.thisisthepy.python.multiplatform.packpack.utils.findVenvInterpreter
import org.thisisthepy.python.multiplatform.packpack.utils.toml.TomlEditor
import org.thisisthepy.python.multiplatform.packpack.utils.toml.TomlValue
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/** The maturin `uv tool run` runs; pinned, so a build does not change under a new release. */
const val MATURIN_VERSION = "1.15.0"

/** PyO3's stable ABI for free-threaded CPython 3.15+ (PEP 803), the default (`docs/SPEC.md` Scope decisions). */
const val PYO3_ABI3T_FEATURE = "pyo3/abi3t-py315"

/**
 * The PyO3 ABI mode a Rust crate is built for (`docs/SPEC.md` *Rust extension modules (Cargo backend)*, #135):
 * - [Abi3t]: PEP 803 free-threaded stable ABI (`abi3t-py315`, shorthand `abi3t`), the default.
 * - [Abi3]: PEP 384 stable ABI (`abi3-py3<minor>`, for example `abi3-py313`).
 * - [VersionSpecific]: version-pinned CPython ABI (`cp315t`, `cp3<minor>t`, `cp3<minor>`, `version`, or `cpython`),
 *   with no `pyo3/abi3*` feature flag.
 */
sealed class PyO3Abi {
    abstract val id: String
    abstract val pyo3Feature: String?
    abstract val crossPythonVersion: String
    abstract val iosTargetAbi: String?
    abstract val iosDefaultSuffix: String

    data class Abi3t(val minor: Int = 15) : PyO3Abi() {
        override val id: String = "abi3t-py3$minor"
        override val pyo3Feature: String = "pyo3/abi3t-py3$minor"
        override val crossPythonVersion: String = "3.$minor"
        override val iosTargetAbi: String = "CPython-abi3t-3.$minor"
        override val iosDefaultSuffix: String = ".abi3t.so"
    }

    data class Abi3(val minor: Int = 13) : PyO3Abi() {
        override val id: String = "abi3-py3$minor"
        override val pyo3Feature: String = "pyo3/abi3-py3$minor"
        override val crossPythonVersion: String = "3.$minor"
        override val iosTargetAbi: String = "CPython-abi3-3.$minor"
        override val iosDefaultSuffix: String = ".abi3.so"
    }

    data class VersionSpecific(val minor: Int = 15, val freeThreaded: Boolean = true) : PyO3Abi() {
        override val id: String = "cp3$minor${if (freeThreaded) "t" else ""}"
        override val pyo3Feature: String? = null
        override val crossPythonVersion: String = "3.$minor"
        override val iosTargetAbi: String? = null
        override val iosDefaultSuffix: String = ".cpython-3$minor${if (freeThreaded) "t" else ""}-darwin.so"
    }

    companion object {
        val DEFAULT: PyO3Abi = Abi3t(15)
        private val ABI3T_REGEX = Regex("""^abi3t(?:-py3(\d+))?$""")
        private val ABI3_REGEX = Regex("""^abi3-py3(\d+)$""")
        private val CP_REGEX = Regex("""^cp3(\d+)(t?)$""")

        fun parse(raw: String): PyO3Abi {
            val text = raw.trim()
            ABI3T_REGEX.matchEntire(text)?.let { m ->
                val minor = m.groupValues[1].ifEmpty { "15" }.toInt()
                require(minor >= 15) { "[tool.ppp.rust]: abi3t requires Python 3.15 or later, got '$raw'." }
                return Abi3t(minor)
            }
            ABI3_REGEX.matchEntire(text)?.let { m ->
                val minor = m.groupValues[1].toInt()
                require(minor >= 7) { "[tool.ppp.rust]: invalid abi3 floor in '$raw'." }
                return Abi3(minor)
            }
            CP_REGEX.matchEntire(text)?.let { m ->
                val minor = m.groupValues[1].toInt()
                val ft = m.groupValues[2] == "t"
                return VersionSpecific(minor, ft)
            }
            if (text == "version" || text == "cpython") {
                return VersionSpecific(15, true)
            }
            throw IllegalArgumentException(
                "[tool.ppp.rust]: unsupported abi '$raw'. Expected 'abi3t-py315' (or 'abi3t'), 'abi3-py3<minor>' (for example 'abi3-py313'), or 'cp3<minor>[t]' (for example 'cp315t').",
            )
        }
    }
}

/** A Rust crate that builds the CPython extension module [module]. */
data class RustCrate(
    val module: String,
    val dir: File,
    val abi: PyO3Abi = PyO3Abi.DEFAULT,
    val features: List<String> = emptyList(),
    val noDefaultFeatures: Boolean = false,
) {
    /**
     * The combined `--features` list passed to maturin or cargo: the ABI's PyO3 feature (when any)
     * followed by [features], deduplicated in order.
     */
    val cargoFeatures: List<String>
        get() = buildList {
            abi.pyo3Feature?.let(::add)
            features.forEach { if (it.isNotBlank() && it !in this) add(it) }
        }
}

/**
 * Which crates a package builds (`docs/SPEC.md` *Rust extension modules (Cargo backend)*, #92, #135):
 * - by convention, a directory holding a `Cargo.toml` inside a Python package is that package's module
 *   (`src/main/core/_fast` is `core._fast`), under the first of `[tool.ppp].python-dir`, `src/main`,
 *   `src`, `python`, `<package>/python` and the package root that holds a Python package (Meson's rule);
 * - `[tool.ppp.rust]` in the package's `pyproject.toml` maps a module to a crate directory relative to the
 *   package (string path or inline table `{ path = ..., abi = ..., features = [...], default-features = false }`),
 *   for crates outside the Python packages (torchnative's `"torch._C" = "rust/torch_c"`), and may also set
 *   package-level defaults (`abi`, `features`, `default-features`, `no-default-features`).
 * A crate in neither place is not built. One module named both ways is an error.
 */
object RustCrates {
    private val MODULE = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")
    private val SKIPPED = setOf("__pycache__", "build", "dist", "target", "node_modules")
    private val OPTION_KEYS = setOf("abi", "features", "default-features", "no-default-features")
    private val ENTRY_OPTION_KEYS = setOf("path", "abi", "features", "default-features", "no-default-features")

    fun discover(packageDir: File): List<RustCrate> {
        val pkg = packageDir.canonicalFile
        val editor = File(pkg, "pyproject.toml").takeIf { it.isFile }?.let { TomlEditor(it.readText()) }
        val defaultAbi = editor?.getValue("tool.ppp.rust", "abi")?.let { value ->
            (value as? TomlValue.String)?.value?.let(PyO3Abi::parse)
                ?: throw IllegalArgumentException("[tool.ppp.rust]: 'abi' must be a string.")
        } ?: PyO3Abi.DEFAULT
        val defaultFeatures = editor?.getValue("tool.ppp.rust", "features")?.let { value ->
            (value as? TomlValue.Array)?.items
                ?: throw IllegalArgumentException("[tool.ppp.rust]: 'features' must be an array of strings.")
        } ?: emptyList()
        val defaultNoDefaultFeatures = resolveNoDefaultFeatures(
            defaultFeaturesVal = editor?.getValue("tool.ppp.rust", "default-features"),
            noDefaultFeaturesVal = editor?.getValue("tool.ppp.rust", "no-default-features"),
            context = "",
        )

        val convention = conventionCrates(pkg, defaultAbi, defaultFeatures, defaultNoDefaultFeatures)
        val table = tableCrates(pkg, editor, defaultAbi, defaultFeatures, defaultNoDefaultFeatures)
        val both = convention.map { it.module }.intersect(table.map { it.module }.toSet())
        require(both.isEmpty()) {
            "Rust module ${both.joinToString()} is declared both by a Cargo.toml inside its package and in " +
                "[tool.ppp.rust]; keep one."
        }
        return (convention + table).sortedBy { it.module }
    }

    private fun resolveNoDefaultFeatures(
        defaultFeaturesVal: TomlValue?,
        noDefaultFeaturesVal: TomlValue?,
        context: String,
        fallback: Boolean = false,
    ): Boolean {
        val defaultFeatures = defaultFeaturesVal?.let {
            (it as? TomlValue.Boolean)?.value
                ?: throw IllegalArgumentException("[tool.ppp.rust]: 'default-features'$context must be a boolean.")
        }
        val noDefaultFeatures = noDefaultFeaturesVal?.let {
            (it as? TomlValue.Boolean)?.value
                ?: throw IllegalArgumentException("[tool.ppp.rust]: 'no-default-features'$context must be a boolean.")
        }
        if (defaultFeatures != null && noDefaultFeatures != null && defaultFeatures == noDefaultFeatures) {
            throw IllegalArgumentException(
                "[tool.ppp.rust]: conflicting 'default-features' and 'no-default-features'$context.",
            )
        }
        return noDefaultFeatures ?: defaultFeatures?.let { !it } ?: fallback
    }

    private fun conventionCrates(
        pkg: File,
        defaultAbi: PyO3Abi,
        defaultFeatures: List<String>,
        defaultNoDefaultFeatures: Boolean,
    ): List<RustCrate> {
        val root = Meson.resolvePythonSourceRoot(pkg) ?: return emptyList()
        val found = mutableListOf<RustCrate>()
        fun visit(dir: File) {
            dir.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith(".") && it.name !in SKIPPED }
                .sortedBy { it.name }
                .forEach { child ->
                    when {
                        File(child, "Cargo.toml").isFile ->
                            found += RustCrate(
                                module = child.relativeTo(root).invariantSeparatorsPath.replace('/', '.'),
                                dir = child,
                                abi = defaultAbi,
                                features = defaultFeatures,
                                noDefaultFeatures = defaultNoDefaultFeatures,
                            )
                        File(child, "__init__.py").isFile -> visit(child)
                    }
                }
        }
        Meson.pythonPackageDirs(root).forEach(::visit)
        return found
    }

    private fun tableCrates(
        pkg: File,
        editor: TomlEditor?,
        defaultAbi: PyO3Abi,
        defaultFeatures: List<String>,
        defaultNoDefaultFeatures: Boolean,
    ): List<RustCrate> {
        if (editor == null) return emptyList()
        return editor.getTableKeys("tool.ppp.rust")
            .filterNot { it in OPTION_KEYS }
            .map { module ->
                require(MODULE.matches(module)) { "[tool.ppp.rust]: '$module' is not a Python module name." }
                val value = editor.getValue("tool.ppp.rust", module)
                val path: String
                val abi: PyO3Abi
                val features: List<String>
                val noDefaultFeatures: Boolean
                when (value) {
                    is TomlValue.String -> {
                        path = value.value
                        abi = defaultAbi
                        features = defaultFeatures
                        noDefaultFeatures = defaultNoDefaultFeatures
                    }
                    is TomlValue.InlineTable -> {
                        val unknown = value.pairs.keys.firstOrNull { it !in ENTRY_OPTION_KEYS }
                        require(unknown == null) { "[tool.ppp.rust]: unknown option '$unknown' for $module." }
                        path = (value.pairs["path"] as? TomlValue.String)?.value
                            ?: throw IllegalArgumentException("[tool.ppp.rust]: '$module' table entry must specify a string 'path'.")
                        abi = value.pairs["abi"]?.let {
                            (it as? TomlValue.String)?.value?.let(PyO3Abi::parse)
                                ?: throw IllegalArgumentException("[tool.ppp.rust]: 'abi' for $module must be a string.")
                        } ?: defaultAbi
                        features = value.pairs["features"]?.let {
                            (it as? TomlValue.Array)?.items
                                ?: throw IllegalArgumentException("[tool.ppp.rust]: 'features' for $module must be an array of strings.")
                        } ?: defaultFeatures
                        noDefaultFeatures = resolveNoDefaultFeatures(
                            defaultFeaturesVal = value.pairs["default-features"],
                            noDefaultFeaturesVal = value.pairs["no-default-features"],
                            context = " for $module",
                            fallback = defaultNoDefaultFeatures,
                        )
                    }
                    else -> throw IllegalArgumentException(
                        "[tool.ppp.rust]: '$module' must be a crate path string or an inline table { path = ... }.",
                    )
                }
                val dir = File(pkg, path).canonicalFile
                require(dir.toPath().startsWith(pkg.toPath())) { "[tool.ppp.rust]: '$path' for $module is outside the package." }
                require(File(dir, "Cargo.toml").isFile) { "[tool.ppp.rust]: '$path' for $module is not a crate (no Cargo.toml)." }
                RustCrate(module, dir, abi, features, noDefaultFeatures)
            }
    }
}

/**
 * What the Cargo backend finds on this machine: `cargo` and `uv` on `PATH`, the installed rustup targets,
 * the Android NDK ([locateNdk]), Xcode's iOS SDK (`xcrun --sdk iphoneos`), and the Windows machine's own
 * architecture ([windowsMachineTarget]).
 */
data class CargoEnvironment(
    val cargo: Boolean,
    val uv: Boolean,
    val rustTargets: Set<String> = emptySet(),
    val ndk: File? = null,
    val xcode: Boolean = false,
    val windowsMachine: String? = null,
) {
    companion object {
        fun detect(): CargoEnvironment =
            CargoEnvironment(
                cargo = runs("cargo"),
                uv = runs("uv"),
                rustTargets = output("rustup", "target", "list", "--installed")?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty(),
                ndk = locateNdk(System.getenv()),
                xcode = output("xcrun", "--sdk", "iphoneos", "--show-sdk-path") != null,
                windowsMachine = windowsMachineTarget(),
            )

        /**
         * The Windows triple of the machine itself, which can differ from the JVM's host (#150): on Windows on
         * ARM the CLI runs as the `win_amd64` native image under x64 emulation (GraalVM ships no Windows aarch64
         * build), so `os.arch` says `amd64`. The emulated process still sees the machine in
         * `PROCESSOR_ARCHITEW6432` or `PROCESSOR_IDENTIFIER` (`ARMv8 (64-bit) ...`). `null` off Windows.
         */
        fun windowsMachineTarget(
            osName: String = System.getProperty("os.name"),
            env: Map<String, String> = System.getenv(),
        ): String? {
            if (!osName.lowercase().startsWith("windows")) return null
            val arm =
                listOf("PROCESSOR_ARCHITEW6432", "PROCESSOR_ARCHITECTURE").any { env[it].equals("ARM64", ignoreCase = true) } ||
                    env["PROCESSOR_IDENTIFIER"].orEmpty().trim().startsWith("ARM", ignoreCase = true)
            return when {
                arm -> "aarch64-pc-windows-msvc"
                env["PROCESSOR_ARCHITECTURE"].equals("AMD64", ignoreCase = true) -> "x86_64-pc-windows-msvc"
                else -> null
            }
        }

        /**
         * `ANDROID_NDK_HOME`, else the newest `<sdk>/ndk/<version>` holding `toolchains/llvm/prebuilt` under
         * `ANDROID_HOME` (or `ANDROID_SDK_ROOT`): the lookup the compile slot (#60) uses.
         */
        fun locateNdk(env: Map<String, String>): File? {
            env["ANDROID_NDK_HOME"]?.takeIf { it.isNotBlank() }?.let { return File(it) }
            val sdk = (env["ANDROID_HOME"] ?: env["ANDROID_SDK_ROOT"])?.takeIf { it.isNotBlank() } ?: return null
            return File(sdk, "ndk").listFiles().orEmpty()
                .filter { File(it, "toolchains/llvm/prebuilt").isDirectory }
                .maxWithOrNull(compareBy<File, List<Int>>(VERSION_ORDER) { dir -> dir.name.split('.').map { it.toIntOrNull() ?: -1 } })
        }

        private val VERSION_ORDER = Comparator<List<Int>> { a, b ->
            a.zip(b).firstOrNull { (x, y) -> x != y }?.let { (x, y) -> x.compareTo(y) } ?: a.size.compareTo(b.size)
        }

        private fun runs(tool: String): Boolean = output(tool, "--version") != null

        private fun output(vararg command: String): String? =
            runCatching {
                val process = ProcessBuilder(*command).redirectErrorStream(true).start()
                val text = process.inputStream.bufferedReader().readText()
                text.takeIf { process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0 }
            }.getOrNull()
    }
}

/** The NDK's `toolchains/llvm/prebuilt/<host tag>` for this machine (the NDK ships x86_64 binaries for macOS on arm64 too). */
fun ndkHostTag(osName: String = System.getProperty("os.name")): String =
    when {
        osName.lowercase().startsWith("mac") -> "darwin-x86_64"
        osName.lowercase().startsWith("windows") -> "windows-x86_64"
        else -> "linux-x86_64"
    }

/** The minimum Android API level a cross-built extension targets: PEP 738's floor, python.org's minimum. */
const val ANDROID_API_LEVEL = 24

/** The minimum iOS version a cross-built extension targets: python.org's iOS minimum. */
const val IOS_DEPLOYMENT_TARGET = "13.0"

/**
 * Builds a package's Rust crates into CPython extension modules: for the host desktop target (stage 1),
 * Linux cross-targets (`manylinux_2_17` / `manylinux_2_28` via `maturin --zig`, #138), Android and iOS (stage 2, #94),
 * and the Windows machine's own architecture when the JVM runs emulated (Windows on ARM, #150).
 *
 * Each crate is built by maturin ([MATURIN_VERSION], through `uv tool run`, so nothing is installed or
 * linked) with PyO3's abi3t, into
 * `build/packpack/cargo/<triple>/<module>`, with Cargo's own build directory under `build/packpack/cargo/target`.
 * The wheel's one extension file is then placed at the module's path in the `site-packages` Meson installed
 * the package into (under [build]'s `destdir`): the crate's module is the package's own, like a Meson C
 * extension, so the `single` and `fat` bundles carry it the same way. Only that file: for a dotted module
 * name maturin's wheel holds `<last>/__init__.py` and `<last>/<last>.abi3t.so`, which would import as the
 * wrong module.
 *
 * The host build runs against the project's `.venv` interpreter. An Android build cross-compiles with the NDK's
 * clang for API [ANDROID_API_LEVEL] against the target Python that `pypackpack python install <version> <target>`
 * put in `<project>/android_arm64` (or `android_x86_64`), read through `PYO3_CROSS_LIB_DIR`, and links that
 * Python's `LDLIBRARY` (`libpython3.15t.so`), as CPython's own Android extensions do: PyO3's abi3 link name,
 * `python3`, is a library with no symbols, which the linker drops, and an extension loaded by an app that
 * opened libpython privately would not resolve its symbols.
 *
 * iOS is built with `cargo rustc` directly (`docs/SPEC.md`'s fallback): maturin builds iOS only inside a cross
 * virtual environment, which python.org's XCframework does not provide. pypackpack writes the PyO3 config itself
 * (`build/packpack/cargo/<triple>/pyo3-config.txt`) and links the XCframework slice's `Python.framework`, as
 * CPython's own iOS extensions do (`@rpath/Python.framework/Python`), for iOS [IOS_DEPLOYMENT_TARGET].
 *
 * @param run runs a command in a directory with extra environment variables; a seam for tests.
 * @param environment which tools are on `PATH`; detected when first needed by default.
 */
open class Cargo(
    private val run: (command: List<String>, workingDir: File, env: Map<String, String>) -> Result<String> = ::runCommand,
    environment: CargoEnvironment? = null,
) {
    private val environment by lazy { environment ?: CargoEnvironment.detect() }

    /**
     * Builds every crate of [packageDir] for [target] (`null`: the host), places each extension in the one
     * `site-packages` under Meson's [destdir], and returns the placed files. No crate: nothing runs and the
     * list is empty.
     */
    open fun build(
        packageDir: File,
        target: String?,
        buildType: String,
        destdir: File,
    ): Result<List<File>> =
        runCatching {
            val pkg = packageDir.canonicalFile
            val crates = RustCrates.discover(pkg)
            if (crates.isEmpty()) return@runCatching emptyList()

            val host = Platforms.detectHostTarget()
            val canonical = target?.let { Platforms.normalizeTarget(it) ?: throw IllegalArgumentException(Platforms.unsupportedTargetMessage(it)) } ?: host
            val android = ANDROID_DIRS[canonical]
            val ios = IOS_TARGETS[canonical]
            val linuxCross = LINUX_CROSS_TARGETS[canonical]?.takeIf { canonical != host }
            val windowsDir = WINDOWS_DIRS[canonical]?.takeIf { canonical != host }
            if (windowsDir != null) {
                require(canonical == environment.windowsMachine) {
                    "Rust crates build for $canonical only on a $canonical machine (no Windows cross toolchain, " +
                        "owner decision 2026-11-19); this machine is ${environment.windowsMachine ?: host}."
                }
            }
            require(canonical == host || android != null || ios != null || linuxCross != null || windowsDir != null) {
                "Rust crates build for the host ($host), Linux (manylinux), Windows on its own machine, Android and iOS; " +
                    "$canonical is not yet supported by the Cargo backend."
            }
            check(environment.cargo) { "cargo is not on PATH. Install Rust with rustup (https://rustup.rs), then build again." }
            check(environment.uv) { "uv is not on PATH; the Cargo backend runs maturin with `uv tool run`." }

            val singleAbi = crates.map { it.abi }.distinct().size <= 1
            val interpreter =
                if (windowsDir != null) {
                    windowsTargetInterpreter(pkg, canonical, windowsDir)
                } else if (ios == null && android == null && linuxCross == null) {
                    findVenvInterpreter(pkg) ?: throw IllegalStateException(
                        "No Python interpreter under a '.venv' above $pkg to build the Rust crates against. " +
                            "Run `pypackpack python install 3.15t` first.",
                    )
                } else {
                    null
                }
            val crateEnvs: Map<RustCrate, Map<String, String>> =
                when {
                    ios != null -> crates.associateWith {
                        iosEnvironment(pkg, canonical, ios, it.abi, if (singleAbi) null else it.module)
                    }
                    android != null -> crates.associateWith {
                        androidEnvironment(pkg, canonical, android, it.abi)
                    }
                    linuxCross != null -> crates.associateWith {
                        linuxCrossEnvironment(pkg, canonical, linuxCross, it.abi)
                    }
                    else -> crates.associateWith { emptyMap() }
                }

            val sitePackages = destdir.walkTopDown().filter { it.isDirectory && it.name == "site-packages" }.toList()
                .singleOrNull() ?: throw IllegalStateException(
                    "No single site-packages under $destdir to place the Rust extensions in; Meson installs the package there first.",
                )

            crates.map { crate ->
                val env = crateEnvs.getValue(crate)
                if (ios != null) return@map buildIos(crate, ios, env, File(pkg, "build/packpack/cargo/target"), buildType == "release", sitePackages)
                val out = File(pkg, "build/packpack/cargo/$canonical/${crate.module}")
                out.deleteWithoutFollowingLinks()
                val command = maturinCommand(
                    crate,
                    interpreter,
                    out,
                    File(pkg, "build/packpack/cargo/target"),
                    buildType == "release",
                    rustTarget = linuxCross?.rustTriple ?: canonical.takeIf { android != null || windowsDir != null },
                    zig = linuxCross != null,
                    compatibility = linuxCross?.compatibility,
                )
                run(command, crate.dir, env).getOrElse {
                    throw IllegalStateException("Building Rust module ${crate.module} (${crate.dir}) failed: ${it.message}", it)
                }
                val wheel = out.listFiles { file -> file.name.endsWith(".whl") }.orEmpty().singleOrNull()
                    ?: throw IllegalStateException("maturin left no single wheel in $out for ${crate.module}.")
                placeExtension(crate, wheel, sitePackages)
            }
        }

    /** The environment for an iOS [canonical] target: its slice of the target Python, and a PyO3 config written for it. */
    private fun iosEnvironment(
        pkg: File,
        canonical: String,
        ios: IosTarget,
        abi: PyO3Abi = PyO3Abi.DEFAULT,
        moduleSubdir: String? = null,
    ): Map<String, String> {
        check(environment.xcode) { "No iOS SDK found (`xcrun --sdk iphoneos`). Install Xcode to build for $canonical." }
        check(ios.rustTriple in environment.rustTargets) {
            "The Rust target ${ios.rustTriple} is not installed. Run `rustup target add ${ios.rustTriple}`, then build again."
        }
        val slice = findTargetDir(pkg, "${ios.installDirName}/Python.xcframework/${ios.slice}") ?: throw IllegalStateException(
            "No Python for $canonical under an '${ios.installDirName}' directory above $pkg. " +
                "Run `pypackpack python install 3.15t $canonical` first.",
        )
        val freeThreaded = readSysconfigValue(File(slice, ios.libDir), "Py_GIL_DISABLED") == "1"
        val config =
            if (moduleSubdir == null) {
                File(pkg, "build/packpack/cargo/$canonical/pyo3-config.txt")
            } else {
                File(pkg, "build/packpack/cargo/$canonical/$moduleSubdir/pyo3-config.txt")
            }
        config.parentFile.mkdirs()
        val configLines = buildList {
            add("implementation=CPython")
            add("version=${abi.crossPythonVersion}")
            abi.iosTargetAbi?.let { add("target_abi=$it") }
            add("shared=true")
            add("build_flags=${if (freeThreaded) "Py_GIL_DISABLED" else ""}")
            add("suppress_build_script_link_lines=true")
        }
        config.writeText(configLines.joinToString("\n", postfix = "\n"))
        return linkedMapOf(
            "PYO3_CONFIG_FILE" to config.path,
            "IPHONEOS_DEPLOYMENT_TARGET" to IOS_DEPLOYMENT_TARGET,
            SLICE_KEY to slice.path,
        )
    }

    /** `cargo rustc` for one crate and one iOS target; the dylib is placed with the ABI's extension suffix. */
    private fun buildIos(
        crate: RustCrate,
        ios: IosTarget,
        env: Map<String, String>,
        targetDir: File,
        release: Boolean,
        sitePackages: File,
    ): File {
        val last = crate.module.substringAfterLast('.')
        val slice = File(env.getValue(SLICE_KEY))
        val suffix =
            if (crate.abi is PyO3Abi.VersionSpecific) {
                readSysconfigValue(File(slice, ios.libDir), "EXT_SUFFIX")?.takeIf { it.isNotBlank() }
                    ?: crate.abi.iosDefaultSuffix
            } else {
                crate.abi.iosDefaultSuffix
            }
        val fileName = "$last$suffix"
        val command = buildList {
            addAll(listOf("cargo", "rustc", "--manifest-path", File(crate.dir, "Cargo.toml").path, "--lib"))
            addAll(listOf("--target", ios.rustTriple, "--target-dir", targetDir.path))
            if (crate.noDefaultFeatures) add("--no-default-features")
            val features = crate.cargoFeatures
            if (features.isNotEmpty()) addAll(listOf("--features", features.joinToString(",")))
            if (release) add("--release")
            addAll(listOf("--", "-C", "link-arg=-F${slice.path}", "-C", "link-arg=-framework", "-C", "link-arg=Python"))
            addAll(listOf("-C", "link-arg=-Wl,-install_name,@rpath/$fileName"))
        }
        run(command, crate.dir, env - SLICE_KEY).getOrElse {
            throw IllegalStateException("Building Rust module ${crate.module} (${crate.dir}) failed: ${it.message}", it)
        }
        val profileDir = File(targetDir, "${ios.rustTriple}/${if (release) "release" else "debug"}")
        val dylib = File(profileDir, "lib$last.dylib")
        if (!dylib.isFile) {
            val built = profileDir.listFiles { file -> file.name.startsWith("lib") && file.name.endsWith(".dylib") }.orEmpty()
            throw IllegalStateException(
                "Rust module ${crate.module}: cargo built ${built.joinToString { it.name }.ifEmpty { "no dylib" }}, not lib$last.dylib. " +
                    "Set [lib] name = \"$last\" in ${File(crate.dir, "Cargo.toml")}.",
            )
        }
        val dir = File(sitePackages, crate.module.substringBeforeLast('.', "").replace('.', '/'))
        dir.mkdirs()
        return File(dir, fileName).also { dylib.copyTo(it, overwrite = true) }
    }

    /** The cross-compile environment for an Android [triple]: the NDK's clang and the target Python's libpython. */
    private fun androidEnvironment(
        pkg: File,
        triple: String,
        installDirName: String,
        abi: PyO3Abi = PyO3Abi.DEFAULT,
    ): Map<String, String> {
        check(triple in environment.rustTargets) {
            "The Rust target $triple is not installed. Run `rustup target add $triple`, then build again."
        }
        val ndk = environment.ndk ?: throw IllegalStateException(
            "No Android NDK found for $triple. Set ANDROID_NDK_HOME, or install one under ANDROID_HOME's ndk directory.",
        )
        val lib = findTargetPythonLib(pkg, installDirName) ?: throw IllegalStateException(
            "No Python for $triple under an '$installDirName' directory above $pkg. " +
                "Run `pypackpack python install 3.15t $triple` first.",
        )
        val ldLibrary = readSysconfigValue(lib, "LDLIBRARY") ?: throw IllegalStateException(
            "The Python in $lib has no _sysconfigdata naming its LDLIBRARY.",
        )
        val bin = File(ndk, "toolchains/llvm/prebuilt/${ndkHostTag()}/bin")
        val clang = File(bin, "$triple$ANDROID_API_LEVEL-clang").path
        val key = triple.replace('-', '_')
        return linkedMapOf(
            "PYO3_CROSS_LIB_DIR" to lib.path,
            "PYO3_CROSS_PYTHON_VERSION" to abi.crossPythonVersion,
            "CARGO_TARGET_${key.uppercase()}_LINKER" to clang,
            "CC_$key" to clang,
            "AR_$key" to File(bin, "llvm-ar").path,
            "CARGO_TARGET_${key.uppercase()}_RUSTFLAGS" to
                "-C link-arg=-L${lib.path} -C link-arg=-l${ldLibrary.removePrefix("lib").removeSuffix(".so")}",
        )
    }

    /**
     * The target Python's `python.exe` for a Windows [canonical] target that is this machine's own architecture
     * but not the JVM's (#150): it runs natively, so maturin reads its configuration from it.
     */
    private fun windowsTargetInterpreter(
        pkg: File,
        canonical: String,
        installDirName: String,
    ): File {
        check(canonical in environment.rustTargets) {
            "The Rust target $canonical is not installed. Run `rustup target add $canonical`, then build again."
        }
        return generateSequence(pkg) { it.parentFile }
            .map { File(it, "$installDirName/python.exe") }
            .firstOrNull { it.isFile }
            ?: throw IllegalStateException(
                "No Python for $canonical under a '$installDirName' directory above $pkg. " +
                    "Run `pypackpack python install 3.15t $canonical` first.",
            )
    }

    /** The cross-compile environment for a Linux [canonical] target: its Rust target and target Python's `lib` directory. */
    private fun linuxCrossEnvironment(
        pkg: File,
        canonical: String,
        linux: LinuxCrossTarget,
        abi: PyO3Abi = PyO3Abi.DEFAULT,
    ): Map<String, String> {
        check(linux.rustTriple in environment.rustTargets) {
            "The Rust target ${linux.rustTriple} is not installed. Run `rustup target add ${linux.rustTriple}`, then build again."
        }
        val lib = findTargetDir(pkg, "${linux.installDirName}/lib") ?: throw IllegalStateException(
            "No Python for $canonical under a '${linux.installDirName}' directory above $pkg. " +
                "Run `pypackpack python install 3.15t ${linux.rustTriple}` first.",
        )
        return linkedMapOf(
            "PYO3_CROSS_LIB_DIR" to lib.path,
            "PYO3_CROSS_PYTHON_VERSION" to abi.crossPythonVersion,
        )
    }

    fun maturinCommand(
        crate: RustCrate,
        interpreter: File?,
        out: File,
        targetDir: File,
        release: Boolean,
        rustTarget: String? = null,
        zig: Boolean = false,
        compatibility: String? = null,
    ): List<String> =
        buildList {
            addAll(listOf("uv", "tool", "run", "--from", "maturin==$MATURIN_VERSION"))
            if (zig) addAll(listOf("--with", "ziglang"))
            addAll(listOf("maturin", "build"))
            addAll(listOf("--manifest-path", File(crate.dir, "Cargo.toml").path))
            if (zig) add("--zig")
            compatibility?.let { addAll(listOf("--compatibility", it)) }
            rustTarget?.let { addAll(listOf("--target", it)) }
            interpreter?.let { addAll(listOf("--interpreter", it.path)) }
            addAll(listOf("--out", out.path))
            addAll(listOf("--target-dir", targetDir.path))
            if (crate.noDefaultFeatures) add("--no-default-features")
            val features = crate.cargoFeatures
            if (features.isNotEmpty()) addAll(listOf("--features", features.joinToString(",")))
            if (release) add("--release")
        }

    private fun placeExtension(
        crate: RustCrate,
        wheel: File,
        sitePackages: File,
    ): File {
        val last = crate.module.substringAfterLast('.')
        ZipFile(wheel).use { zip ->
            val extensions = zip.entries().asSequence()
                .filter { !it.isDirectory && !it.name.contains(".dist-info/") }
                .filter { it.name.endsWith(".so") || it.name.endsWith(".pyd") }
                .toList()
            val entry = extensions.singleOrNull()
                ?: throw IllegalStateException("${wheel.name} holds ${extensions.size} extension modules for ${crate.module}; expected one.")
            val fileName = entry.name.substringAfterLast('/')
            val stem = fileName.substringBefore('.')
            check(stem == last) {
                "Rust module ${crate.module}: the crate builds '$stem', which would not import as ${crate.module}. " +
                    "Set [lib] name = \"$last\" in ${File(crate.dir, "Cargo.toml")}."
            }
            val dir = File(sitePackages, crate.module.substringBeforeLast('.', "").replace('.', '/'))
            dir.mkdirs()
            dir.listFiles { file -> file.name.startsWith("$last.") && (file.name.endsWith(".so") || file.name.endsWith(".pyd")) }
                .orEmpty().forEach { it.delete() }
            val placed = File(dir, last + fileName.removePrefix(stem))
            zip.getInputStream(entry).use { input -> placed.outputStream().use { input.copyTo(it) } }
            return placed
        }
    }
}

/** The Windows triples the Cargo backend builds on their own machine, and the directory `installPython` puts their Python in. */
private val WINDOWS_DIRS = mapOf("x86_64-pc-windows-msvc" to "windows_amd64", "aarch64-pc-windows-msvc" to "windows_arm64")

/** The canonical Android triples the Cargo backend builds, and the directory `installPython` puts their Python in. */
private val ANDROID_DIRS = mapOf("aarch64-linux-android" to "android_arm64", "x86_64-linux-android" to "android_x86_64")

/** An iOS target: its Rust triple, `installPython`'s directory, and the XCframework slice and lib directory. */
private data class IosTarget(
    val rustTriple: String,
    val installDirName: String,
    val slice: String,
    val libDir: String,
)

private val IOS_TARGETS = mapOf(
    "arm64-apple-ios" to IosTarget("aarch64-apple-ios", "ios_arm64", "ios-arm64", "lib-arm64"),
    "arm64-apple-ios-simulator" to IosTarget("aarch64-apple-ios-sim", "ios_simulator_arm64", "ios-arm64_x86_64-simulator", "lib-arm64"),
    "x86_64-apple-ios-simulator" to IosTarget("x86_64-apple-ios", "ios_simulator_x86_64", "ios-arm64_x86_64-simulator", "lib-x86_64"),
)

/** A Linux cross-compile target: its Rust triple, `installPython`'s directory, and maturin `--compatibility` tag. */
private data class LinuxCrossTarget(
    val rustTriple: String,
    val installDirName: String,
    val compatibility: String,
)

private val LINUX_CROSS_TARGETS = mapOf(
    "x86_64-unknown-linux-gnu" to LinuxCrossTarget("x86_64-unknown-linux-gnu", "linux_x86_64", "manylinux_2_17"),
    "aarch64-unknown-linux-gnu" to LinuxCrossTarget("aarch64-unknown-linux-gnu", "linux_arm64", "manylinux_2_17"),
    "x86_64-manylinux2014" to LinuxCrossTarget("x86_64-unknown-linux-gnu", "linux_x86_64", "manylinux_2_17"),
    "x86_64-manylinux_2_17" to LinuxCrossTarget("x86_64-unknown-linux-gnu", "linux_x86_64", "manylinux_2_17"),
    "x86_64-manylinux_2_28" to LinuxCrossTarget("x86_64-unknown-linux-gnu", "linux_x86_64", "manylinux_2_28"),
    "aarch64-manylinux_2_17" to LinuxCrossTarget("aarch64-unknown-linux-gnu", "linux_arm64", "manylinux_2_17"),
    "aarch64-manylinux_2_28" to LinuxCrossTarget("aarch64-unknown-linux-gnu", "linux_arm64", "manylinux_2_28"),
)

/** Carries the iOS slice from [Cargo]'s environment to its command; not passed to the process. */
private const val SLICE_KEY = "pypackpack.ios.slice"

/** `<dir>/<relative>` in the nearest directory at or above [startDir] that has one. */
private fun findTargetDir(
    startDir: File,
    relative: String,
): File? =
    generateSequence(startDir.canonicalFile) { it.parentFile }
        .map { File(it, relative) }
        .firstOrNull { it.isDirectory }

/** `<dir>/<installDirName>/prefix/lib` in the nearest directory at or above [startDir] that has one. */
private fun findTargetPythonLib(
    startDir: File,
    installDirName: String,
): File? = findTargetDir(startDir, "$installDirName/prefix/lib")

/** A string value from the target Python's `_sysconfigdata_*.py` under [lib]. */
private fun readSysconfigValue(
    lib: File,
    key: String,
): String? =
    lib.walkTopDown().maxDepth(2).firstOrNull { it.isFile && it.name.startsWith("_sysconfigdata") && it.name.endsWith(".py") }
        ?.readText()
        ?.let { Regex("'${Regex.escape(key)}':\\s*(?:'([^']*)'|([0-9]+))").find(it)?.groupValues?.let { g -> g[1].ifEmpty { g[2] } } }

private fun runCommand(
    command: List<String>,
    workingDir: File,
    env: Map<String, String>,
): Result<String> =
    runCatching {
        val log = File.createTempFile("ppp-cargo", ".log")
        try {
            val process = ProcessBuilder(command).directory(workingDir).redirectErrorStream(true).redirectOutput(log)
                .apply { environment().putAll(env) }.start()
            val exit = process.waitFor()
            val output = log.readText()
            check(exit == 0) { output.lines().takeLast(30).joinToString("\n") }
            output
        } finally {
            log.delete()
        }
    }
