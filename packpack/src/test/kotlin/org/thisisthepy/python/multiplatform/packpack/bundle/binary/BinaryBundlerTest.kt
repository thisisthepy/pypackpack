package org.thisisthepy.python.multiplatform.packpack.bundle.binary

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import org.thisisthepy.python.multiplatform.packpack.bundle.BundleRequest
import org.thisisthepy.python.multiplatform.packpack.utils.Platforms
import org.thisisthepy.python.multiplatform.packpack.utils.extractArchive
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The `binary` bundle (docs/SPEC.md *Bundle Type*, #156). The runtime installer is a test double: on the
 * host it links the host's `python3` as the runtime's `bin/python3`, so the launcher really runs; on the
 * other targets it writes a stand-in interpreter file. No network.
 */
class BinaryBundlerTest {
    @TempDir
    lateinit var tempDir: File

    private val host: String = Platforms.detectHostTarget()
    private val hostFamily: String = Platforms.getPlatformFamily(host)
    private val installs = mutableListOf<Triple<String, String, File>>()

    private fun hostPython3(): File {
        val process = ProcessBuilder("python3", "-c", "import sys; print(sys.executable)").redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText().trim()
        check(process.waitFor() == 0 && out.isNotEmpty()) { "python3 is needed on PATH: $out" }
        return File(out)
    }

    private fun installer(fail: String? = null, interpreter: Boolean = true): (String, String, File) -> Result<Unit> = { version, target, dir ->
        installs += Triple(version, target, dir)
        if (fail != null) {
            Result.failure(IllegalStateException(fail))
        } else {
            val family = Platforms.getPlatformFamily(target)
            File(dir, "lib/python3.15t/os.py").apply { parentFile.mkdirs(); writeText("# stdlib stand-in\n") }
            if (interpreter) {
                if (family == "windows") {
                    File(dir, "python.exe").writeText("MZ")
                } else if (target == host) {
                    val bin = File(dir, "bin").apply { mkdirs() }
                    Files.createSymbolicLink(File(bin, "python3").toPath(), hostPython3().toPath())
                } else {
                    File(dir, "bin/python3.15t").apply { parentFile.mkdirs(); writeText("ELF"); setExecutable(true) }
                }
            }
            Result.success(Unit)
        }
    }

    private fun project(scripts: String? = "demo = \"core.cli:main\"", target: String = host): File {
        val pkg = File(tempDir, "core").apply { mkdirs() }
        File(pkg, "pyproject.toml").writeText(
            "[project]\nname = \"core\"\nversion = \"1.2.3\"\n" + (scripts?.let { "\n[project.scripts]\n$it\n" } ?: ""),
        )
        val site = File(pkg, "dist/$target/debug/instant/usr/local/lib/python3.15t/site-packages")
        File(site, "core/__init__.py").apply { parentFile.mkdirs(); writeText("") }
        File(site, "core/cli.py").writeText(
            "import sys\nimport six\n\ndef main():\n    print('hello', six.NAME, *sys.argv[1:])\n    return 3\n",
        )
        File(site, "core/__main__.py").writeText("print('as a module')\n")
        File(pkg, "build/crossenv/$target/six.py").apply { parentFile.mkdirs(); writeText("NAME = 'six'\n") }
        return pkg
    }

    private fun run(launcher: File, vararg args: String): Pair<Int, String> {
        val process = ProcessBuilder(listOf(launcher.absolutePath) + args).directory(tempDir).redirectErrorStream(true)
            .apply { environment().remove("PYTHONPATH"); environment().remove("PYTHONHOME") }.start()
        val out = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "the launcher did not finish")
        return process.exitValue() to out
    }

    @Test
    fun hostBundleLaysOutRuntimeAppAndALauncherThatRunsTheScriptEntryPoint() {
        assumeTrue(hostFamily == "macos" || hostFamily == "linux", "the sh launcher runs on macOS and Linux hosts")
        val pkg = project()

        val result = BinaryBundler(installer()).bundle(BundleRequest(packageDir = pkg, target = host)).getOrThrow()

        val root = result.outputDir
        assertEquals(File(pkg, "build/packpack/binary/debug/instant/$host/core").canonicalFile, root.canonicalFile)
        assertTrue(File(root, "runtime/lib/python3.15t/os.py").isFile)
        assertTrue(File(root, "app/core/cli.py").isFile && File(root, "app/six.py").isFile, "package and dependencies in app/")
        val launcher = File(root, "bin/core")
        assertTrue(launcher.canExecute(), "the launcher is executable")
        val manifest = result.manifestFile.readText()
        assertTrue("\"entryPoint\": \"core.cli:main\"" in manifest && "\"launcher\": \"bin/core\"" in manifest, manifest)

        val (exit, out) = run(launcher, "a b", "c")
        assertEquals(3, exit, out)
        assertEquals("hello six a b c", out.trim())
        assertEquals(listOf(Triple("3.15t", host, File(pkg, "build/packpack/runtime/$host/3.15t").canonicalFile)), installs.map { Triple(it.first, it.second, it.third.canonicalFile) })
    }

    @Test
    fun withoutProjectScriptsTheLauncherRunsThePackageAsAModuleAndTheRuntimeIsInstalledOnce() {
        assumeTrue(hostFamily == "macos" || hostFamily == "linux", "the sh launcher runs on macOS and Linux hosts")
        val pkg = project(scripts = null)
        val bundler = BinaryBundler(installer())

        bundler.bundle(BundleRequest(packageDir = pkg, target = host)).getOrThrow()
        val second = bundler.bundle(BundleRequest(packageDir = pkg, target = host, overwrite = true)).getOrThrow()

        assertEquals(1, installs.size, "the runtime is installed once and reused")
        assertEquals("as a module", run(File(second.outputDir, "bin/core")).second.trim())
    }

    @Test
    fun theTarGzKeepsTheLauncherExecutableAndTheRuntimeLinks() {
        assumeTrue(hostFamily == "macos" || hostFamily == "linux", "symbolic links and the sh launcher")
        val pkg = project()

        val result = BinaryBundler(installer()).bundle(BundleRequest(packageDir = pkg, target = host)).getOrThrow()

        val archive = result.artifactFile!!
        assertEquals("core-1.2.3-$host.tar.gz", archive.name)
        val out = File(tempDir, "extracted").apply { mkdirs() }
        extractArchive(archive, out)
        val launcher = File(out, "core/bin/core")
        assertTrue(launcher.canExecute(), "the extracted launcher keeps its executable bit")
        assertTrue(Files.isSymbolicLink(File(out, "core/runtime/bin/python3").toPath()), "the runtime's link stays a link")
        assertEquals(3, run(launcher).first, "the extracted bundle runs")
    }

    @Test
    fun windowsGetsACmdLauncherAndAZip() {
        val target = "x86_64-pc-windows-msvc"
        val pkg = project(target = target)

        val result = BinaryBundler(installer()).bundle(BundleRequest(packageDir = pkg, target = target)).getOrThrow()

        val cmd = File(result.outputDir, "core.cmd").readText()
        assertTrue("set \"PYTHONPATH=%~dp0app\"" in cmd && "\"%~dp0runtime\\python.exe\" -s -c" in cmd && "%*" in cmd, cmd)
        assertEquals("core-1.2.3-$target.zip", result.artifactFile!!.name)
        val names = ZipFile(result.artifactFile).use { zip -> zip.entries().asSequence().map { it.name }.toList() }
        assertTrue("core/core.cmd" in names && "core/app/core/cli.py" in names && "core/runtime/python.exe" in names, names.toString())
        assertEquals(names.sorted(), names, "entries are sorted")
    }

    @Test
    fun mobileTargetsGetTheLayoutWithoutALauncher() {
        val target = "aarch64-linux-android"
        val pkg = project(target = target)

        val result = BinaryBundler(installer()).bundle(BundleRequest(packageDir = pkg, target = target)).getOrThrow()

        assertFalse(File(result.outputDir, "bin").exists())
        assertTrue("\"launcher\": null" in result.manifestFile.readText())
        assertTrue(File(result.outputDir, "runtime/bin/python3.15t").isFile && File(result.outputDir, "app/six.py").isFile)
        assertEquals("core-1.2.3-$target.tar.gz", result.artifactFile!!.name)
    }

    @Test
    fun refusesWithoutCompiledOutputNamingTheBuildCommand() {
        val pkg = File(tempDir, "core").apply { mkdirs() }
        File(pkg, "pyproject.toml").writeText("[project]\nname = \"core\"\nversion = \"1.0\"\n")

        val result = BinaryBundler(installer()).bundle(BundleRequest(packageDir = pkg, target = "aarch64-linux-android"))

        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue("No compiled output found" in message && "pypackpack build core" in message, message)
        assertTrue(installs.isEmpty(), "nothing installed before the input is checked")
    }

    @Test
    fun refusesAPathInBothThePackageAndItsDependencies() {
        val target = "aarch64-linux-android"
        val pkg = project(target = target)
        File(pkg, "build/crossenv/$target/core/cli.py").apply { parentFile.mkdirs(); writeText("# clash\n") }

        val message = BinaryBundler(installer()).bundle(BundleRequest(packageDir = pkg, target = target)).exceptionOrNull()?.message.orEmpty()

        assertTrue("core/cli.py" in message && "refusing" in message, message)
    }

    @Test
    fun refusesARuntimeThatCannotBeInstalledOrHasNoInterpreter() {
        val target = "aarch64-linux-android"
        val pkg = project(target = target)

        val failed = BinaryBundler(installer(fail = "no pinned archive")).bundle(BundleRequest(packageDir = pkg, target = target))
        assertTrue("Could not install CPython 3.15t for $target: no pinned archive" in failed.exceptionOrNull()?.message.orEmpty())

        val empty = BinaryBundler(installer(interpreter = false)).bundle(BundleRequest(packageDir = pkg, target = target))
        assertTrue("has no interpreter" in empty.exceptionOrNull()?.message.orEmpty(), empty.exceptionOrNull()?.message)
    }
}
