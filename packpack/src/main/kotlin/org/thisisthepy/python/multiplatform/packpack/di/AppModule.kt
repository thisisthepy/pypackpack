package org.thisisthepy.python.multiplatform.packpack.di

import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import org.koin.dsl.bind
import org.thisisthepy.python.multiplatform.packpack.dependency.backend.BaseInterface
import org.thisisthepy.python.multiplatform.packpack.dependency.backend.UVInterface
import org.thisisthepy.python.multiplatform.packpack.dependency.middleware.environment.DevEnv
import org.thisisthepy.python.multiplatform.packpack.dependency.middleware.environment.CrossEnv
import org.thisisthepy.python.multiplatform.packpack.dependency.frontend.BaseInterface as FrontendBaseInterface

/**
 * Main application module for dependency injection
 * Configures all the core components of PyPackPack
 */
val appModule = module {
    
    // Backend interfaces for package management
    singleOf(::UVInterface) bind BaseInterface::class
    
    // Environment management
    singleOf(::DevEnv)
    singleOf(::CrossEnv)
    
    // Frontend interfaces (when implemented)
    // singleOf(::FrontendImplementation) bind FrontendBaseInterface::class
}

/**
 * Utility module for common utilities and helpers
 */
val utilityModule = module {
    // Command line utilities and helpers will be added here
    // when they are implemented with DI support
}

/**
 * Compilation module for build and compilation related components
 */
val compilationModule = module {
    // Compilation backends and middleware will be added here
    // when they are implemented
}

/**
 * Bundle module for bundling and deployment components
 */
val bundleModule = module {
    // Bundle and deployment components will be added here
    // when they are implemented
}

/**
 * All application modules combined
 */
val allModules = listOf(
    appModule,
    utilityModule,
    compilationModule,
    bundleModule
) 