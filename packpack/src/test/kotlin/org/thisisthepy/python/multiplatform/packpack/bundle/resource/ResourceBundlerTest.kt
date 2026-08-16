package org.thisisthepy.python.multiplatform.packpack.bundle.resource

import org.junit.jupiter.api.io.TempDir
import org.thisisthepy.python.multiplatform.packpack.bundle.BundlerInterface
import org.thisisthepy.python.multiplatform.packpack.bundle.BundleRequest
import org.thisisthepy.python.multiplatform.packpack.bundle.BundleType
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Specification tests for bundle type `resource`.
 *
 * Spec source: `docs/SPEC.md` -> "Bundle Type" -> "`resource`: Produces a resource layout consumable
 * by python-multiplatform/toolchain", plus the output-path convention
 * `<package>/build/packpack/<bundle type>/<build type>/<build level>` from SPEC's "User directory
 * structure", and the platform source overlay `<package>/src/{main,<family>}` from the same section.
 *
 * Everything the SPEC does not state (manifest file name, manifest fields, `python/` payload root,
 * exclusion rules, which build levels are supported) is an assumption recorded in
 * `ResourceBundler`'s KDoc; these tests pin those assumptions down so a later change to them is a
 * visible, deliberate edit rather than a silent drift.
 */
class ResourceBundlerTest {
    @TempDir
    lateinit var tempDir: File

    private fun packageDir(
        name: String = "core",
        version: String = "0.1.0",
    ): File {
        val dir = File(tempDir, name)
        dir.mkdirs()
        File(dir, "pyproject.toml").writeText(
            """
            [project]
            name = "$name"
            version = "$version"
            """.trimIndent(),
        )
        return dir
    }

    private fun write(
        root: File,
        relativePath: String,
        content: String,
    ) {
        val file = File(root, relativePath)
        file.parentFile.mkdirs()
        file.writeText(content)
    }

