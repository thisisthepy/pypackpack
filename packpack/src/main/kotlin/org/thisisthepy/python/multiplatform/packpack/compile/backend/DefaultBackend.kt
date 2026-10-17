package org.thisisthepy.python.multiplatform.packpack.compile.backend

import org.thisisthepy.python.multiplatform.packpack.compile.backend.external.Cargo
import org.thisisthepy.python.multiplatform.packpack.compile.backend.external.Meson
import org.thisisthepy.python.multiplatform.packpack.utils.findWorkspaceRoot
import org.thisisthepy.python.multiplatform.packpack.utils.resolveWorkspacePackageDir
import java.io.File

/**
 * Strategy pattern default interface for compilation backend
 */
class DefaultBackend(
    val meson: Meson = Meson(),
    val cargo: Cargo = Cargo(),
) : BackendInterface {

    override fun initialize() {
        // Default initialization logic for compilation middleware
    }

    override suspend fun compile(
        packageName: String,
        extraArgs: Map<String, String>?,
    ): Result<String> =
        runCatching {
            require(packageName.isNotBlank()) { "Package name is required. Use ppp build <package>." }

            val type = extraArgs?.get("type")?.takeIf { it.isNotBlank() } ?: "debug"
            val level = extraArgs?.get("level")?.takeIf { it.isNotBlank() } ?: "instant"
            val target = extraArgs?.get("target")?.takeIf { it.isNotBlank() } ?: "default"
            val buildDir = "build/packpack/single/$type/$level/$target"
            val overwrite = extraArgs?.get("overwrite")?.toBooleanStrictOrNull() ?: false
            val workspaceRoot = findWorkspaceRoot(File(System.getProperty("user.dir")))
            val packageDir = resolveWorkspacePackageDir(workspaceRoot, packageName)

            if (overwrite) {
                File(packageDir, buildDir).deleteRecursively()
            }

            val destdir = "${packageDir.absolutePath}/dist/$target/$type/$level"

            meson.setup(buildDir = buildDir, options = listOf("--buildtype=$type"), workingDir = packageDir, overwrite = overwrite).getOrThrow()
            meson.compile(buildDir = buildDir, options = null, workingDir = packageDir).getOrThrow()
            meson.install(buildDir = buildDir, options = null, workingDir = packageDir, destdir = destdir).getOrThrow()
            // The package's Rust crates (#92): their extensions go beside Meson's, in the destdir's site-packages.
            cargo.build(packageDir, target.takeUnless { it == "default" }, type, File(destdir)).getOrThrow()

            "Package '$packageName' compiled successfully."
        }
}
