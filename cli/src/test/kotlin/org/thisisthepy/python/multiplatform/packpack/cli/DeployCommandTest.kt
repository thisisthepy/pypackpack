package org.thisisthepy.python.multiplatform.packpack.cli

import com.github.ajalt.clikt.testing.test
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

    @Test
    fun deploy_reportsUnimplementedReasonAndFails() {
        val command = DeployCommand()
        val result = command.test("mypackage source")

        assertEquals(1, result.statusCode, result.output)
        assertTrue(
            result.output.contains("not implemented", ignoreCase = true),
            "output must state reason for failure: ${result.output}",
        )
    }

    @Test
    fun deploy_registeredInPyPackPackCommandHelp() {
        val command = PyPackPackCommand()
        val result = command.test("--help")

        assertEquals(0, result.statusCode, result.output)
        assertTrue(result.output.contains("deploy"), result.output)
    }
}
