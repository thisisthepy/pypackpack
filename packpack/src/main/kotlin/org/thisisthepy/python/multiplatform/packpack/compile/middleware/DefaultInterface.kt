package org.thisisthepy.python.multiplatform.packpack.compile.middleware

import kotlinx.coroutines.runBlocking
import org.thisisthepy.python.multiplatform.packpack.compile.backend.DefaultInterface as BackendDefaultInterface

/**
 * Decorator pattern default interface for compilation middleware
 */
class DefaultInterface : BaseInterface {
    override fun initialize() {
        // Default initialization logic for compilation middleware
    }

    override fun compile(
        packageName: String,
        extraArgs: Map<String, String>?,
    ): Boolean {
        val backend = BackendDefaultInterface()
        return runBlocking {
            backend.compile(packageName, extraArgs)
        }.onFailure {
            val target = packageName.takeIf { it.isNotBlank() } ?: "current project"
            println("Failed to compile $target: ${it.message}")
        }.isSuccess
    }
}
