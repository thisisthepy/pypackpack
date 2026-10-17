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

/** Whether the tools the Cargo backend calls are on `PATH`. */
data class CargoEnvironment(
    val cargo: Boolean,
    val uv: Boolean,
) {
    companion object {
        fun detect(): CargoEnvironment = CargoEnvironment(cargo = runs("cargo"), uv = runs("uv"))

        private fun runs(tool: String): Boolean =
            runCatching {
                val process = ProcessBuilder(tool, "--version").redirectErrorStream(true).start()
                process.inputStream.readAllBytes()
                process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0
            }.getOrDefault(false)
    }
}

/**
 * Builds a package's Rust crates into CPython extension modules (stage 1: the host desktop target only).
 *
 * Each crate is built by maturin ([MATURIN_VERSION], through `uv tool run`, so nothing is installed or
 * linked) with PyO3's abi3t against the project's `.venv` interpreter, into
 * `build/packpack/cargo/<triple>/<module>`, with Cargo's own build directory under `build/packpack/cargo/target`.
 * The wheel's one extension file is then placed at the module's path in the `site-packages` Meson installed
 * the package into (under [build]'s `destdir`): the crate's module is the package's own, like a Meson C
 * extension, so the `single` and `fat` bundles carry it the same way. Only that file: for a dotted module
 * name maturin's wheel holds `<last>/__init__.py` and `<last>/<last>.abi3t.so`, which would import as the
 * wrong module.
 *
 * @param run runs a command in a directory; a seam for tests.
 * @param environment which tools are on `PATH`; detected when first needed by default.
 */
open class Cargo(
    private val run: (command: List<String>, workingDir: File) -> Result<String> = ::runCommand,
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
            require(canonical == host) {
                "Rust crates build for the host ($host) only so far; $canonical is stage 2 of the Cargo backend (#92)."
            }
            check(environment.cargo) { "cargo is not on PATH. Install Rust with rustup (https://rustup.rs), then build again." }
            check(environment.uv) { "uv is not on PATH; the Cargo backend runs maturin with `uv tool run`." }
            val interpreter = findVenvInterpreter(pkg) ?: throw IllegalStateException(
                "No Python interpreter under a '.venv' above $pkg to build the Rust crates against. " +
                    "Run `pypackpack python install 3.15t` first.",
            )

            val sitePackages = destdir.walkTopDown().filter { it.isDirectory && it.name == "site-packages" }.toList()
                .singleOrNull() ?: throw IllegalStateException(
                    "No single site-packages under $destdir to place the Rust extensions in; Meson installs the package there first.",
                )

            crates.map { crate ->
                val out = File(pkg, "build/packpack/cargo/$host/${crate.module}")
                out.deleteRecursively()
                val command = maturinCommand(crate, interpreter, out, File(pkg, "build/packpack/cargo/target"), buildType == "release")
                run(command, crate.dir).getOrElse {
                    throw IllegalStateException("Building Rust module ${crate.module} (${crate.dir}) failed: ${it.message}", it)
                }
                val wheel = out.listFiles { file -> file.name.endsWith(".whl") }.orEmpty().singleOrNull()
                    ?: throw IllegalStateException("maturin left no single wheel in $out for ${crate.module}.")
                placeExtension(crate, wheel, sitePackages)
            }
        }

    fun maturinCommand(
        crate: RustCrate,
        interpreter: File,
        out: File,
        targetDir: File,
        release: Boolean,
    ): List<String> =
        buildList {
            addAll(listOf("uv", "tool", "run", "--from", "maturin==$MATURIN_VERSION", "maturin", "build"))
            addAll(listOf("--manifest-path", File(crate.dir, "Cargo.toml").path))
            addAll(listOf("--interpreter", interpreter.path))
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

private fun runCommand(
    command: List<String>,
    workingDir: File,
): Result<String> =
    runCatching {
        val log = File.createTempFile("ppp-cargo", ".log")
        try {
            val process = ProcessBuilder(command).directory(workingDir).redirectErrorStream(true).redirectOutput(log).start()
            val exit = process.waitFor()
            val output = log.readText()
            check(exit == 0) { output.lines().takeLast(30).joinToString("\n") }
            output
        } finally {
            log.delete()
        }
    }
