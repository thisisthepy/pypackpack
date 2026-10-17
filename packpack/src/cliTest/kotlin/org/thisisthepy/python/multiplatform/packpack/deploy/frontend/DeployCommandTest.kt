package org.thisisthepy.python.multiplatform.packpack.deploy.frontend

import org.thisisthepy.python.multiplatform.packpack.utils.*
import org.thisisthepy.python.multiplatform.packpack.dependency.frontend.*
import org.thisisthepy.python.multiplatform.packpack.compile.frontend.*

import com.github.ajalt.clikt.testing.test
import org.thisisthepy.python.multiplatform.packpack.deploy.DeployInterface
import org.thisisthepy.python.multiplatform.packpack.deploy.DeployRequest
import org.thisisthepy.python.multiplatform.packpack.deploy.DeployResult
import org.thisisthepy.python.multiplatform.packpack.deploy.DeployType
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeployCommandTest {
    @Test
    fun deploy_helpOptionWorks() {
        val command = DeployCommand()
        val result = command.test("--help")

        assertEquals(0, result.statusCode, result.output)
        assertTrue(result.output.contains("Deploy a package artifact"), result.output)
    }

    private val workspace: File = Files.createTempDirectory("ppp-deploy-cli-").toFile().canonicalFile

    init {
        File(workspace, "pyproject.toml").writeText("[project]\nname = \"ws\"\n\n[tool.uv.workspace]\nmembers = [\"packages/core\"]\n")
        File(workspace, "packages/core").mkdirs()
        File(workspace, "packages/core/pyproject.toml").writeText("[project]\nname = \"core\"\nversion = \"0.1.0\"\n")
    }

    @AfterTest
    fun cleanup() {
        workspace.deleteRecursively()
    }

    private val requests = mutableListOf<DeployRequest>()

    private fun recording(type: DeployType) = object : DeployInterface {
        override fun deploy(request: DeployRequest): Result<DeployResult> {
            requests += request
            return Result.success(DeployResult(type, "PyPI", statusMessage = "Dry run: core.whl"))
        }
    }

    @Test
    fun deploy_reportsUnimplementedReasonAndFails() {
        val command = DeployCommand(workspaceDir = workspace)
        val result = command.test("core source")

        assertEquals(1, result.statusCode, result.output)
        assertTrue(
            result.output.contains("not implemented", ignoreCase = true),
            "output must state reason for failure: ${result.output}",
        )
    }

    @Test
    fun deploy_codeSingleResolvesTheWorkspacePackageAndPassesPublishUrlAndDryRun() {
        val command = DeployCommand(deployerFor = ::recording, workspaceDir = workspace)
        val result = command.test("core code single --type release --publish-url https://test.pypi.org/legacy/ --dry-run")

        assertEquals(0, result.statusCode, result.output)
        val request = requests.single()
        assertEquals(File(workspace, "packages/core"), request.packageDir.canonicalFile)
        assertEquals("single", request.bundleType)
        assertEquals("release", request.buildType)
        assertEquals("https://test.pypi.org/legacy/", request.destination)
        assertTrue(request.dryRun)
        assertTrue("Dry run: core.whl" in result.output, result.output)
    }

    @Test
    fun deploy_codeSingleWithoutAWheelNamesTheBuildCommand() {
        val result = DeployCommand(workspaceDir = workspace).test("core code single")

        assertEquals(1, result.statusCode, result.output)
        assertTrue("pypackpack build core source single" in result.output, result.output)
    }

    @Test
    fun deploy_anUnknownPackageIsRefused() {
        val result = DeployCommand(deployerFor = ::recording, workspaceDir = workspace).test("nope code single")

        assertEquals(1, result.statusCode, result.output)
        assertTrue("nope" in result.output, result.output)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun deploy_registeredInPyPackPackCommandHelp() {
        val command = PyPackPackCommand()
        val result = command.test("--help")

        assertEquals(0, result.statusCode, result.output)
        assertTrue(result.output.contains("deploy"), result.output)
    }
}
