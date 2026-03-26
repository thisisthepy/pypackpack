package org.thisisthepy.python.multiplatform.packpack.dependency.middleware.internal

import java.io.File

internal object WorkspacePaths {
    private const val PYPROJECT_FILE = "pyproject.toml"

    fun findProjectRoot(startDir: File = File(System.getProperty("user.dir"))): File? {
        var dir = startDir
        while (true) {
            if (File(dir, PYPROJECT_FILE).exists()) {
                return dir
            }
            val parent = dir.parentFile ?: return null
            dir = parent
        }
    }

    fun requireProjectRoot(startDir: File = File(System.getProperty("user.dir"))): File =
        findProjectRoot(startDir)
            ?: throw IllegalStateException("No pyproject.toml found in current directory or parent directories")

    fun resolveWorkspaceRootForPackage(
        packageName: String,
        startDir: File = File(System.getProperty("user.dir")),
    ): File {
        var dir = startDir
        while (true) {
            val pyproject = File(dir, PYPROJECT_FILE)
            val packageDir = File(dir, packageName)
            if (pyproject.exists() && packageDir.exists() && packageDir.isDirectory) {
                return dir
            }

            val parent = dir.parentFile
                ?: throw IllegalStateException("Unable to resolve workspace root for package '$packageName'")
            dir = parent
        }
    }
}
