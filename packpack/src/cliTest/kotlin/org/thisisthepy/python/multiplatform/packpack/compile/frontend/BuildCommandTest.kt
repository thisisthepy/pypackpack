package org.thisisthepy.python.multiplatform.packpack.compile.frontend

import com.github.ajalt.clikt.testing.test
import org.thisisthepy.python.multiplatform.packpack.bundle.BundleRequest
import org.thisisthepy.python.multiplatform.packpack.bundle.BundleResult
import org.thisisthepy.python.multiplatform.packpack.bundle.BundleType
import org.thisisthepy.python.multiplatform.packpack.bundle.BundlerInterface
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `build` takes the bundle as an argument (the maintainer's spec sheet, `spec.md` 686b1ad; SPEC *Package
 * build*; #81):
 *
 *     pypackpack build <package> source [<bundle type: default binary>] [--type] [--level] [--target]
 *     pypackpack build <package> resource [--target]
 *
 * `build <package>` alone still only compiles.
 */
class BuildCommandTest {
    private val workspace: File = Files.createTempDirectory("ppp-build-").toFile().canonicalFile
    private val packageDir = File(workspace, "packages/core")

    init {
        File(workspace, "pyproject.toml").writeText("[project]\nname = \"ws\"\n\n[tool.uv.workspace]\nmembers = [\"packages/core\"]\n")
        File(packageDir, "src/main/core").mkdirs()
        File(packageDir, "pyproject.toml").writeText("[project]\nname = \"core\"\nversion = \"0.1.0\"\n")
        File(packageDir, "src/main/core/__init__.py").writeText("VALUE = 1\n")
    }

    @AfterTest
    fun cleanup() {
        workspace.deleteRecursively()
    }

    private val compiled = mutableListOf<Pair<String, Map<String, String>>>()
    private val bundled = mutableListOf<Pair<BundleType, BundleRequest>>()

    private fun command(
        compileSucceeds: Boolean = true,
        recordBundles: Boolean = false,
    ) = BuildCommand(
        compile = { name, args ->
            compiled += name to args
            compileSucceeds
        },
        bundlerFor = { type ->
            if (!recordBundles) {
                BundlerInterface.create(type)
            } else {
                object : BundlerInterface {
                    override fun bundle(request: BundleRequest): Result<BundleResult> {
                        bundled += type to request
                        val artifact = File(request.packageDir, "build/fake.whl")
                        return Result.success(BundleResult(type, artifact.parentFile, artifact, 1, artifactFile = artifact))
                    }
                }
            }
        },
        workspaceDir = workspace,
    )

    @Test
    fun `resource bundles the package source without compiling`() {
        val result = command().test("core resource --target macos")

        assertEquals(0, result.statusCode, result.output)
        assertTrue(compiled.isEmpty(), "resource reads source; nothing is compiled: $compiled")
        val payload = File(packageDir, "build/packpack/resource/debug/instant/python/core/__init__.py")
        assertTrue(payload.isFile, result.output)
        assertTrue("resource" in result.output && "build/packpack/resource/debug/instant" in result.output, result.output)
    }

    @Test
    fun `source compiles for the canonical target, then bundles the chosen type`() {
        val result = command(recordBundles = true).test("core source single --target macos --type release")

        assertEquals(0, result.statusCode, result.output)
        val (name, args) = compiled.single()
        assertEquals("core", name)
        assertEquals("aarch64-apple-darwin", args["target"])
        assertEquals("release", args["type"])
        val (type, request) = bundled.single()
        assertEquals(BundleType.SINGLE, type)
        assertEquals(packageDir, request.packageDir.canonicalFile)
        assertEquals("aarch64-apple-darwin", request.target)
        assertEquals("release", request.buildType)
        assertEquals("instant", request.buildLevel)
        assertTrue("fake.whl" in result.output, result.output)
    }

    @Test
    fun `source without a bundle type is binary, which says it is not implemented`() {
        val result = command().test("core source --target macos")

        assertEquals(1, result.statusCode, result.output)
        assertEquals(1, compiled.size)
        assertTrue("binary" in result.output && "not implemented" in result.output.lowercase(), result.output)
    }

    @Test
    fun `a failed compile stops before the bundle`() {
        val result = command(compileSucceeds = false, recordBundles = true).test("core source fat --target macos")

        assertEquals(1, result.statusCode, result.output)
        assertTrue(bundled.isEmpty(), "$bundled")
    }

    @Test
    fun `without a bundle kind build only compiles, as before`() {
        val result = command(recordBundles = true).test("core --target macos")

        assertEquals(0, result.statusCode, result.output)
        assertEquals("macos", compiled.single().second["target"])
        assertTrue(bundled.isEmpty())
    }

    @Test
    fun `an unknown kind, a bundle type after resource, or resource as a source bundle is refused`() {
        listOf("core wheel", "core resource fat", "core source resource", "core source zip").forEach { args ->
            val result = command(recordBundles = true).test(args)

            assertEquals(1, result.statusCode, "$args: ${result.output}")
            assertTrue(bundled.isEmpty() && compiled.isEmpty(), "$args: $compiled $bundled")
        }
    }

    @Test
    fun `an unknown target is refused before anything runs`() {
        val result = command(recordBundles = true).test("core source single --target amiga")

        assertEquals(1, result.statusCode, result.output)
        assertTrue("amiga" in result.output, result.output)
        assertTrue(compiled.isEmpty() && bundled.isEmpty())
    }

    @Test
    fun `the help shows both bundle forms`() {
        val result = command().test("--help")

        assertEquals(0, result.statusCode, result.output)
        assertTrue("source" in result.output && "resource" in result.output, result.output)
    }
}