    private fun sha256(text: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun bundler() = BundlerInterface.create(BundleType.RESOURCE)

    @Test
    fun create_returnsResourceBundlerForResourceType() {
        assertTrue(BundlerInterface.create(BundleType.RESOURCE) is ResourceBundler)
    }

    @Test
    fun bundle_writesPayloadUnderConventionalOutputDirectory() {
        val pkg = packageDir()
        write(pkg, "src/main/core/__init__.py", "VERSION = '0.1.0'\n")
        write(pkg, "src/main/core/api.py", "def ping():\n    return 'pong'\n")

        val result =
            bundler()
                .bundle(BundleRequest(packageDir = pkg, target = "macos"))
                .also { assertTrue(it.isSuccess, it.exceptionOrNull()?.stackTraceToString()) }
                .getOrThrow()

        assertEquals(
            File(pkg, "build/packpack/resource/debug/instant").canonicalFile,
            result.outputDir.canonicalFile,
        )
        assertEquals(
            "def ping():\n    return 'pong'\n",
            File(result.outputDir, "python/core/api.py").readText(),
        )
        assertEquals(
            "VERSION = '0.1.0'\n",
            File(result.outputDir, "python/core/__init__.py").readText(),
        )
        assertTrue(result.manifestFile.isFile, "manifest not written: ${result.manifestFile}")
        assertEquals(BundleType.RESOURCE, result.bundleType)
        assertEquals(2, result.fileCount)
    }

    @Test
    fun bundle_manifestRecordsIdentityTargetAndPerFileDigests() {
        val pkg = packageDir(name = "core", version = "1.2.3")
        val source = "def ping():\n    return 'pong'\n"
        write(pkg, "src/main/core/__init__.py", source)

        val result = bundler().bundle(BundleRequest(packageDir = pkg, target = "macos")).getOrThrow()
        val manifest = result.manifestFile.readText()

        assertTrue(manifest.contains("\"formatVersion\": 1"), manifest)
        assertTrue(manifest.contains("\"packageName\": \"core\""), manifest)
        assertTrue(manifest.contains("\"version\": \"1.2.3\""), manifest)
        assertTrue(manifest.contains("\"bundleType\": \"resource\""), manifest)
        assertTrue(manifest.contains("\"target\": \"aarch64-apple-darwin\""), manifest)
        assertTrue(manifest.contains("\"platformFamily\": \"macos\""), manifest)
        assertTrue(manifest.contains("\"buildType\": \"debug\""), manifest)
        assertTrue(manifest.contains("\"buildLevel\": \"instant\""), manifest)
        assertTrue(manifest.contains("\"pythonRoot\": \"python\""), manifest)
        assertTrue(manifest.contains("\"path\": \"python/core/__init__.py\""), manifest)
        assertTrue(manifest.contains("\"size\": ${source.toByteArray().size}"), manifest)
        assertTrue(manifest.contains("\"sha256\": \"${sha256(source)}\""), manifest)
    }

    @Test
    fun bundle_manifestIsValidJsonWithEscapedStrings() {
        val pkg = packageDir()
        write(pkg, "src/main/core/__init__.py", "")
        write(pkg, "src/main/core/quote\"name.txt", "x")

        val manifest = bundler().bundle(BundleRequest(packageDir = pkg, target = "macos")).getOrThrow().manifestFile.readText()

        assertTrue(manifest.contains("""python/core/quote\"name.txt"""), manifest)
        assertBalancedJson(manifest)
    }

    @Test
    fun bundle_overlaysPlatformSpecificSourcesOverCommonOnes() {
        val pkg = packageDir()
        write(pkg, "src/main/core/__init__.py", "")
        write(pkg, "src/main/core/api.py", "PLATFORM = 'common'\n")
        write(pkg, "src/main/core/common_only.py", "COMMON = True\n")
        write(pkg, "src/android/core/api.py", "PLATFORM = 'android'\n")
        write(pkg, "src/android/core/android_only.py", "ANDROID = True\n")
        write(pkg, "src/windows/core/api.py", "PLATFORM = 'windows'\n")

        val result = bundler().bundle(BundleRequest(packageDir = pkg, target = "aarch64-linux-android")).getOrThrow()

        assertEquals("PLATFORM = 'android'\n", File(result.outputDir, "python/core/api.py").readText())
        assertEquals("COMMON = True\n", File(result.outputDir, "python/core/common_only.py").readText())
        assertEquals("ANDROID = True\n", File(result.outputDir, "python/core/android_only.py").readText())
        assertFalse(
            File(result.outputDir, "python/core/windows").exists(),
            "windows-only overlay must not be bundled for an android target",
        )
        assertTrue(result.manifestFile.readText().contains("\"platformFamily\": \"android\""))
    }

    /**
     * `BundleRequest.minSdk` is `toolchain`'s per-variant Android min SDK/API level (`f60bc3b` in
     * `toolchain`), which had nowhere to go before this field existed. `ResourceBundler` is the
     * bundler `toolchain`'s `bundleWithPackpack` actually calls today (`BundleType.RESOURCE`), so
     * recording the declared value in the manifest is what lets a downstream consumer (staging into
     * Android assets, for instance) read it back without `toolchain` inventing its own side channel.
     */
    @Test
    fun bundle_manifestRecordsDeclaredMinSdkForAnAndroidTarget() {
        val pkg = packageDir()
        write(pkg, "src/main/core/__init__.py", "")

        val result =
            bundler()
                .bundle(BundleRequest(packageDir = pkg, target = "aarch64-linux-android", minSdk = 24))
                .getOrThrow()

        assertTrue(result.manifestFile.readText().contains("\"minSdk\": 24"))
    }

    @Test
    fun bundle_manifestOmitsMinSdkWhenNotDeclared() {
        val pkg = packageDir()
        write(pkg, "src/main/core/__init__.py", "")

        val result = bundler().bundle(BundleRequest(packageDir = pkg, target = "macos")).getOrThrow()

        assertFalse(result.manifestFile.readText().contains("\"minSdk\""))
    }

    @Test
    fun bundle_rejectsMinSdkDeclaredForANonAndroidTarget() {
        val pkg = packageDir()
        write(pkg, "src/main/core/__init__.py", "")

        val result = bundler().bundle(BundleRequest(packageDir = pkg, target = "macos", minSdk = 24))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("macos"), result.exceptionOrNull()?.message)
    }

    @Test
    fun bundle_keepsNonPythonResourceFilesButDropsCompiledAndEditorArtifacts() {
        val pkg = packageDir()
        write(pkg, "src/main/core/__init__.py", "")
        write(pkg, "src/main/core/data/config.json", "{\"a\": 1}")
        write(pkg, "src/main/core/py.typed", "")
        write(pkg, "src/main/core/api.pyi", "def ping() -> str: ...\n")
        write(pkg, "src/main/core/__pycache__/api.cpython-313.pyc", "compiled")
        write(pkg, "src/main/core/stale.pyc", "compiled")
        write(pkg, "src/main/core/.DS_Store", "junk")

        val result = bundler().bundle(BundleRequest(packageDir = pkg, target = "macos")).getOrThrow()

        assertEquals("{\"a\": 1}", File(result.outputDir, "python/core/data/config.json").readText())
        assertTrue(File(result.outputDir, "python/core/py.typed").isFile)
        assertTrue(File(result.outputDir, "python/core/api.pyi").isFile)
        assertFalse(File(result.outputDir, "python/core/__pycache__").exists())
        assertFalse(File(result.outputDir, "python/core/stale.pyc").exists())
        assertFalse(File(result.outputDir, "python/core/.DS_Store").exists())
        assertEquals(4, result.fileCount)
    }

