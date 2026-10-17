package org.thisisthepy.python.multiplatform.packpack.compile.backend.external

import org.thisisthepy.python.multiplatform.packpack.utils.Platforms
import org.thisisthepy.python.multiplatform.packpack.utils.findVenvInterpreter
import org.thisisthepy.python.multiplatform.packpack.utils.toml.TomlEditor
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/** The maturin `uv tool run` runs; pinned, so a build does not change under a new release. */
const val MATURIN_VERSION = "1.15.0"

/** PyO3's stable ABI for free-threaded CPython 3.15+ (PEP 803), the default (`docs/SPEC.md` Scope decisions). */
const val PYO3_ABI3T_FEATURE = "pyo3/abi3t-py315"

/** A Rust crate that builds the CPython extension module [module]. */
data class RustCrate(
    val module: String,
    val dir: File,
)

/**
 * Which crates a package builds (`docs/SPEC.md` *Rust extension modules (Cargo backend)*, #92):
 * - by convention, a directory holding a `Cargo.toml` inside a Python package is that package's module
 *   (`src/main/core/_fast` is `core._fast`), under the first of `src/main`, `src` and the package root
 *   that holds a Python package (Meson's rule);
 * - `[tool.ppp.rust]` in the package's `pyproject.toml` maps a module to a crate directory relative to the
 *   package, for crates outside the Python packages (torchnative's `"torch._C" = "rust/torch_c"`).
 * A crate in neither place is not built. One module named both ways is an error.
 */
object RustCrates {
    private val MODULE = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")
    private val SKIPPED = setOf("__pycache__", "build", "dist", "target", "node_modules")

    fun discover(packageDir: File): List<RustCrate> {
        val pkg = packageDir.canonicalFile
        val convention = conventionCrates(pkg)
        val table = tableCrates(pkg)
        val both = convention.map { it.module }.intersect(table.map { it.module }.toSet())
        require(both.isEmpty()) {
            "Rust module ${both.joinToString()} is declared both by a Cargo.toml inside its package and in " +
                "[tool.ppp.rust]; keep one."
        }
        return (convention + table).sortedBy { it.module }
    }

    private fun conventionCrates(pkg: File): List<RustCrate> {
        val root = listOf(File(pkg, "src/main"), File(pkg, "src"), pkg).firstOrNull { candidate ->
            candidate.listFiles().orEmpty().any { it.isDirectory && File(it, "__init__.py").isFile }
        } ?: return emptyList()
        val found = mutableListOf<RustCrate>()
        fun visit(dir: File) {
            dir.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith(".") && it.name !in SKIPPED }
                .sortedBy { it.name }
                .forEach { child ->
                    when {
                        File(child, "Cargo.toml").isFile ->
                            found += RustCrate(child.relativeTo(root).invariantSeparatorsPath.replace('/', '.'), child)
                        File(child, "__init__.py").isFile -> visit(child)
                    }
                }
        }
        root.listFiles().orEmpty().filter { it.isDirectory && File(it, "__init__.py").isFile }.sortedBy { it.name }.forEach(::visit)
        return found
    }

    private fun tableCrates(pkg: File): List<RustCrate> {
        val pyproject = File(pkg, "pyproject.toml").takeIf { it.isFile } ?: return emptyList()
        return TomlEditor(pyproject.readText()).getStringEntries("tool.ppp.rust").map { (module, path) ->
            require(MODULE.matches(module)) { "[tool.ppp.rust]: '$module' is not a Python module name." }
            val dir = File(pkg, path).canonicalFile
            require(dir.toPath().startsWith(pkg.toPath())) { "[tool.ppp.rust]: '$path' for $module is outside the package." }
            require(File(dir, "Cargo.toml").isFile) { "[tool.ppp.rust]: '$path' for $module is not a crate (no Cargo.toml)." }
            RustCrate(module, dir)
        }
    }
}

/**
 * What the Cargo backend finds on this machine: `cargo` and `uv` on `PATH`, the installed rustup targets,
 * the Android NDK ([locateNdk]) and Xcode's iOS SDK (`xcrun --sdk iphoneos`).
 */
