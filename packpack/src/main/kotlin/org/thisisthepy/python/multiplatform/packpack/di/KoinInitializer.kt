package org.thisisthepy.python.multiplatform.packpack.di

import org.koin.core.context.GlobalContext.startKoin
import org.koin.core.context.GlobalContext.stopKoin
import org.koin.core.logger.Level
import org.koin.core.logger.PrintLogger

/**
 * Koin dependency injection initializer
 * Handles the setup and teardown of the DI container
 */
object KoinInitializer {
    
    /**
     * Initialize Koin DI container with all application modules
     * Configured for GraalVM Native Image compatibility
     */
    fun initialize() {
        startKoin {
            // Use print logger for GraalVM compatibility
            logger(PrintLogger(Level.INFO))
            
            // Load all application modules
            modules(allModules)
            
            // Additional configuration for GraalVM Native Image
            properties(
                mapOf(
                    "koin.use.reflection" to "false",
                    "koin.use.kotlin.reflection" to "false"
                )
            )
        }
    }
    
    /**
     * Shutdown Koin DI container
     * Should be called when the application is terminating
     */
    fun shutdown() {
        try {
            stopKoin()
        } catch (e: Exception) {
            // Ignore shutdown errors in native image
            println("Warning: Error during Koin shutdown: ${e.message}")
        }
    }
    
    /**
     * Check if Koin is already initialized
     */
    fun isInitialized(): Boolean {
        return try {
            org.koin.core.context.GlobalContext.getOrNull() != null
        } catch (e: Exception) {
            false
        }
    }
} 