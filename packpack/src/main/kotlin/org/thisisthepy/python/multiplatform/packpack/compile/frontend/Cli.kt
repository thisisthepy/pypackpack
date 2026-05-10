package org.thisisthepy.python.multiplatform.packpack.compile.frontend

import org.thisisthepy.python.multiplatform.packpack.compile.middleware.DefaultInterface
import org.thisisthepy.python.multiplatform.packpack.compile.middleware.BaseInterface as MiddlewareInterface

/**
 * CLI interface for compilation process
 */
class Cli : BaseInterface {
    private lateinit var middleware: MiddlewareInterface

    /**
     * Initialize CLI interface
     */
    override fun initialize() {
        middleware = DefaultInterface()
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
