package org.thisisthepy.python.multiplatform.packpack.deploy.code

import org.thisisthepy.python.multiplatform.packpack.deploy.DeployInterface
import org.thisisthepy.python.multiplatform.packpack.deploy.DeployRequest
import org.thisisthepy.python.multiplatform.packpack.deploy.DeployType
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `deploy <package> code single|fat` uploads the wheel `build <package> source single|fat` made, with
 * `uv publish` (#90; SPEC *Package deployment*). Only a fake runner is used: nothing is uploaded.
 */
class PyPIPublishAPITest {
    private val packageDir: File = Files.createTempDirectory("ppp-deploy-").toFile().canonicalFile
    private val runs = mutableListOf<Pair<List<String>, File>>()

    @AfterTest
    fun cleanup() {
        packageDir.deleteRecursively()
    }

    private fun wheel(bundle: String, name: String, buildType: String = "debug", level: String = "instant"): File =
        File(packageDir, "build/packpack/$bundle/$buildType/$level/$name").apply {
            parentFile.mkdirs()
            writeText("wheel")
        }

    private fun api(exit: Result<String> = Result.success("uploaded")) =
        PyPIPublishAPI(run = { command, dir ->
            runs += command to dir
            exit
        })

    private fun request(bundle: String, destination: String? = null, dryRun: Boolean = false, buildType: String = "debug") =
        DeployRequest(packageDir = packageDir, bundleType = bundle, destination = destination, dryRun = dryRun, buildType = buildType)

    @Test
    fun `the single wheel is published with uv publish`() {
        val whl = wheel("single", "core-0.1.0-cp315-cp315t-macosx_11_0_arm64.whl")

        val result = api().deploy(request("single")).getOrThrow()

        assertEquals(listOf("publish", whl.path), runs.single().first)
        assertEquals(packageDir, runs.single().second)
        assertEquals(whl, result.artifactFile)
        assertEquals(DeployType.CODE, result.deployType)
    }

    @Test
    fun `publish url and dry run pass through, and the build type picks the directory`() {
        val whl = wheel("fat", "core-0.1.0-cp315-cp315t-macosx_11_0_arm64.whl", buildType = "release")

        api().deploy(request("fat", destination = "https://test.pypi.org/legacy/", dryRun = true, buildType = "release")).getOrThrow()

        assertEquals(
            listOf("publish", "--publish-url", "https://test.pypi.org/legacy/", "--dry-run", whl.path),
            runs.single().first,
        )
    }

    @Test
    fun `no wheel is refused, naming the build command that makes it`() {
        val error = api().deploy(request("single")).exceptionOrNull()

        val message = error?.message.orEmpty()
        assertTrue("pypackpack build" in message && "source single" in message && "--type debug" in message, message)
        assertTrue(runs.isEmpty())
    }

    @Test
    fun `more than one wheel is refused, listing them`() {
        wheel("single", "core-0.1.0-cp315-cp315t-macosx_11_0_arm64.whl")
        wheel("single", "core-0.1.0-cp315-cp315t-manylinux_2_34_x86_64.whl")

        val message = api().deploy(request("single")).exceptionOrNull()?.message.orEmpty()

        assertTrue("macosx_11_0_arm64" in message && "manylinux_2_34_x86_64" in message, message)
        assertTrue(runs.isEmpty())
    }

    @Test
    fun `a failed upload fails with uv's output`() {
        wheel("single", "core-0.1.0-cp315-cp315t-macosx_11_0_arm64.whl")

        val message = api(Result.failure(IllegalStateException("403 Forbidden"))).deploy(request("single")).exceptionOrNull()?.message.orEmpty()

        assertTrue("403 Forbidden" in message, message)
    }

    /** Libraries (wheels) go to PyPI; an app's binary or patch goes to FastTrack, which has no server yet. */
    @Test
    fun `code routes single and fat to PyPI, and refuses binary and patch for it`() {
        wheel("single", "core-0.1.0-cp315-cp315t-macosx_11_0_arm64.whl")
        val code = CodeAPI(pypi = api())

        assertTrue(code.deploy(request("single")).isSuccess)
        listOf("binary", "patch").forEach { bundle ->
            val message = code.deploy(request(bundle)).exceptionOrNull()?.message.orEmpty()
            assertTrue("not implemented" in message.lowercase() && "FastTrack" in message && "PyPI" in message, "$bundle: $message")
        }
        assertTrue("unknown" in code.deploy(request("zip")).exceptionOrNull()?.message.orEmpty().lowercase())
        assertEquals(1, runs.size)
    }

    @Test
    fun `the factory's code deployer is the routing one`() {
        val message = DeployInterface.create(DeployType.CODE).deploy(request("patch")).exceptionOrNull()?.message.orEmpty()
        assertTrue("FastTrack" in message, message)
    }
}
