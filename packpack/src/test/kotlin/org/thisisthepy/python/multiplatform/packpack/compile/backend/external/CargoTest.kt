package org.thisisthepy.python.multiplatform.packpack.compile.backend.external

import org.thisisthepy.python.multiplatform.packpack.utils.Platforms
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Cargo backend, stage 1: host desktop only (#92; SPEC *Rust extension modules (Cargo backend)*).
 * Discovery, the maturin command, placing the extension in Meson's `site-packages`, and every refusal,
 * with a fake maturin run.
 * `CargoRealBuildTest` builds a real crate.
 */
class CargoTest {
    private val pkg: File = Files.createTempDirectory("ppp-cargo-").toFile().canonicalFile
    private val host = Platforms.detectHostTarget()

    @AfterTest
    fun cleanup() {
        pkg.deleteRecursively()
    }

    private fun write(relative: String, text: String = "") =
        File(pkg, relative).apply {
            parentFile.mkdirs()
            writeText(text)
        }

    private fun crate(relative: String, lib: String = relative.substringAfterLast('/')) =
        write("$relative/Cargo.toml", "[package]\nname = \"x\"\nversion = \"0.1.0\"\n\n[lib]\nname = \"$lib\"\ncrate-type = [\"cdylib\"]\n")

    /** Meson's destdir for this build, with the `site-packages` it installed the package into. */
    private val destdir = File(pkg, "dist/default/debug/instant")
    private val sitePackages = File(destdir, "usr/local/lib/python3.15t/site-packages")

    private fun pyproject(extra: String = "") = write("pyproject.toml", "[project]\nname = \"core\"\nversion = \"0.1.0\"\n$extra")

    // Discovery

    @Test
    fun `a Cargo directory inside a Python package is that package's module`() {
        pyproject()
        write("src/main/core/__init__.py")
        write("src/main/core/sub/__init__.py")
        crate("src/main/core/_fast")
        crate("src/main/core/sub/_deep")
        crate("src/main/notapackage/_lost")

        val crates = RustCrates.discover(pkg)

        assertEquals(listOf("core._fast", "core.sub._deep"), crates.map { it.module })
        assertEquals(File(pkg, "src/main/core/_fast"), crates.first().dir)
    }

    @Test
    fun `the tool ppp rust table names a crate outside the Python packages`() {
        pyproject("\n[tool.ppp.rust]\n\"torch._C\" = \"rust/torch_c\"\n")
        write("src/main/torch/__init__.py")
        crate("rust/torch_c", lib = "_C")

        assertEquals(listOf("torch._C" to File(pkg, "rust/torch_c")), RustCrates.discover(pkg).map { it.module to it.dir })
    }

    /** torchnative#53's layout: `torch._C` from `rust/torch_c`; the probe crates beside it are not built. */
    @Test
    fun `torchnative's layout is found through the table, without its probe crates`() {
        pyproject("\n[tool.ppp.rust]\n\"torch._C\" = \"rust/torch_c\"\n")
        write("python/torch/__init__.py")
        write("python/torchnative/__init__.py")
        crate("rust/torch_c", lib = "_C")
        crate("rust/vulkan_probe")
        crate("rust/wasm_probe")
        File(pkg, "rust/vendor").mkdirs()

        assertEquals(listOf("torch._C"), RustCrates.discover(pkg).map { it.module })
    }

    @Test
    fun `a table entry that is not a crate, leaves the package, or repeats a convention module is refused`() {
        pyproject("\n[tool.ppp.rust]\n\"x._y\" = \"rust/missing\"\n")
        assertTrue("rust/missing" in runCatching { RustCrates.discover(pkg) }.exceptionOrNull()?.message.orEmpty())

        pyproject("\n[tool.ppp.rust]\n\"x._y\" = \"../outside\"\n")
        assertTrue("outside" in runCatching { RustCrates.discover(pkg) }.exceptionOrNull()?.message.orEmpty())

        pyproject("\n[tool.ppp.rust]\n\"core._fast\" = \"rust/fast\"\n")
        write("src/main/core/__init__.py")
        crate("src/main/core/_fast")
        crate("rust/fast", lib = "_fast")
        val message = runCatching { RustCrates.discover(pkg) }.exceptionOrNull()?.message.orEmpty()
        assertTrue("core._fast" in message && "both" in message, message)
    }

    @Test
    fun `no crate means nothing to do`() {
        pyproject()
        write("src/main/core/__init__.py")
        assertTrue(RustCrates.discover(pkg).isEmpty())
        assertTrue(Cargo(environment = available()).build(pkg, null, "debug", destdir).getOrThrow().isEmpty())
    }

    // Build

    private fun available(cargo: Boolean = true, uv: Boolean = true) = CargoEnvironment(cargo = cargo, uv = uv)

    private fun venv() = write(".venv/bin/python3")

    /** A maturin that writes [entries] into a wheel under `--out`. */
    private fun fakeMaturin(vararg entries: String, calls: MutableList<List<String>> = mutableListOf()): (List<String>, File) -> Result<String> =
        { command, _ ->
            calls += command
            val out = File(command[command.indexOf("--out") + 1]).apply { mkdirs() }
            ZipOutputStream(File(out, "x-0.1.0-cp315-abi3.abi3t-macosx_11_0_arm64.whl").outputStream()).use { zip ->
                entries.forEach { zip.putNextEntry(ZipEntry(it)); zip.write(it.encodeToByteArray()); zip.closeEntry() }
            }
            Result.success("built")
        }

    @Test
    fun `maturin is pinned, run through uv, built for abi3t against the venv, outside the source tree`() {
        pyproject()
        write("src/main/core/__init__.py")
        crate("src/main/core/_fast")
        val python = venv()
        sitePackages.mkdirs()
        val calls = mutableListOf<List<String>>()

        Cargo(run = fakeMaturin("_fast/_fast.abi3t.so", "_fast/__init__.py", calls = calls), environment = available())
            .build(pkg, null, "release", destdir).getOrThrow()

        val out = File(pkg, "build/packpack/cargo/$host/core._fast")
        assertEquals(
            listOf(
                "uv", "tool", "run", "--from", "maturin==$MATURIN_VERSION", "maturin", "build",
                "--manifest-path", File(pkg, "src/main/core/_fast/Cargo.toml").path,
                "--interpreter", python.path,
                "--out", out.path,
                "--target-dir", File(pkg, "build/packpack/cargo/target").path,
                "--features", "pyo3/abi3t-py315",
                "--release",
            ),
            calls.single(),
        )
    }

    /**
     * The crate's module is the package's own, like a Meson C extension: it goes where Meson installed the
     * package (the destdir's `site-packages`), which the `single` and `fat` bundles read.
     */
    @Test
    fun `the extension is placed at its module path in Meson's site-packages, and nothing else of the wheel`() {
        pyproject()
        write("src/main/core/__init__.py")
        crate("src/main/core/_fast")
        venv()
        File(sitePackages, "core/__init__.py").apply { parentFile.mkdirs(); writeText("# installed by meson\n") }

        val placed = Cargo(run = fakeMaturin("_fast/_fast.abi3t.so", "_fast/__init__.py"), environment = available())
            .build(pkg, host, "debug", destdir).getOrThrow()

        val target = File(sitePackages, "core/_fast.abi3t.so")
        assertEquals(listOf(target), placed)
        assertTrue(target.isFile)
        assertFalse(File(sitePackages, "_fast").exists(), "maturin's own _fast/__init__.py is not installed")
        assertEquals("# installed by meson\n", File(sitePackages, "core/__init__.py").readText())
    }

    @Test
    fun `without Meson's site-packages there is nowhere to place the extension`() {
        pyproject()
        write("src/main/core/__init__.py")
        crate("src/main/core/_fast")
        venv()

        val message = Cargo(run = fakeMaturin("_fast/_fast.abi3t.so"), environment = available())
            .build(pkg, null, "debug", destdir).exceptionOrNull()?.message.orEmpty()

        assertTrue("site-packages" in message, message)
    }

    @Test
    fun `an extension named other than the module is refused, naming the lib name to set`() {
        pyproject("\n[tool.ppp.rust]\n\"torch._C\" = \"rust/torch_c\"\n")
        crate("rust/torch_c", lib = "torch_c")
        venv()
        sitePackages.mkdirs()

        val message = Cargo(run = fakeMaturin("torch_c/torch_c.abi3t.so"), environment = available())
            .build(pkg, null, "debug", destdir).exceptionOrNull()?.message.orEmpty()

        assertTrue("torch._C" in message && "[lib] name = \"_C\"" in message, message)
    }

    @Test
    fun `missing cargo, uv or venv, or a non-host target, is refused before maturin runs`() {
        pyproject()
        write("src/main/core/__init__.py")
        crate("src/main/core/_fast")
        val calls = mutableListOf<List<String>>()
        val maturin = fakeMaturin("_fast/_fast.abi3t.so", calls = calls)

        val noVenv = Cargo(run = maturin, environment = available()).build(pkg, null, "debug", destdir).exceptionOrNull()?.message.orEmpty()
        assertTrue(".venv" in noVenv && "python install 3.15t" in noVenv, noVenv)

        venv()
        val noCargo = Cargo(run = maturin, environment = available(cargo = false)).build(pkg, null, "debug", destdir).exceptionOrNull()?.message.orEmpty()
        assertTrue("cargo" in noCargo && "rustup" in noCargo, noCargo)

        val noUv = Cargo(run = maturin, environment = available(uv = false)).build(pkg, null, "debug", destdir).exceptionOrNull()?.message.orEmpty()
        assertTrue("uv" in noUv, noUv)

        val other = if (host == "aarch64-linux-android") "x86_64-linux-android" else "aarch64-linux-android"
        val cross = Cargo(run = maturin, environment = available()).build(pkg, other, "debug", destdir).exceptionOrNull()?.message.orEmpty()
        assertTrue(other in cross && "host" in cross, cross)

        assertTrue(calls.isEmpty(), "$calls")
    }

    @Test
    fun `a failed maturin build fails with its output`() {
        pyproject()
        write("src/main/core/__init__.py")
        crate("src/main/core/_fast")
        venv()
        sitePackages.mkdirs()

        val message = Cargo(run = { _, _ -> Result.failure(IllegalStateException("error[E0433]")) }, environment = available())
            .build(pkg, null, "debug", destdir).exceptionOrNull()?.message.orEmpty()

        assertTrue("core._fast" in message && "error[E0433]" in message, message)
    }
}
