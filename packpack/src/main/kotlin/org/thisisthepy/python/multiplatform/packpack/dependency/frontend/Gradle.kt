package org.thisisthepy.python.multiplatform.packpack.dependency.frontend

import org.thisisthepy.python.multiplatform.packpack.dependency.middleware.BaseInterface as MiddlewareBaseInterface
import org.thisisthepy.python.multiplatform.packpack.dependency.middleware.DefaultInterface

/**
 * Gradle interface for dependency management
 */
class Gradle : BaseInterface {
    private lateinit var middleware: MiddlewareBaseInterface

    /**
     * Initialize Gradle interface
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
