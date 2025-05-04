package org.thisisthepy.python.multiplatform.packpack.dependency.frontend

import org.thisisthepy.python.multiplatform.packpack.dependency.middleware.BaseInterface as MiddlewareBaseInterface
import org.thisisthepy.python.multiplatform.packpack.dependency.middleware.DefaultInterface

/**
 * CLI interface for dependency management
 */
class Cli : BaseInterface {
    private lateinit var middleware: MiddlewareBaseInterface

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
    override fun getMiddleware(): MiddlewareBaseInterface {
        if (!::middleware.isInitialized) {
            initialize()
        }
        return middleware
    }
}
