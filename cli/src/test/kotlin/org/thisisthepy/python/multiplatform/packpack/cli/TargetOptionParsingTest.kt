package org.thisisthepy.python.multiplatform.packpack.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.varargValues
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import kotlin.test.Test
import kotlin.test.assertEquals

class TargetOptionParsingTest {
    @Test
    fun `vararg target option accepts space separated values`() {
        val command = TargetParsingProbe()

        command.parse(listOf("--target", "windows", "linux"))

        assertEquals(listOf("windows", "linux"), command.capturedTargets)
    }

    @Test
    fun `vararg target option stops at unknown option when argument is declared first`() {
        val command = TargetParsingProbe()

        command.parse(listOf("--target", "windows", "linux", "--quiet"))

        assertEquals(listOf("windows", "linux"), command.capturedTargets)
        assertEquals(listOf("--quiet"), command.capturedRawArgs)
    }

    private class TargetParsingProbe : CliktCommand() {
        override val treatUnknownOptionsAsArgs: Boolean = true
        val rawArgs by argument().multiple()
        val targets by option("--target").varargValues().default(emptyList())
        var capturedTargets: List<String> = emptyList()
        var capturedRawArgs: List<String> = emptyList()

        override fun run() {
            capturedTargets = targets.takeWhile { !it.startsWith("-") }
            capturedRawArgs = rawArgs + targets.dropWhile { !it.startsWith("-") }
        }
    }
}
