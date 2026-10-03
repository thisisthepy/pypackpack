package org.thisisthepy.python.multiplatform.packpack.dependency.frontend

import org.thisisthepy.python.multiplatform.packpack.dependency.middleware.DefaultMiddleware
import org.thisisthepy.python.multiplatform.packpack.dependency.middleware.MiddlewareInterface

/**
 * CLI interface for dependency management
 */
class Cli : FrontendInterface {
    private lateinit var middleware: MiddlewareInterface

    /**
     * Initialize CLI interface
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
