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

    private fun available(
        cargo: Boolean = true,
        uv: Boolean = true,
        rustTargets: Set<String> = setOf("aarch64-linux-android", "x86_64-linux-android", "aarch64-apple-ios", "aarch64-apple-ios-sim", "x86_64-apple-ios"),
        ndk: File? = null,
        xcode: Boolean = true,
    ) = CargoEnvironment(cargo = cargo, uv = uv, rustTargets = rustTargets, ndk = ndk, xcode = xcode)

    private fun venv() = write(".venv/bin/python3")

    /** A maturin that writes [entries] into a wheel under `--out`. */
    private fun fakeMaturin(
        vararg entries: String,
        calls: MutableList<List<String>> = mutableListOf(),
        envs: MutableList<Map<String, String>> = mutableListOf(),
    ): (List<String>, File, Map<String, String>) -> Result<String> =
        { command, _, env ->
            calls += command
            envs += env
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

        val windows = if (host == "x86_64-pc-windows-msvc") "aarch64-unknown-linux-gnu" else "x86_64-pc-windows-msvc"
        val other = Cargo(run = maturin, environment = available()).build(pkg, windows, "debug", destdir).exceptionOrNull()?.message.orEmpty()
        assertTrue(windows in other && "not yet" in other, other)

        assertTrue(calls.isEmpty(), "$calls")
    }

    // Stage 2: Android (#94)

    /** An NDK laid out as `toolchains/llvm/prebuilt/<host tag>/bin`, with the clang and llvm-ar maturin needs. */
    private fun fakeNdk(): File {
        val bin = File(pkg, "ndk/toolchains/llvm/prebuilt/${ndkHostTag()}/bin").apply { mkdirs() }
        listOf("aarch64-linux-android24-clang", "x86_64-linux-android24-clang", "llvm-ar").forEach { File(bin, it).writeText("") }
        return File(pkg, "ndk")
    }

    /** `pypackpack python install 3.15t aarch64-linux-android` puts the target Python at `<project>/android_arm64`. */
    private fun androidPython(dirName: String = "android_arm64"): File {
        val lib = File(pkg, "$dirName/prefix/lib")
        File(lib, "python3.15t/_sysconfigdata_t_android_aarch64-linux-android.py").apply { parentFile.mkdirs() }
            .writeText("build_time_vars = {'LDLIBRARY': 'libpython3.15t.so', 'Py_GIL_DISABLED': 1}\n")
        File(lib, "libpython3.15t.so").writeText("")
        return lib
    }

    @Test
    fun `android cross-builds with the NDK's clang against the target Python, linking its libpython`() {
        pyproject()
        write("src/main/core/__init__.py")
        crate("src/main/core/_fast")
        sitePackages.mkdirs()
        val ndk = fakeNdk()
        val lib = androidPython()
        val calls = mutableListOf<List<String>>()
        val envs = mutableListOf<Map<String, String>>()

        val placed = Cargo(run = fakeMaturin("_fast/_fast.abi3t.so", calls = calls, envs = envs), environment = available(ndk = ndk))
            .build(pkg, "aarch64-linux-android", "debug", destdir).getOrThrow()

        val command = calls.single()
        assertEquals(listOf("--target", "aarch64-linux-android"), command.subList(command.indexOf("--target"), command.indexOf("--target") + 2))
        assertFalse("--interpreter" in command, "the target Python is given through PYO3_CROSS_LIB_DIR, not run")
        assertTrue(command.containsAll(listOf("--features", PYO3_ABI3T_FEATURE)), "$command")
        val bin = File(ndk, "toolchains/llvm/prebuilt/${ndkHostTag()}/bin")
        val env = envs.single()
        assertEquals(lib.path, env["PYO3_CROSS_LIB_DIR"])
        assertEquals("3.15", env["PYO3_CROSS_PYTHON_VERSION"])
        assertEquals(File(bin, "aarch64-linux-android24-clang").path, env["CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER"])
        assertEquals(File(bin, "aarch64-linux-android24-clang").path, env["CC_aarch64_linux_android"])
        assertEquals(File(bin, "llvm-ar").path, env["AR_aarch64_linux_android"])
        // CPython's own Android extensions need libpython3.15t.so; PyO3's abi3 link name (python3) is dropped.
        assertEquals("-C link-arg=-L${lib.path} -C link-arg=-lpython3.15t", env["CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS"])
        assertEquals(listOf(File(sitePackages, "core/_fast.abi3t.so")), placed)
    }

    @Test
    fun `android without its Rust target, an NDK or a target Python is refused before maturin runs`() {
        pyproject()
        write("src/main/core/__init__.py")
        crate("src/main/core/_fast")
        sitePackages.mkdirs()
        val calls = mutableListOf<List<String>>()
        val maturin = fakeMaturin("_fast/_fast.abi3t.so", calls = calls)
        val ndk = fakeNdk()
        androidPython()

        val noTarget = Cargo(run = maturin, environment = available(ndk = ndk, rustTargets = emptySet()))
            .build(pkg, "aarch64-linux-android", "debug", destdir).exceptionOrNull()?.message.orEmpty()
        assertTrue("rustup target add aarch64-linux-android" in noTarget, noTarget)

        val noNdk = Cargo(run = maturin, environment = available(ndk = null))
            .build(pkg, "aarch64-linux-android", "debug", destdir).exceptionOrNull()?.message.orEmpty()
        assertTrue("ANDROID_NDK_HOME" in noNdk && "ANDROID_HOME" in noNdk, noNdk)

        File(pkg, "android_arm64").deleteRecursively()
        val noPython = Cargo(run = maturin, environment = available(ndk = ndk))
            .build(pkg, "aarch64-linux-android", "debug", destdir).exceptionOrNull()?.message.orEmpty()
        assertTrue("android_arm64" in noPython && "python install 3.15t aarch64-linux-android" in noPython, noPython)

        assertTrue(calls.isEmpty(), "$calls")
    }

    @Test
    fun `the NDK is ANDROID_NDK_HOME, else the newest under ANDROID_HOME's ndk directory`() {
        val sdk = File(pkg, "sdk")
        listOf("27.0.1", "29.0.14206865").forEach { File(sdk, "ndk/$it/toolchains/llvm/prebuilt").mkdirs() }
        File(sdk, "ndk/30.0.broken").mkdirs()

        assertEquals(File(pkg, "own"), CargoEnvironment.locateNdk(mapOf("ANDROID_NDK_HOME" to File(pkg, "own").path, "ANDROID_HOME" to sdk.path)))
        assertEquals(File(sdk, "ndk/29.0.14206865"), CargoEnvironment.locateNdk(mapOf("ANDROID_HOME" to sdk.path)))
        assertEquals(File(sdk, "ndk/29.0.14206865"), CargoEnvironment.locateNdk(mapOf("ANDROID_SDK_ROOT" to sdk.path)))
        assertEquals(null, CargoEnvironment.locateNdk(emptyMap()))
    }

    // Stage 2: iOS (#94). maturin builds iOS only inside a cross virtual environment, which python.org's
    // XCframework does not provide, so iOS takes SPEC's fallback: cargo directly, with pypackpack's PyO3 config.

    /** `pypackpack python install 3.15t <ios target>` extracts the XCframework into `<project>/<dir>`. */
    private fun iosPython(dirName: String, slice: String, libDir: String, freeThreaded: Boolean = true): File {
        val sliceDir = File(pkg, "$dirName/Python.xcframework/$slice")
        File(sliceDir, "$libDir/python3.15t/_sysconfigdata_t_ios.py").apply { parentFile.mkdirs() }
            .writeText("build_time_vars = {'LDLIBRARY': 'Python.framework/Python', 'Py_GIL_DISABLED': ${if (freeThreaded) 1 else 0}}\n")
        File(sliceDir, "Python.framework").mkdirs()
        return sliceDir
    }

    /** A cargo that leaves `lib<name>.dylib` where `cargo rustc --target <t> --target-dir <d>` puts it. */
    private fun fakeCargo(
        name: String,
        calls: MutableList<List<String>> = mutableListOf(),
        envs: MutableList<Map<String, String>> = mutableListOf(),
    ): (List<String>, File, Map<String, String>) -> Result<String> =
        { command, _, env ->
            calls += command
            envs += env
            val profile = if ("--release" in command) "release" else "debug"
            File(command[command.indexOf("--target-dir") + 1], "${command[command.indexOf("--target") + 1]}/$profile/lib$name.dylib")
                .apply { parentFile.mkdirs(); writeText("dylib") }
            Result.success("built")
        }

    @Test
    fun `ios builds with cargo against the device slice, linking Python framework, with its own PyO3 config`() {
        pyproject()
        write("src/main/core/__init__.py")
        crate("src/main/core/_fast")
        sitePackages.mkdirs()
        val slice = iosPython("ios_arm64", "ios-arm64", "lib-arm64")
        val calls = mutableListOf<List<String>>()
        val envs = mutableListOf<Map<String, String>>()

        val placed = Cargo(run = fakeCargo("_fast", calls, envs), environment = available())
            .build(pkg, "arm64-apple-ios", "release", destdir).getOrThrow()

        val targetDir = File(pkg, "build/packpack/cargo/target")
        assertEquals(
            listOf(
                "cargo", "rustc", "--manifest-path", File(pkg, "src/main/core/_fast/Cargo.toml").path, "--lib",
                "--target", "aarch64-apple-ios", "--target-dir", targetDir.path, "--features", PYO3_ABI3T_FEATURE, "--release",
                "--", "-C", "link-arg=-F${slice.path}", "-C", "link-arg=-framework", "-C", "link-arg=Python",
                "-C", "link-arg=-Wl,-install_name,@rpath/_fast.abi3t.so",
            ),
            calls.single(),
        )
        val env = envs.single()
        assertEquals("13.0", env["IPHONEOS_DEPLOYMENT_TARGET"])
        val config = File(env.getValue("PYO3_CONFIG_FILE")).readText()
        listOf("implementation=CPython", "version=3.15", "target_abi=CPython-abi3t-3.15", "shared=true", "build_flags=Py_GIL_DISABLED", "suppress_build_script_link_lines=true")
            .forEach { assertTrue(it in config.lines(), "$it in\n$config") }
        assertEquals(listOf(File(sitePackages, "core/_fast.abi3t.so")), placed)
        assertEquals("dylib", placed.single().readText())
    }

    @Test
    fun `the ios simulators use the simulator slice and their own Rust targets`() {
        pyproject()
        write("src/main/core/__init__.py")
        crate("src/main/core/_fast")
        sitePackages.mkdirs()
        val arm = iosPython("ios_simulator_arm64", "ios-arm64_x86_64-simulator", "lib-arm64")
        iosPython("ios_simulator_x86_64", "ios-arm64_x86_64-simulator", "lib-x86_64")
        val calls = mutableListOf<List<String>>()

        Cargo(run = fakeCargo("_fast", calls), environment = available()).build(pkg, "arm64-apple-ios-simulator", "debug", destdir).getOrThrow()
        Cargo(run = fakeCargo("_fast", calls), environment = available()).build(pkg, "x86_64-apple-ios-simulator", "debug", destdir).getOrThrow()

        assertEquals(listOf("aarch64-apple-ios-sim", "x86_64-apple-ios"), calls.map { it[it.indexOf("--target") + 1] })
        assertTrue("link-arg=-F${arm.path}" in calls.first(), "${calls.first()}")
    }

    @Test
    fun `a GIL ios Python leaves the free-threaded build flag out`() {
        pyproject()
        write("src/main/core/__init__.py")
        crate("src/main/core/_fast")
        sitePackages.mkdirs()
        iosPython("ios_arm64", "ios-arm64", "lib-arm64", freeThreaded = false)
        val envs = mutableListOf<Map<String, String>>()

        Cargo(run = fakeCargo("_fast", envs = envs), environment = available()).build(pkg, "arm64-apple-ios", "debug", destdir).getOrThrow()

        assertTrue("build_flags=" in File(envs.single().getValue("PYO3_CONFIG_FILE")).readText().lines())
    }

    @Test
    fun `ios without Xcode, its Rust target or the XCframework, or with another lib name, is refused`() {
        pyproject()
        write("src/main/core/__init__.py")
        crate("src/main/core/_fast")
        sitePackages.mkdirs()
        iosPython("ios_arm64", "ios-arm64", "lib-arm64")
        val calls = mutableListOf<List<String>>()

        val noXcode = Cargo(run = fakeCargo("_fast", calls), environment = available(xcode = false))
            .build(pkg, "arm64-apple-ios", "debug", destdir).exceptionOrNull()?.message.orEmpty()
        assertTrue("Xcode" in noXcode, noXcode)

        val noTarget = Cargo(run = fakeCargo("_fast", calls), environment = available(rustTargets = emptySet()))
            .build(pkg, "arm64-apple-ios", "debug", destdir).exceptionOrNull()?.message.orEmpty()
        assertTrue("rustup target add aarch64-apple-ios" in noTarget, noTarget)
        assertTrue(calls.isEmpty(), "$calls")

        val wrongName = Cargo(run = fakeCargo("fast"), environment = available())
            .build(pkg, "arm64-apple-ios", "debug", destdir).exceptionOrNull()?.message.orEmpty()
        assertTrue("[lib] name = \"_fast\"" in wrongName, wrongName)

        File(pkg, "ios_arm64").deleteRecursively()
        val noPython = Cargo(run = fakeCargo("_fast", calls), environment = available())
            .build(pkg, "arm64-apple-ios", "debug", destdir).exceptionOrNull()?.message.orEmpty()
        assertTrue("ios_arm64" in noPython && "python install 3.15t arm64-apple-ios" in noPython, noPython)
    }

    /** The previous maturin output is cleared without following a link out of it (#96). */
    @Test
    fun `clearing the maturin output does not follow a link out of it`() {
        pyproject()
        write("src/main/core/__init__.py")
        crate("src/main/core/_fast")
        venv()
        sitePackages.mkdirs()
        val outside = File(pkg, "outside").apply { mkdirs() }
        File(outside, "keep.txt").writeText("keep")
        val out = File(pkg, "build/packpack/cargo/$host/core._fast").apply { mkdirs() }
        java.nio.file.Files.createSymbolicLink(File(out, "link").toPath(), outside.toPath())

        Cargo(run = fakeMaturin("_fast/_fast.abi3t.so"), environment = available()).build(pkg, null, "debug", destdir).getOrThrow()

        assertEquals("keep", File(outside, "keep.txt").readText())
    }

    @Test
    fun `a failed maturin build fails with its output`() {
        pyproject()
        write("src/main/core/__init__.py")
        crate("src/main/core/_fast")
        venv()
        sitePackages.mkdirs()

        val message = Cargo(run = { _, _, _ -> Result.failure(IllegalStateException("error[E0433]")) }, environment = available())
            .build(pkg, null, "debug", destdir).exceptionOrNull()?.message.orEmpty()

        assertTrue("core._fast" in message && "error[E0433]" in message, message)
    }
}
