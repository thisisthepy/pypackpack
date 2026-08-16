package org.thisisthepy.python.multiplatform.packpack.cli

import org.thisisthepy.python.multiplatform.packpack.dependency.middleware.MiddlewareInterface

/**
 * Shared fake middleware for CLI-layer tests: records the arguments a command passed through so
 * tests can assert on wiring (e.g. that a passthrough flag reached `extraArgs`) without needing a
 * real `uv` process.
 */
internal class RecordingMiddleware : MiddlewareInterface {
    var lastAddPackageName: String? = null
    var lastAddDependencies: List<String>? = null
    var lastAddTargets: List<String>? = null
    var lastAddExtraArgs: Map<String, String>? = null
    var lastRemovePackageName: String? = null
    var lastRemoveDependencies: List<String>? = null
    var lastRemoveTargets: List<String>? = null
    var lastRemoveExtraArgs: Map<String, String>? = null
    var lastSyncPackageName: String? = null
    var lastSyncTargets: List<String>? = null
    var lastSyncExtraArgs: Map<String, String>? = null
    var lastTreePackageName: String? = null
    var lastTreeTargets: List<String>? = null
    var lastTreeExtraArgs: Map<String, String>? = null

    override fun initialize() = Unit

    override fun initProject(
        path: String?,
        targets: List<String>?,
        extraArgs: Map<String, String>?,
    ): Result<String> = Result.success("ok")

    override fun addDependencies(
        packageName: String?,
        dependencies: List<String>,
        targets: List<String>?,
        extraArgs: Map<String, String>?,
    ): Boolean {
        lastAddPackageName = packageName
        lastAddDependencies = dependencies
        lastAddTargets = targets
        lastAddExtraArgs = extraArgs
        return true
    }

    override fun removeDependencies(
        packageName: String?,
        dependencies: List<String>,
        targets: List<String>?,
        extraArgs: Map<String, String>?,
    ): Boolean {
        lastRemovePackageName = packageName
        lastRemoveDependencies = dependencies
        lastRemoveTargets = targets
        lastRemoveExtraArgs = extraArgs
        return true
    }

    override fun syncDependencies(
        packageName: String?,
        targets: List<String>?,
        extraArgs: Map<String, String>?,
    ): Boolean {
        lastSyncPackageName = packageName
        lastSyncTargets = targets
        lastSyncExtraArgs = extraArgs
        return true
    }

    override fun showDependencyTree(
        packageName: String?,
        targets: List<String>?,
        extraArgs: Map<String, String>?,
    ): Boolean {
        lastTreePackageName = packageName
        lastTreeTargets = targets
        lastTreeExtraArgs = extraArgs
        return true
    }

    override fun addPackage(
        packageName: String,
        path: String?,
        extraArgs: Map<String, String>?,
    ): Result<String> = Result.success("ok")

    override fun removePackage(
        packageName: String,
        path: String?,
    ): Result<String> = Result.success("ok")

    override fun addTargets(
        packageName: String?,
        targets: List<String>,
    ): Result<String> = Result.success("ok")

    override fun removeTargets(
        packageName: String?,
        targets: List<String>,
    ): Result<String> = Result.success("ok")

    override fun getToolVersion(): Result<String> = Result.success("ok")

    override fun changePythonVersion(pythonVersion: String): Boolean = true

    override fun listPythonVersions(): Boolean = true

    override fun findPythonVersion(pythonVersion: String): Boolean = true

    override fun installPythonVersion(
        pythonVersion: String,
        targetPlatform: String?,
    ): Result<String> = Result.success("ok")

    override fun uninstallPythonVersion(pythonVersion: String): Boolean = true
}
