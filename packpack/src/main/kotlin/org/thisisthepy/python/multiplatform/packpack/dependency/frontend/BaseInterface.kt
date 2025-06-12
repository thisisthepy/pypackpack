package org.thisisthepy.python.multiplatform.packpack.dependency.frontend

import org.thisisthepy.python.multiplatform.packpack.dependency.middleware.BaseInterface as MiddlewareBaseInterface

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
        fun create(type: String): BaseInterface {
            return when (type.lowercase()) {
                "cli" -> Cli()
                "gradle" -> Gradle()
                else -> Cli() // Default to CLI
            }
        }
    }
}