    @Test
    fun bundle_isDeterministicAcrossRuns() {
        val pkg = packageDir()
        write(pkg, "src/main/core/__init__.py", "")
        write(pkg, "src/main/core/b.py", "B = 1\n")
        write(pkg, "src/main/core/a.py", "A = 1\n")

        val first = bundler().bundle(BundleRequest(packageDir = pkg, target = "macos", overwrite = true)).getOrThrow()
        val firstManifest = first.manifestFile.readText()
        val second = bundler().bundle(BundleRequest(packageDir = pkg, target = "macos", overwrite = true)).getOrThrow()

        assertEquals(firstManifest, second.manifestFile.readText())
        assertTrue(
            firstManifest.indexOf("python/core/a.py") < firstManifest.indexOf("python/core/b.py"),
            "manifest file list must be sorted by path",
        )
    }

    @Test
    fun bundle_refusesToOverwriteExistingOutputUnlessAsked() {
        val pkg = packageDir()
        write(pkg, "src/main/core/__init__.py", "")
        bundler().bundle(BundleRequest(packageDir = pkg, target = "macos")).getOrThrow()

        val second = bundler().bundle(BundleRequest(packageDir = pkg, target = "macos"))

        assertTrue(second.isFailure)
        assertTrue(
            second.exceptionOrNull()?.message.orEmpty().contains("--overwrite"),
            "message should tell the user how to proceed: ${second.exceptionOrNull()?.message}",
        )
    }

    @Test
    fun bundle_overwriteRemovesStaleFilesFromPreviousRun() {
        val pkg = packageDir()
        write(pkg, "src/main/core/__init__.py", "")
        write(pkg, "src/main/core/gone.py", "GONE = True\n")
        val first = bundler().bundle(BundleRequest(packageDir = pkg, target = "macos")).getOrThrow()
        assertTrue(File(first.outputDir, "python/core/gone.py").isFile)
        File(pkg, "src/main/core/gone.py").delete()

        val second = bundler().bundle(BundleRequest(packageDir = pkg, target = "macos", overwrite = true)).getOrThrow()

        assertFalse(File(second.outputDir, "python/core/gone.py").exists(), "stale file survived --overwrite")
        assertEquals(1, second.fileCount)
    }

    @Test
    fun bundle_rejectsBuildLevelsThatAreNotImplementedYet() {
        val pkg = packageDir()
        write(pkg, "src/main/core/__init__.py", "")

        // "bytecode" used to be in this list; it is implemented now (below), so it moved out --
        // see `bundle_bytecodeLevel*`.
        listOf("native", "mixed").forEach { level ->
            val result = bundler().bundle(BundleRequest(packageDir = pkg, target = "macos", buildLevel = level))
            assertTrue(result.isFailure, "build level '$level' should not silently succeed")
            val message = result.exceptionOrNull()?.message.orEmpty()
            assertTrue(message.contains(level), message)
        }
    }

    /**
     * Finds a real Python 3 interpreter on the host to drive `compileall` with -- the same way
     * `SingleWheelBundlerTest` writes a synthetic Meson destdir instead of shelling out to `meson`,
     * these tests need an interpreter that actually exists rather than a fixture, because `compileall`
     * is a real subprocess call. `which` is POSIX-only, matching every other host-tool probe already
     * in this codebase (`Meson.isMesonInstalled`), which does not attempt Windows support either.
     */
    private fun hostPython3(): File {
        val process = ProcessBuilder("which", "python3").redirectErrorStream(true).start()
        val path = process.inputStream.bufferedReader().readText().trim()
        val exit = process.waitFor()
        require(exit == 0 && path.isNotEmpty()) { "No 'python3' found on PATH; cannot exercise the 'bytecode' build level." }
        return File(path)
    }

