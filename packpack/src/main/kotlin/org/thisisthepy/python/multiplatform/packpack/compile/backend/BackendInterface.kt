package org.thisisthepy.python.multiplatform.packpack.compile.backend

import java.io.File

enum class BackendType {
    MESON,
}

/**
 * Factory pattern base interface for compilation backend
 */
interface BackendInterface {
    fun initialize()

    /**
     * Compiles [packageName] of the workspace at [workingDir] (AGENTS.md rule 13: the directory is a
     * parameter, never `user.dir`; docs/SPEC.md *Package build*, #159).
     */
    suspend fun compile(
        packageName: String,
        extraArgs: Map<String, String>? = null,
        workingDir: File,
    ): Result<String>
}
