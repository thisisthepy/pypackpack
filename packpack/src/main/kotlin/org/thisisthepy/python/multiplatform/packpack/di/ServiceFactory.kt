package org.thisisthepy.python.multiplatform.packpack.di

import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.thisisthepy.python.multiplatform.packpack.dependency.backend.BaseInterface
import org.thisisthepy.python.multiplatform.packpack.dependency.middleware.environment.DevEnv
import org.thisisthepy.python.multiplatform.packpack.dependency.middleware.environment.CrossEnv

/**
 * Service factory using Koin DI
 * Provides easy access to injected dependencies
 */
object ServiceFactory : KoinComponent {
    
    /**
     * Get UV backend interface instance
     */
    val uvBackend: BaseInterface by inject()
    
    /**
     * Get development environment instance
     */
    val devEnv: DevEnv by inject()
    
    /**
     * Get cross-platform environment instance
     */
    val crossEnv: CrossEnv by inject()
    
    /**
     * Create a new DevEnv instance (for cases where a fresh instance is needed)
     */
    fun createDevEnv(): DevEnv {
        return DevEnv()
    }
    
    /**
     * Create a new CrossEnv instance (for cases where a fresh instance is needed)
     */
    fun createCrossEnv(): CrossEnv {
        return CrossEnv()
    }
} 