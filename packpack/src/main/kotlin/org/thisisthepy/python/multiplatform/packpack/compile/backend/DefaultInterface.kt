package org.thisisthepy.python.multiplatform.packpack.compile.backend

import org.thisisthepy.python.multiplatform.packpack.compile.backend.external.Meson
import java.io.File

/**
 * Strategy pattern default interface for compilation backend
 */
class DefaultInterface : BaseInterface {
    val meson = Meson()

    override fun initialize() {
        // Default initialization logic for compilation middleware
    }

    override suspend fun compile(
        packageName: String,
        extraArgs: Map<String, String>?,
    ): Result<String> =
        runCatching {
            val buildDir = "build"
            val workingDir = File(System.getProperty("user.dir"))
            meson.setup(buildDir = buildDir, options = null, workingDir = workingDir).getOrThrow()
            meson.compile(buildDir = buildDir, options = null, workingDir = workingDir).getOrThrow()
            meson.install(buildDir = buildDir, options = null, workingDir = workingDir).getOrThrow()
            "Compilation completed successfully."
        }
}
