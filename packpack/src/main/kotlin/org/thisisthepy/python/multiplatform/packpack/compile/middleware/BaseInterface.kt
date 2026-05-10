package org.thisisthepy.python.multiplatform.packpack.compile.middleware

/**
 * Factory pattern base interface for compilation middleware
 */
interface BaseInterface {
    fun initialize()

    fun compile(
        packageName: String,
        extraArgs: Map<String, String>? = null,
    ): Boolean
}