    /**
     * `ResourceBundler`'s `bytecode` level looks for an interpreter under `<project>/.venv`, matching
     * `docs/SPEC.md`'s directory structure (`<project>/.venv`). Symlinking the real host interpreter
     * into that conventional location is what lets a test both exercise the real lookup path and
     * produce a real, importable `.pyc`.
     */
    private fun installVenvPython(pkg: File): File {
        val venvBin = File(pkg, ".venv/bin")
        venvBin.mkdirs()
        val target = File(venvBin, "python3")
        Files.createSymbolicLink(target.toPath(), hostPython3().toPath())
        return target
    }

    /**
     * SPEC's `bytecode(.py+.pyc)` for debug / `bytecode(.pyc)` for release (SPEC's "User directory
     * structure") is a real behavioral difference this pins down: debug keeps the source alongside
     * the compiled bytecode (so a stack trace still shows source), release strips the source.
     */
    @Test
    fun bundle_bytecodeLevelKeepsBothPyAndPycForDebugBuildType() {
        val pkg = packageDir()
        installVenvPython(pkg)
        write(pkg, "src/main/core/__init__.py", "def ping():\n    return 'pong'\n")

        val result =
            bundler()
                .bundle(BundleRequest(packageDir = pkg, target = "macos", buildLevel = "bytecode", buildType = "debug"))
                .also { assertTrue(it.isSuccess, it.exceptionOrNull()?.stackTraceToString()) }
                .getOrThrow()

        assertTrue(File(result.outputDir, "python/core/__init__.py").isFile, "debug bytecode level must keep the .py source")
        assertTrue(File(result.outputDir, "python/core/__init__.pyc").isFile, "debug bytecode level must produce a .pyc")
        assertTrue(result.manifestFile.readText().contains("\"path\": \"python/core/__init__.pyc\""))
        assertTrue(result.manifestFile.readText().contains("\"buildLevel\": \"bytecode\""))
    }

    @Test
    fun bundle_bytecodeLevelKeepsOnlyPycForReleaseBuildType() {
        val pkg = packageDir()
        installVenvPython(pkg)
        write(pkg, "src/main/core/__init__.py", "def ping():\n    return 'pong'\n")

        val result =
            bundler()
                .bundle(
                    BundleRequest(packageDir = pkg, target = "macos", buildLevel = "bytecode", buildType = "release"),
                ).also { assertTrue(it.isSuccess, it.exceptionOrNull()?.stackTraceToString()) }
                .getOrThrow()

        assertFalse(File(result.outputDir, "python/core/__init__.py").exists(), "release bytecode level must drop the .py source")
        assertTrue(File(result.outputDir, "python/core/__init__.pyc").isFile, "release bytecode level must keep the .pyc")
    }

