package org.thisisthepy.python.multiplatform.packpack.compile.backend

import org.thisisthepy.python.multiplatform.packpack.compile.backend.external.Meson
import org.thisisthepy.python.multiplatform.packpack.utils.findWorkspaceRoot
import org.thisisthepy.python.multiplatform.packpack.utils.readWorkspaceMembers
import java.io.File

/**
 * Strategy pattern default interface for compilation backend
 */
class DefaultInterface : BaseInterface {
    val meson = Meson()

    override fun initialize() {
        // Default initialization logic for compilation middleware
    }

    override suspend fun compile(
        packageName: String,
        extraArgs: Map<String, String>?,
    ): Result<String> =
        runCatching {
            require(packageName.isNotBlank()) { "Package name is required. Use ppp build <package>." }

            val buildDir = "build/packpack/single/debug"
            val overwrite = extraArgs?.get("overwrite")?.toBooleanStrictOrNull() ?: false
            val workspaceRoot = findWorkspaceRoot(File(System.getProperty("user.dir")))
            val packageDir = resolveWorkspacePackageDir(workspaceRoot, packageName)

            if (overwrite) {
                File(packageDir, buildDir).deleteRecursively()
            }

            meson.setup(buildDir = buildDir, options = null, workingDir = packageDir, overwrite = overwrite).getOrThrow()
            meson.compile(buildDir = buildDir, options = null, workingDir = packageDir).getOrThrow()
            meson.install(buildDir = buildDir, options = null, workingDir = packageDir).getOrThrow()

            "Package '$packageName' compiled successfully."
        }

    private fun resolveWorkspacePackageDir(
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
}
