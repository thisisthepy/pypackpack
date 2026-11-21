package org.thisisthepy.python.multiplatform.packpack.compile.backend

import org.thisisthepy.python.multiplatform.packpack.utils.deleteWithoutFollowingLinks
import org.thisisthepy.python.multiplatform.packpack.compile.backend.external.Cargo
import org.thisisthepy.python.multiplatform.packpack.compile.backend.external.Meson
import org.thisisthepy.python.multiplatform.packpack.utils.findWorkspaceRoot
import org.thisisthepy.python.multiplatform.packpack.utils.resolveWorkspacePackageDir
import org.thisisthepy.python.multiplatform.packpack.utils.readProjectName
import org.thisisthepy.python.multiplatform.packpack.utils.readWorkspaceMembers
import org.thisisthepy.python.multiplatform.packpack.dependency.backend.UVBackend
import java.io.File

/**
 * Strategy pattern default interface for compilation backend
 */
class DefaultBackend(
    val meson: Meson = Meson(),
    val cargo: Cargo = Cargo(),
    val dependencyCheck: DependencyCheck = UvDependencyCheck(),
) : BackendInterface {

    override fun initialize() {
        // Default initialization logic for compilation middleware
    }

    override suspend fun compile(
        packageName: String,
        extraArgs: Map<String, String>?,
        workingDir: File,
    ): Result<String> =
        runCatching {
            require(packageName.isNotBlank()) { "Package name is required. Use ppp build <package>." }

            val type = extraArgs?.get("type")?.takeIf { it.isNotBlank() } ?: "debug"
            val level = extraArgs?.get("level")?.takeIf { it.isNotBlank() } ?: "instant"
            val target = extraArgs?.get("target")?.takeIf { it.isNotBlank() } ?: "default"
            val buildDir = "build/packpack/single/$type/$level/$target"
            val overwrite = extraArgs?.get("overwrite")?.toBooleanStrictOrNull() ?: false
            val workspaceRoot = findWorkspaceRoot(workingDir)
            val packageDir = resolveWorkspacePackageDir(workspaceRoot, packageName)
            // The sheet's orchestration: dependencies are verified before anything is built (#159).
            dependencyCheck.check(workspaceRoot, packageDir).getOrThrow()

            if (overwrite) {
                File(packageDir, buildDir).deleteWithoutFollowingLinks()
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

/** Verifies a package's dependencies before `pypackpack build` compiles it (docs/SPEC.md *Package build*). */
fun interface DependencyCheck {
    suspend fun check(workspaceRoot: File, packageDir: File): Result<Unit>
}

/**
 * `uv sync --check` in [workspaceRoot] (with `--package <name>` in a workspace): fails, naming
 * `pypackpack sync`, when the project's environment does not match its lock and `pyproject.toml`.
 */
class UvDependencyCheck(
    private val uv: UVBackend = UVBackend(),
) : DependencyCheck {
    override suspend fun check(workspaceRoot: File, packageDir: File): Result<Unit> {
        val command = mutableListOf("sync", "--check")
        if (readWorkspaceMembers(workspaceRoot).isNotEmpty()) {
            readProjectName(packageDir)?.let { command += listOf("--package", it) }
        }
        return uv.executeCommand(command, workspaceRoot).fold(
            onSuccess = { Result.success(Unit) },
            onFailure = {
                Result.failure(
                    IllegalStateException(
                        "The dependencies of ${packageDir.name} are not in sync with the project; run `pypackpack sync` " +
                            "and build again. uv sync --check: ${it.message?.trim()}",
                        it,
                    ),
                )
            },
        )
    }
}
