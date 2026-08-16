package org.thisisthepy.python.multiplatform.packpack.dependency.frontend

import org.thisisthepy.python.multiplatform.packpack.dependency.middleware.DefaultMiddleware
import org.thisisthepy.python.multiplatform.packpack.dependency.middleware.MiddlewareInterface

/**
 * Gradle interface for dependency management
 */
class Gradle : FrontendInterface {
    private lateinit var middleware: MiddlewareInterface

    /**
     * Initialize Gradle interface
     */
    override fun initialize() {
        middleware = DefaultMiddleware()
        middleware.initialize()
    }

    /**
     * Get middleware interface
     */
    override fun getMiddleware(): MiddlewareInterface {
        if (!::middleware.isInitialized) {
            initialize()
        }
        return middleware
    }
}
