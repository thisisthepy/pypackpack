package org.thisisthepy.python.multiplatform.packpack.dependency.frontend

import org.thisisthepy.python.multiplatform.packpack.dependency.middleware.BaseInterface as MiddlewareBaseInterface

/**
 * Frontend type enum
 */
enum class FrontendType {
    CLI,
    GRADLE,
}

/**
 * Base interface for frontend
 */
interface BaseInterface {
    /**
     * Initialize frontend
     */
    fun initialize()

    /**
     * Get middleware interface
     */
    fun getMiddleware(): MiddlewareBaseInterface

    companion object {
        /**
         * Create frontend instance
         * @param type Frontend type
         * @return Frontend instance
         */
        fun create(type: FrontendType): BaseInterface =
            when (type) {
                FrontendType.CLI -> Cli()
                FrontendType.GRADLE -> Gradle()
            }
    }
}
