package org.thisisthepy.python.multiplatform.packpack.dependency.frontend

import org.thisisthepy.python.multiplatform.packpack.dependency.middleware.MiddlewareInterface

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
interface FrontendInterface {
    /**
     * Initialize frontend
     */
    fun initialize()

    /**
     * Get middleware interface
     */
    fun getMiddleware(): MiddlewareInterface

    companion object {
        /**
         * Create frontend instance
         * @param type Frontend type
         * @return Frontend instance
         */
        fun create(type: FrontendType): FrontendInterface =
            when (type) {
                FrontendType.CLI -> Cli()
                FrontendType.GRADLE -> Gradle()
            }
    }
}
