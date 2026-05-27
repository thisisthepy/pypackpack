package org.thisisthepy.python.multiplatform.packpack.compile.frontend

import org.thisisthepy.python.multiplatform.packpack.compile.middleware.DefaultInterface
import org.thisisthepy.python.multiplatform.packpack.compile.middleware.BaseInterface as MiddlewareBaseInterface

/**
 * Gradle interface for compilation process
 */
class Gradle : BaseInterface {
    private lateinit var middleware: MiddlewareBaseInterface

    override fun initialize() {
        middleware = DefaultInterface()
        middleware.initialize()
    }

    override fun getMiddleware(): MiddlewareBaseInterface {
        if (!::middleware.isInitialized) {
            initialize()
        }
        return middleware
    }
}
