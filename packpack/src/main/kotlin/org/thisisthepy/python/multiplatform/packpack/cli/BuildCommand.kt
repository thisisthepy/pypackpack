package org.thisisthepy.python.multiplatform.packpack.cli

import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import org.thisisthepy.python.multiplatform.packpack.compile.frontend.BaseInterface
import org.thisisthepy.python.multiplatform.packpack.compile.frontend.FrontendType

class BuildCommand : CliktCommand(name = "build") {
    override fun help(context: Context) = "Build the project for the specified target(s)."

    val type by option("--type", help = "Specify the build type (default: debug)", metavar = "TYPE").default("debug")
    val level by option("--level", help = "Specify the build level (default: instant)").default("instant")
    val target by option("--target", help = "Specify the build target")

    override fun run() {
        val middleware = BaseInterface.create(FrontendType.CLI).apply { initialize() }.getMiddleware()
        runBooleanCommand(
            progressMessage = "Building project with type: $type, level: $level, target: $target...",
            failureMessage = "Build failed for type: $type, level: $level, target: $target.",
            successMessage = "Build completed successfully for type: $type, level: $level, target: $target.",
        ) {
            middleware.compile("")
        }
    }
}
