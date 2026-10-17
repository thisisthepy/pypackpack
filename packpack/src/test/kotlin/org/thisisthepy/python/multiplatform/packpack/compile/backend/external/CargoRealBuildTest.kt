package org.thisisthepy.python.multiplatform.packpack.compile.backend.external

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A real build of a small PyO3 crate through the Cargo backend (#92): maturin, abi3t, the extension placed
 * at `core._fast` in Meson's `site-packages`, imported by a free-threaded 3.15 interpreter without the GIL.
 *
 * Needs `cargo`, `uv` and network access (crates.io, PyPI, python-build-standalone) and takes a minute,
 * so it runs only with `PPP_CARGO_REAL_BUILD=1`. Everything it creates is under this module's `build/`;
 * uv's managed Python goes there too (`UV_PYTHON_INSTALL_DIR`).
 */
class CargoRealBuildTest {
    private val root = File("build/cargo-real").absoluteFile

    private fun run(command: List<String>, dir: File, env: Map<String, String> = emptyMap()): String {
        val log = File(root, "command.log")
        val process = ProcessBuilder(command).directory(dir).redirectErrorStream(true).redirectOutput(log)
            .apply { environment().putAll(env) }.start()
        check(process.waitFor(15, TimeUnit.MINUTES) && process.exitValue() == 0) { "${command.joinToString(" ")}\n${log.readText()}" }
        return log.readText()
    }

    @Test
    fun `a PyO3 crate becomes core_fast, an abi3t extension that keeps the GIL off`() {
        assumeTrue(System.getenv("PPP_CARGO_REAL_BUILD") == "1", "set PPP_CARGO_REAL_BUILD=1 to build a real crate")
        val environment = CargoEnvironment.detect()
        assumeTrue(environment.cargo && environment.uv, "needs cargo and uv on PATH")

        val pkg = File(root, "pkg").apply { deleteRecursively(); mkdirs() }
        File(pkg, "pyproject.toml").writeText("[project]\nname = \"core\"\nversion = \"0.1.0\"\n")
        File(pkg, "src/main/core").mkdirs()
        File(pkg, "src/main/core/__init__.py").writeText("")
        val crate = File(pkg, "src/main/core/_fast").apply { File(this, "src").mkdirs() }
        File(crate, "Cargo.toml").writeText(
            "[package]\nname = \"fast\"\nversion = \"0.1.0\"\nedition = \"2021\"\n\n" +
                "[lib]\nname = \"_fast\"\ncrate-type = [\"cdylib\"]\n\n[dependencies]\npyo3 = \"0.30\"\n",
        )
        File(crate, "src/lib.rs").writeText(
            "use pyo3::prelude::*;\n\n#[pymodule(gil_used = false)]\nmod _fast {\n    use pyo3::prelude::*;\n\n" +
                "    #[pyfunction]\n    fn answer() -> i64 {\n        42\n    }\n}\n",
        )
        run(listOf("uv", "venv", "--python", "3.15t", ".venv"), pkg, mapOf("UV_PYTHON_INSTALL_DIR" to File(root, "python").path))

        // Where Meson would have installed the package.
        val destdir = File(pkg, "dist/default/debug/instant")
        val sitePackages = File(destdir, "lib/python3.15t/site-packages")
        File(sitePackages, "core").mkdirs()
        File(sitePackages, "core/__init__.py").writeText("")

        val placed = Cargo().build(pkg, null, "debug", destdir).getOrThrow()

        assertEquals(listOf(File(sitePackages, "core").path), placed.map { it.parentFile.path })
        val output = run(
            listOf(
                File(pkg, ".venv/bin/python").path, "-I", "-c",
                "import sys; sys.path.insert(0, sys.argv[1]); from core import _fast; " +
                    "print(_fast.answer(), int(sys._is_gil_enabled()), _fast.__file__.rsplit('/', 1)[1])",
                sitePackages.path,
            ),
            pkg,
        )
        assertEquals("42 0 _fast.abi3t.so", output.trim().lines().last())
    }
}
