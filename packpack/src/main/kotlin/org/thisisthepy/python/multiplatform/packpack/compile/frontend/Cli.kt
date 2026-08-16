package org.thisisthepy.python.multiplatform.packpack.compile.frontend

import org.thisisthepy.python.multiplatform.packpack.compile.middleware.DefaultMiddleware
import org.thisisthepy.python.multiplatform.packpack.compile.middleware.MiddlewareInterface

/**
 * CLI interface for compilation process
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
