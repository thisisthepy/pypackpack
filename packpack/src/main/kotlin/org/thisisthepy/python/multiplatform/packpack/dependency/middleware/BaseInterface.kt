package org.thisisthepy.python.multiplatform.packpack.dependency.middleware

import org.thisisthepy.python.multiplatform.packpack.dependency.backend.BaseInterface as BackendBaseInterface

/**
 * Base interface for middleware
 */
interface BaseInterface {
    /**
     * Initialize middleware
     */
    fun initialize()
    
    /**
     * Get backend interface
     */
    fun getBackend(): BackendBaseInterface
    
    /**
     * Add dependencies to a package
     * @param packageName Package name (optional)
     * @param dependencies List of dependencies to add
     * @param targets List of target platforms (optional)
     * @param extraArgs Extra arguments (optional)
     * @return Success status
     */
    fun addDependencies(packageName: String?, dependencies: List<String>, targets: List<String>?, extraArgs: Map<String, String>?): Boolean
    
    /**
     * Remove dependencies from a package
     * @param packageName Package name (optional)
     * @param dependencies List of dependencies to remove
     * @param targets List of target platforms (optional)
     * @param extraArgs Extra arguments (optional)
     * @return Success status
     */
    fun removeDependencies(packageName: String?, dependencies: List<String>, targets: List<String>?, extraArgs: Map<String, String>?): Boolean
    
    /**
     * Synchronize dependencies for a package
     * @param packageName Package name (optional)
     * @param targets List of target platforms (optional)
     * @param extraArgs Extra arguments (optional)
     * @return Success status
     */
    fun syncDependencies(packageName: String?, targets: List<String>?, extraArgs: Map<String, String>?): Boolean
    
    /**
     * Show dependency tree for a package
     * @param packageName Package name (optional)
     * @param targets List of target platforms (optional)
     * @param extraArgs Extra arguments (optional)
     * @return Success status
     */
    fun showDependencyTree(packageName: String?, targets: List<String>?, extraArgs: Map<String, String>?): Boolean
}
