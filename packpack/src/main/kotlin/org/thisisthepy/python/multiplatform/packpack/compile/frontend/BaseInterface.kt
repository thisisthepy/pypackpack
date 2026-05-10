package org.thisisthepy.python.multiplatform.packpack.compile.frontend

import org.thisisthepy.python.multiplatform.packpack.compile.middleware.BaseInterface as MiddlewareInterface

/**
 * Frontend type enum
 */
enum class FrontendType {
    CLI,
    GRADLE,
}

/**
 * Factory pattern base interface for compilation frontend
 */
interface BaseInterface {
    fun initialize()

    fun getMiddleware(): MiddlewareInterface

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
