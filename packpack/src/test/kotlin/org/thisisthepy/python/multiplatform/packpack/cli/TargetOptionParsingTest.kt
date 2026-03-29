package org.thisisthepy.python.multiplatform.packpack.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.varargValues
import kotlin.test.Test
import kotlin.test.assertEquals

class TargetOptionParsingTest {
    @Test
    fun `vararg target option accepts space separated values`() {
        val command = TargetParsingProbe()

        command.parse(listOf("--target", "windows", "linux"))

        assertEquals(listOf("windows", "linux"), command.capturedTargets)
    }

    private class TargetParsingProbe : CliktCommand() {
        val targets by option("--target").varargValues().default(emptyList())
        var capturedTargets: List<String> = emptyList()

        override fun run() {
            capturedTargets = targets
        }
    }
}
