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
