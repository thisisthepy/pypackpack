package org.thisisthepy.python.multiplatform.packpack.compile.backend

enum class BackendType {
    MESON,
}

/**
 * Factory pattern base interface for compilation backend
 */
interface BaseInterface {
    fun initialize()

    suspend fun compile(
        packageName: String,
        extraArgs: Map<String, String>? = null,
    ): Result<String>
}
