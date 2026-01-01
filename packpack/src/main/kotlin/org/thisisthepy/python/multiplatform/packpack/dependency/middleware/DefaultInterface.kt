package org.thisisthepy.python.multiplatform.packpack.dependency.middleware

import kotlinx.coroutines.runBlocking
import org.thisisthepy.python.multiplatform.packpack.dependency.backend.BackendType
import org.thisisthepy.python.multiplatform.packpack.dependency.middleware.environment.CrossEnv
import org.thisisthepy.python.multiplatform.packpack.dependency.middleware.environment.DevEnv
import java.io.File
import org.thisisthepy.python.multiplatform.packpack.dependency.backend.BaseInterface as BackendBaseInterface

/** Default middleware implementation */
class DefaultInterface : BaseInterface {
    private lateinit var backend: BackendBaseInterface
    private lateinit var devEnv: DevEnv
    private lateinit var crossEnv: CrossEnv

    /** Initialize middleware */
    override fun initialize() {
        if (!::backend.isInitialized) {
            backend = BackendBaseInterface.create(BackendType.UV)
            backend.initialize()
        }
    }

    /** Get backend interface */
    override fun getBackend(): BackendBaseInterface {
        if (!::backend.isInitialized) {
            initialize()
        }
        return backend
    }

    /** Get development environment */
    override fun getDevEnv(): DevEnv {
        if (!::devEnv.isInitialized) {
            devEnv = DevEnv()
            devEnv.initialize(backend)
        }
        return devEnv
    }

    /** Get cross-platform environment */
    override fun getCrossEnv(): CrossEnv {
        if (!::crossEnv.isInitialized) {
            crossEnv = CrossEnv()
            crossEnv.initialize(backend)
        }
        return crossEnv
    }

    /** Init Project */
    override fun initProject(
        path: String?,
        extraArgs: Map<String, String>?,
    ): Result<String> =
        runBlocking {
            backend.initProject(path, extraArgs)
        }

    /**
     * Add dependencies to a package
     * @param packageName Package name (optional)
     * @param dependencies List of dependencies to add
     * @param targets List of target platforms (optional)
     * @param extraArgs Extra arguments (optional)
     * @return Success status
     */
    override fun addDependencies(
        packageName: String?,
        dependencies: List<String>,
        targets: List<String>?,
        extraArgs: Map<String, String>?,
    ): Boolean =
        if (packageName == null) {
            // Add to development environment
            devEnv.addDependencies(dependencies, extraArgs)
        } else {
            // Add to cross-platform environment
            crossEnv.addDependencies(packageName, dependencies, targets, extraArgs)
        }

    /**
     * Remove dependencies from a package
     * @param packageName Package name (optional)
     * @param dependencies List of dependencies to remove
     * @param targets List of target platforms (optional)
     * @param extraArgs Extra arguments (optional)
     * @return Success status
     */
    override fun removeDependencies(
        packageName: String?,
        dependencies: List<String>,
        targets: List<String>?,
        extraArgs: Map<String, String>?,
    ): Boolean {
        if (!::backend.isInitialized) {
            initialize()
        }

        return if (packageName == null) {
            // Remove from development environment
            devEnv.removeDependencies(dependencies, extraArgs)
        } else {
            // Remove from cross-platform environment
            crossEnv.removeDependencies(packageName, dependencies, targets, extraArgs)
        }
    }

    /**
     * Synchronize dependencies for a package
     * @param packageName Package name (optional)
     * @param targets List of target platforms (optional)
     * @param extraArgs Extra arguments (optional)
     * @return Success status
     */
    override fun syncDependencies(
        packageName: String?,
        targets: List<String>?,
        extraArgs: Map<String, String>?,
    ): Boolean {
        if (!::backend.isInitialized) {
            initialize()
        }

        return if (packageName == null) {
            // Sync development environment
            devEnv.syncDependencies(extraArgs)
        } else {
            // Sync cross-platform environment
            crossEnv.syncDependencies(packageName, targets, extraArgs)
        }
    }

    /**
     * Show dependency tree for a package
     * @param packageName Package name (optional)
     * @param targets List of target platforms (optional)
     * @param extraArgs Extra arguments (optional)
     * @return Success status
     */
    override fun showDependencyTree(
        packageName: String?,
        targets: List<String>?,
        extraArgs: Map<String, String>?,
    ): Boolean {
        if (!::backend.isInitialized) {
            initialize()
        }

        return if (packageName == null) {
            // Show development environment dependency tree
            devEnv.showDependencyTree(extraArgs)
        } else {
            // Show cross-platform environment dependency tree
            crossEnv.showDependencyTree(packageName, targets, extraArgs)
        }
    }
}