    /** The `.pyc` this produces must actually be a compiled Python module, not a copy or a stub. */
    @Test
    fun bundle_bytecodeLevelProducesARealLoadablePycFile() {
        val pkg = packageDir()
        val python = installVenvPython(pkg)
        write(pkg, "src/main/core/__init__.py", "VALUE = 41 + 1\n")

        val result =
            bundler()
                .bundle(BundleRequest(packageDir = pkg, target = "macos", buildLevel = "bytecode", buildType = "release"))
                .getOrThrow()

        val pyc = File(result.outputDir, "python/core/__init__.pyc")
        val magic = pyc.inputStream().use { it.readNBytes(4) }
        assertTrue(magic.isNotEmpty() && magic[0].toInt() != 0, "not a real .pyc (empty/zeroed magic number): ${pyc.absolutePath}")

        // Actually import it with the same interpreter that compiled it, and read the value back --
        // proof this is loadable bytecode, not just four bytes that happen to look like a magic number.
        val process =
            ProcessBuilder(
                python.absolutePath,
                "-c",
                "import sys; sys.path.insert(0, ${'"'}${File(result.outputDir, "python").absolutePath}${'"'}); " +
                    "import core; print(core.VALUE)",
            ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        assertEquals(0, exit, "importing the produced .pyc failed:\n$output")
        assertEquals("42", output.trim())
    }

    @Test
    fun bundle_bytecodeLevelFailsClearlyWithoutAVenvInterpreter() {
        val pkg = packageDir()
        write(pkg, "src/main/core/__init__.py", "")
        // Deliberately no .venv installed.

        val result = bundler().bundle(BundleRequest(packageDir = pkg, target = "macos", buildLevel = "bytecode"))

        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(message.contains(".venv"), message)
    }

    @Test
    fun bundle_rejectsUnknownTarget() {
        val pkg = packageDir()
        write(pkg, "src/main/core/__init__.py", "")

        val result = bundler().bundle(BundleRequest(packageDir = pkg, target = "definitely-not-a-target"))

        assertTrue(result.isFailure)
        assertTrue(
            result.exceptionOrNull()?.message.orEmpty().contains("definitely-not-a-target"),
            result.exceptionOrNull()?.message,
        )
    }

    @Test
    fun bundle_failsWhenPackageHasNoPyprojectOrNoSources() {
        val noPyproject = File(tempDir, "nothing").apply { mkdirs() }
        val noSources = packageDir(name = "empty")

        val missingPyproject = bundler().bundle(BundleRequest(packageDir = noPyproject, target = "macos"))
        val missingSources = bundler().bundle(BundleRequest(packageDir = noSources, target = "macos"))

        assertTrue(missingPyproject.isFailure)
        assertTrue(missingPyproject.exceptionOrNull()?.message.orEmpty().contains("pyproject.toml"))
        assertTrue(missingSources.isFailure)
        assertNotNull(missingSources.exceptionOrNull()?.message)
    }

    @Test
    fun bundle_fallsBackToSrcWhenThereIsNoSrcMain() {
        val pkg = packageDir()
        write(pkg, "src/core/__init__.py", "FROM = 'src'\n")

        val result = bundler().bundle(BundleRequest(packageDir = pkg, target = "macos")).getOrThrow()

        assertEquals("FROM = 'src'\n", File(result.outputDir, "python/core/__init__.py").readText())
    }

    @Test
    fun bundle_honoursExplicitOutputDirectory() {
        val pkg = packageDir()
        write(pkg, "src/main/core/__init__.py", "")
        val custom = File(tempDir, "elsewhere/resource-out")

        val result =
            bundler()
                .bundle(BundleRequest(packageDir = pkg, target = "macos", outputDir = custom))
                .getOrThrow()

        assertEquals(custom.canonicalFile, result.outputDir.canonicalFile)
        assertTrue(File(custom, "python/core/__init__.py").isFile)
    }

    @Test
    fun create_reportsUnimplementedBundleTypesInsteadOfPretendingToWork() {
        // SINGLE (SingleWheelBundlerTest), FAT (FatWheelBundlerTest) and PATCH (WheelPatchBundlerTest)
        // are all implemented now; only BINARY remains a stub (see `BinaryBundler`'s own KDoc -- it is
        // blocked on every compiler backend under `compile/backend/external` still being an empty
        // placeholder file).
        listOf(BundleType.BINARY).forEach { type ->
            val result =
                BundlerInterface.create(type).bundle(
                    BundleRequest(packageDir = packageDir(name = "p-${type.name.lowercase()}"), target = "macos"),
                )
            assertTrue(result.isFailure, "$type must not report success while unimplemented")
            assertTrue(
                result.exceptionOrNull()?.message.orEmpty().contains("not implemented", ignoreCase = true),
                "$type: ${result.exceptionOrNull()?.message}",
            )
        }
    }

    /**
     * `FAT` and `PATCH` are now real bundlers (see `FatWheelBundlerTest`/`WheelPatchBundlerTest` for
     * their full specs), so they must fail for *their own* reasons -- not the generic "not
     * implemented" stub message -- when given a package with no compiled output at all.
     */
    @Test
    fun create_fatAndPatchFailForTheirOwnReasonsNotTheStubMessage() {
        val fatResult =
            BundlerInterface.create(BundleType.FAT).bundle(
                BundleRequest(packageDir = packageDir(name = "p-fat"), target = "macos"),
            )
        assertTrue(fatResult.isFailure)
        assertFalse(fatResult.exceptionOrNull()?.message.orEmpty().contains("not implemented", ignoreCase = true))

        val patchResult =
            BundlerInterface.create(BundleType.PATCH).bundle(
                BundleRequest(packageDir = packageDir(name = "p-patch"), target = "macos"),
            )
        assertTrue(patchResult.isFailure)
        assertFalse(patchResult.exceptionOrNull()?.message.orEmpty().contains("not implemented", ignoreCase = true))
    }

    /** Cheap structural check: quotes, braces and brackets balance outside of strings. */
    private fun assertBalancedJson(json: String) {
        var depth = 0
        var inString = false
        var escaped = false
        json.forEach { c ->
            when {
                escaped -> escaped = false
                inString && c == '\\' -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' || c == '[' -> depth++
                c == '}' || c == ']' -> depth--
            }
            assertTrue(depth >= 0, "unbalanced JSON: $json")
        }
        assertFalse(inString, "unterminated JSON string: $json")
        assertEquals(0, depth, "unbalanced JSON: $json")
    }
}
