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

    /**
     * Deletes [file] without following symbolic links. The Android and iOS projects link to the extracted
     * target Pythons, and `deleteRecursively` follows a link into its directory and empties the target.
     */
    private fun deleteWithoutFollowing(file: File) {
        val path = file.toPath()
        if (java.nio.file.Files.isSymbolicLink(path)) {
            java.nio.file.Files.delete(path)
            return
        }
        if (file.isDirectory) file.listFiles().orEmpty().forEach(::deleteWithoutFollowing)
        file.delete()
    }

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

        val pkg = File(root, "pkg").apply { deleteWithoutFollowing(this); mkdirs() }
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

    /**
     * Android (#94): the crate cross-compiled with the NDK against python-multiplatform's 3.15t Android build.
     * Also needs `PPP_ANDROID_PYTHON` (the extracted archive: `prefix/lib/libpython3.15t.so` inside) and an
     * NDK (`ANDROID_NDK_HOME`). A host cannot load the result, so the test reads its ELF dynamic section.
     */
    @Test
    fun `a PyO3 crate cross-compiles for aarch64 Android and links the target's libpython`() {
        assumeTrue(System.getenv("PPP_CARGO_REAL_BUILD") == "1", "set PPP_CARGO_REAL_BUILD=1 to build a real crate")
        val androidPython = System.getenv("PPP_ANDROID_PYTHON")?.let(::File)
        assumeTrue(androidPython != null && File(androidPython, "prefix/lib").isDirectory, "set PPP_ANDROID_PYTHON to an extracted Android Python")
        val environment = CargoEnvironment.detect()
        assumeTrue(environment.cargo && environment.uv && environment.ndk != null, "needs cargo, uv and an NDK")

        val project = File(root, "android").apply { deleteWithoutFollowing(this); mkdirs() }
        java.nio.file.Files.createSymbolicLink(File(project, "android_arm64").toPath(), androidPython!!.absoluteFile.toPath())
        val pkg = File(project, "pkg").apply { mkdirs() }
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
        val destdir = File(pkg, "dist/aarch64-linux-android/debug/instant")
        val sitePackages = File(destdir, "lib/python3.15t/site-packages").apply { mkdirs() }

        val placed = Cargo().build(pkg, "aarch64-linux-android", "debug", destdir).getOrThrow()

        assertEquals(listOf(File(sitePackages, "core/_fast.abi3t.so")), placed)
        val wheel = File(pkg, "build/packpack/cargo/aarch64-linux-android/core._fast").listFiles().orEmpty().single { it.name.endsWith(".whl") }
        assertEquals(true, wheel.name.endsWith("-cp315-abi3.abi3t-android_24_arm64_v8a.whl"), wheel.name)
        val readelf = File(environment.ndk, "toolchains/llvm/prebuilt/${ndkHostTag()}/bin/llvm-readelf").path
        val dynamic = run(listOf(readelf, "-d", "-h", placed.single().path), pkg)
        assertEquals(true, "Shared library: [libpython3.15t.so]" in dynamic && "AArch64" in dynamic, dynamic)
    }

    /**
     * iOS (#94): cargo against python-multiplatform's 3.15t XCframework, device and simulator. Also needs
     * `PPP_IOS_PYTHON` (the extracted archive: `Python.xcframework` inside) and Xcode. The result is read with
     * `otool`: an iOS (platform 2, minimum 13.0) or iOS simulator (platform 7, minimum 14.0 on arm64) binary that links `@rpath/Python.framework/Python`.
     */
    @Test
    fun `a PyO3 crate builds for the iOS device and simulator against the XCframework`() {
        assumeTrue(System.getenv("PPP_CARGO_REAL_BUILD") == "1", "set PPP_CARGO_REAL_BUILD=1 to build a real crate")
        val iosPython = System.getenv("PPP_IOS_PYTHON")?.let(::File)
        assumeTrue(iosPython != null && File(iosPython, "Python.xcframework").isDirectory, "set PPP_IOS_PYTHON to an extracted iOS XCframework archive")
        val environment = CargoEnvironment.detect()
        assumeTrue(environment.cargo && environment.xcode, "needs cargo and Xcode")

        val project = File(root, "ios").apply { deleteWithoutFollowing(this); mkdirs() }
        listOf("ios_arm64", "ios_simulator_arm64").forEach {
            java.nio.file.Files.createSymbolicLink(File(project, it).toPath(), iosPython!!.absoluteFile.toPath())
        }
        val pkg = File(project, "pkg").apply { mkdirs() }
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

        // Rust's aarch64-apple-ios-sim target starts at iOS 14 (the first arm64 simulator), above the 13.0 asked for.
        for ((target, platform, minos) in listOf(
            Triple("arm64-apple-ios", "platform 2", "minos 13.0"),
            Triple("arm64-apple-ios-simulator", "platform 7", "minos 14.0"),
        )) {
            val sitePackages = File(pkg, "dist/$target/debug/instant/lib/python3.15t/site-packages").apply { mkdirs() }
            val placed = Cargo().build(pkg, target, "debug", File(pkg, "dist/$target/debug/instant")).getOrThrow()

            assertEquals(listOf(File(sitePackages, "core/_fast.abi3t.so")), placed)
            val links = run(listOf("otool", "-L", placed.single().path), pkg)
            assertEquals(true, "@rpath/Python.framework/Python" in links && "@rpath/_fast.abi3t.so" in links, links)
            val loads = run(listOf("otool", "-l", placed.single().path), pkg)
            assertEquals(true, platform in loads && minos in loads, "$target: $loads")
        }
    }
}
