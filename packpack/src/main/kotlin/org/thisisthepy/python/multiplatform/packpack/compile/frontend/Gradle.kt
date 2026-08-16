package org.thisisthepy.python.multiplatform.packpack.compile.frontend

import org.thisisthepy.python.multiplatform.packpack.compile.middleware.DefaultMiddleware
import org.thisisthepy.python.multiplatform.packpack.compile.middleware.MiddlewareInterface

/**
 * Gradle interface for compilation process
 */
class Gradle : FrontendInterface {
    private lateinit var middleware: MiddlewareInterface

    override fun initialize() {
        middleware = DefaultMiddleware()
        middleware.initialize()
    }

    override fun getMiddleware(): MiddlewareInterface {
        if (!::middleware.isInitialized) {
            initialize()
        }
        return middleware
    }
}
