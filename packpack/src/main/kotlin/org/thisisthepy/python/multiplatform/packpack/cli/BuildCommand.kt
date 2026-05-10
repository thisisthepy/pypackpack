package org.thisisthepy.python.multiplatform.packpack.cli

import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.default

class BuildCommand : CliktCommand(name = "build") {
    override fun help(context: Context) = "Build the project for the specified target(s)."

    val type by option("--type", help = "Specify the build type (default: debug)", metavar = "TYPE").default("debug")
    val level by option("--level", help = "Specify the build level (default: instant)").default("instant")
    val target by option("--target", help = "Specify the build target")

    override fun run() {
        TODO("Implement build command with various build options and target specifications.")

        
    }
}
