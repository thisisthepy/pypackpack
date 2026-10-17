package org.thisisthepy.python.multiplatform.packpack.utils

import org.thisisthepy.python.multiplatform.packpack.utils.toml.TomlEditor
import java.io.File

private const val PYPROJECT_FILE = "pyproject.toml"

/**
 * Utility functions for finding project and workspace roots to search pyproject.toml
 */
internal fun findProjectRoot(startDir: File = File(System.getProperty("user.dir"))): File? {
    var current: File? = startDir.canonicalFile
    while (current != null) {
        if (File(current, PYPROJECT_FILE).isFile) {
            return current
        }
        current = current.parentFile
    }
    return null
}

/**
 * The interpreter of the nearest `<dir>/.venv` at or above [startDir] (`docs/SPEC.md`'s
 * `<project>/.venv` convention, however deep the package sits in a multi-package project), or
 * `null` when there is none. Never a `python3` from `PATH`: what it builds or compiles is tied to
 * the interpreter that produced it.
 */
internal fun findVenvInterpreter(startDir: File): File? {
    var current: File? = startDir.canonicalFile
    while (current != null) {
        val venv = File(current, ".venv")
        val candidate =
            listOf("bin/python3", "bin/python", "Scripts/python.exe")
                .map { File(venv, it) }
                .firstOrNull { it.isFile }
        if (candidate != null) return candidate
        current = current.parentFile
    }
    return null
}

/**
 * Finds the workspace root by looking for a pyproject.toml with a [readWorkspaceMembers] section.
 * Starts searching from the given directory and moves up the directory tree until found.
 * @throws IllegalStateException if no workspace root is found.
 */
internal fun findWorkspaceRoot(startDir: File = File(System.getProperty("user.dir"))): File {
    var current: File? = startDir.canonicalFile
    while (current != null) {
        if (readWorkspaceMembers(current).isNotEmpty()) {
            return current
        }
        current = current.parentFile
    }

    throw IllegalStateException("Workspace root not found. Run the command inside a workspace.")
}

internal fun readWorkspaceMembers(workspaceRoot: File): List<String> {
    val workspacePyproject = File(workspaceRoot, PYPROJECT_FILE)
    if (!workspacePyproject.isFile) {
        return emptyList()
    }

    return TomlEditor(workspacePyproject.readText()).getArray("tool.uv.workspace", "members")
}

/**
 * The directory of the workspace member [packageName] (its path or its last path segment), which must stay
 * inside [workspaceRoot] and hold a `pyproject.toml`.
 */
internal fun resolveWorkspacePackageDir(
    workspaceRoot: File,
    packageName: String,
): File {
    val members = readWorkspaceMembers(workspaceRoot)
    val member =
        members.firstOrNull { it == packageName || File(it).name == packageName }
            ?: throw IllegalArgumentException("Package '$packageName' is not a workspace member")

    val rootPath = workspaceRoot.canonicalFile.toPath()
    val packageDir = File(workspaceRoot, member).canonicalFile
    require(packageDir.toPath().startsWith(rootPath)) {
        "Package path must stay within workspace: $member"
    }
    require(packageDir.isDirectory) {
        "Workspace package directory not found: ${packageDir.absolutePath}"
    }
    require(File(packageDir, "pyproject.toml").isFile) {
        "pyproject.toml not found for workspace package '$packageName'"
    }

    return packageDir
}
