package org.thisisthepy.python.multiplatform.packpack.dependency.middleware

import org.thisisthepy.python.multiplatform.packpack.dependency.middleware.environment.CrossEnv
import org.thisisthepy.python.multiplatform.packpack.dependency.middleware.environment.DevEnv
import org.thisisthepy.python.multiplatform.packpack.dependency.backend.BaseInterface as BackendBaseInterface

/** Base interface for middleware */
interface BaseInterface {
    /** Initialize middleware */
    fun initialize()

    /** Get backend interface */
    fun getBackend(): BackendBaseInterface

    /** Get development environment */
    fun getDevEnv(): DevEnv

    /** Get cross-platform environment */
    fun getCrossEnv(): CrossEnv

    /** Init Project */
    fun initProject(
        path: String?,
        targets: List<String>? = emptyList(),
        extraArgs: Map<String, String>?,
    ): Result<String>

    /**
     * Add dependencies to a package
     * @param packageName Package name (optional)
     * @param dependencies List of dependencies to add
     * @param targets List of target platforms (optional)
     * @param extraArgs Extra arguments (optional)
     * @return Success status
     */
    fun addDependencies(
        packageName: String?,
        dependencies: List<String>,
        targets: List<String>?,
        extraArgs: Map<String, String>?,
    ): Boolean

    /**
     * Remove dependencies from a package
     * @param packageName Package name (optional)
     * @param dependencies List of dependencies to remove
     * @param targets List of target platforms (optional)
     * @param extraArgs Extra arguments (optional)
     * @return Success status
     */
    fun removeDependencies(
        packageName: String?,
        dependencies: List<String>,
        targets: List<String>?,
        extraArgs: Map<String, String>?,
    ): Boolean

    /**
     * Synchronize dependencies for a package
     * @param packageName Package name (optional)
     * @param targets List of target platforms (optional)
     * @param extraArgs Extra arguments (optional)
     * @return Success status
     */
    fun syncDependencies(
        packageName: String?,
        targets: List<String>?,
        extraArgs: Map<String, String>?,
    ): Boolean

    /**
     * Show dependency tree for a package
     * @param packageName Package name (optional)
     * @param targets List of target platforms (optional)
     * @param extraArgs Extra arguments (optional)
     * @return Success status
     */
    fun showDependencyTree(
        packageName: String?,
        targets: List<String>?,
        extraArgs: Map<String, String>?,
    ): Boolean

    /**
     * Add a new package to the workspace
     * @param packageName Name of the package to create
     * @param path Parent directory path (optional, defaults to current directory)
     * @param extraArgs Extra arguments to pass to UV init (optional)
     * @return Result containing success message or error
     */
    fun addPackage(
        packageName: String,
        path: String? = null,
        extraArgs: Map<String, String>? = null,
    ): Result<String>

    /**
     * Remove a package from the workspace
     * @param packageName Name of the package to remove
     * @param path Parent directory path (optional, defaults to current directory)
     * @return Result containing success message or error
     */
    fun removePackage(
        packageName: String,
        path: String? = null,
    ): Result<String>

    /**
     * Add target platforms to a package
     * @param targets List of target platform identifiers to add
     * @param path Package directory path (optional, defaults to current directory)
     * @return Result containing success message or error
     */
    fun addTargets(
        targets: List<String>,
        path: String? = null,
    ): Result<String>

    /**
     * Remove target platforms from a package
     * @param targets List of target platform identifiers to remove
     * @param path Package directory path (optional, defaults to current directory)
     * @return Result containing success message or error
     */
    fun removeTargets(
        targets: List<String>,
        path: String? = null,
    ): Result<String>
}