data class CargoEnvironment(
    val cargo: Boolean,
    val uv: Boolean,
    val rustTargets: Set<String> = emptySet(),
    val ndk: File? = null,
    val xcode: Boolean = false,
) {
    companion object {
        fun detect(): CargoEnvironment =
            CargoEnvironment(
                cargo = runs("cargo"),
                uv = runs("uv"),
                rustTargets = output("rustup", "target", "list", "--installed")?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty(),
                ndk = locateNdk(System.getenv()),
                xcode = output("xcrun", "--sdk", "iphoneos", "--show-sdk-path") != null,
            )

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
 * Android and iOS (stage 2, #94).
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
            require(canonical == host || android != null || ios != null) {
                "Rust crates build for the host ($host), Android and iOS; $canonical is not yet supported by the Cargo backend."
            }
            check(environment.cargo) { "cargo is not on PATH. Install Rust with rustup (https://rustup.rs), then build again." }
            check(environment.uv) { "uv is not on PATH; the Cargo backend runs maturin with `uv tool run`." }

            val (interpreter, env) =
                if (ios != null) {
                    null to iosEnvironment(pkg, canonical, ios)
                } else if (android == null) {
                    val venv = findVenvInterpreter(pkg) ?: throw IllegalStateException(
                        "No Python interpreter under a '.venv' above $pkg to build the Rust crates against. " +
                            "Run `pypackpack python install 3.15t` first.",
                    )
                    venv to emptyMap()
                } else {
                    null to androidEnvironment(pkg, canonical, android)
                }

            val sitePackages = destdir.walkTopDown().filter { it.isDirectory && it.name == "site-packages" }.toList()
                .singleOrNull() ?: throw IllegalStateException(
                    "No single site-packages under $destdir to place the Rust extensions in; Meson installs the package there first.",
                )

            crates.map { crate ->
                if (ios != null) return@map buildIos(crate, ios, env, File(pkg, "build/packpack/cargo/target"), buildType == "release", sitePackages)
                val out = File(pkg, "build/packpack/cargo/$canonical/${crate.module}")
                out.deleteRecursively()
                val command = maturinCommand(
                    crate, interpreter, out, File(pkg, "build/packpack/cargo/target"), buildType == "release",
                    rustTarget = canonical.takeIf { android != null },
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
        val config = File(pkg, "build/packpack/cargo/$canonical/pyo3-config.txt")
        config.parentFile.mkdirs()
        config.writeText(
            listOf(
                "implementation=CPython",
                "version=3.15",
                "target_abi=CPython-abi3t-3.15",
                "shared=true",
                "build_flags=${if (freeThreaded) "Py_GIL_DISABLED" else ""}",
                "suppress_build_script_link_lines=true",
            ).joinToString("\n", postfix = "\n"),
        )
        return linkedMapOf(
            "PYO3_CONFIG_FILE" to config.path,
            "IPHONEOS_DEPLOYMENT_TARGET" to IOS_DEPLOYMENT_TARGET,
            SLICE_KEY to slice.path,
        )
    }

    /** `cargo rustc` for one crate and one iOS target; the dylib is placed as `<last>.abi3t.so`. */
    private fun buildIos(
        crate: RustCrate,
        ios: IosTarget,
        env: Map<String, String>,
        targetDir: File,
        release: Boolean,
        sitePackages: File,
    ): File {
        val last = crate.module.substringAfterLast('.')
        val fileName = "$last.abi3t.so"
        val command = buildList {
            addAll(listOf("cargo", "rustc", "--manifest-path", File(crate.dir, "Cargo.toml").path, "--lib"))
            addAll(listOf("--target", ios.rustTriple, "--target-dir", targetDir.path, "--features", PYO3_ABI3T_FEATURE))
            if (release) add("--release")
            addAll(listOf("--", "-C", "link-arg=-F${env.getValue(SLICE_KEY)}", "-C", "link-arg=-framework", "-C", "link-arg=Python"))
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
            "PYO3_CROSS_PYTHON_VERSION" to "3.15",
            "CARGO_TARGET_${key.uppercase()}_LINKER" to clang,
            "CC_$key" to clang,
            "AR_$key" to File(bin, "llvm-ar").path,
            "CARGO_TARGET_${key.uppercase()}_RUSTFLAGS" to
                "-C link-arg=-L${lib.path} -C link-arg=-l${ldLibrary.removePrefix("lib").removeSuffix(".so")}",
        )
    }

    fun maturinCommand(
        crate: RustCrate,
        interpreter: File?,
        out: File,
        targetDir: File,
        release: Boolean,
        rustTarget: String? = null,
    ): List<String> =
        buildList {
            addAll(listOf("uv", "tool", "run", "--from", "maturin==$MATURIN_VERSION", "maturin", "build"))
            addAll(listOf("--manifest-path", File(crate.dir, "Cargo.toml").path))
            rustTarget?.let { addAll(listOf("--target", it)) }
            interpreter?.let { addAll(listOf("--interpreter", it.path)) }
            addAll(listOf("--out", out.path))
            addAll(listOf("--target-dir", targetDir.path))
            addAll(listOf("--features", PYO3_ABI3T_FEATURE))
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
