package org.thisisthepy.python.multiplatform.packpack.cli

import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import org.thisisthepy.python.multiplatform.packpack.compile.frontend.BaseInterface
import org.thisisthepy.python.multiplatform.packpack.compile.frontend.FrontendType

class BuildCommand : CliktCommand(name = "build") {
    override fun help(context: Context) = "Build the project for the specified target(s)."

    val packageName by argument(help = "Package name")

    val type by option("--type", help = "Specify the build type (default: debug)", metavar = "TYPE").default("debug")
    val level by option("--level", help = "Specify the build level (default: instant)").default("instant")
    val target by option("--target", help = "Specify the build target")
    val overwrite by option("--overwrite", help = "Overwrite generated Meson files and build directory").flag()

    override fun run() {
        val middleware = BaseInterface.create(FrontendType.CLI).apply { initialize() }.getMiddleware()
        runBooleanCommand(
            progressMessage = "Building package: $packageName with type: $type, level: $level, target: $target...",
            failureMessage = "Build failed for package: $packageName",
            successMessage = "Build completed successfully for package: $packageName",
        ) {
            middleware.compile(packageName, mapOf("overwrite" to overwrite.toString()))
        }
    }
}
