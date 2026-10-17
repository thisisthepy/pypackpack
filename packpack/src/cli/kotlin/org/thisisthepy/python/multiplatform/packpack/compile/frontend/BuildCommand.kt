package org.thisisthepy.python.multiplatform.packpack.compile.frontend

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import org.thisisthepy.python.multiplatform.packpack.bundle.BundleRequest
import org.thisisthepy.python.multiplatform.packpack.bundle.BundleType
import org.thisisthepy.python.multiplatform.packpack.bundle.BundlerInterface
import org.thisisthepy.python.multiplatform.packpack.utils.Platforms
import org.thisisthepy.python.multiplatform.packpack.utils.findWorkspaceRoot
import org.thisisthepy.python.multiplatform.packpack.utils.resolveWorkspacePackageDir
import org.thisisthepy.python.multiplatform.packpack.utils.runBooleanCommand
import java.io.File

/**
 * `build` compiles and, given a bundle kind, bundles (the maintainer's spec sheet `spec.md` 686b1ad;
 * `docs/SPEC.md` *Package build*):
 *
 *     pypackpack build <package> source [<bundle type: default binary>] [--type] [--level] [--target]
 *     pypackpack build <package> resource [--target]
 *
 * `build <package>` alone only compiles. The seams ([compile], [bundlerFor], [workspaceDir]) are for tests.
 */
class BuildCommand(
    private val compile: (packageName: String, args: Map<String, String>) -> Boolean = { name, args ->
        FrontendInterface.create(FrontendType.CLI).apply { initialize() }.getMiddleware().compile(name, args)
    },
    private val bundlerFor: (BundleType) -> BundlerInterface = BundlerInterface::create,
    private val workspaceDir: File = File(System.getProperty("user.dir")),
) : CliktCommand(name = "build") {
    override fun help(context: Context) =
        "Build a package: `build <package> source [binary|fat|single|patch]` compiles and bundles it, " +
            "`build <package> resource` bundles its source for python-multiplatform, and `build <package>` " +
            "only compiles."

    val packageName by argument(help = "Package name")
    val kind by argument(help = "What to bundle: source or resource (omit to only compile)").optional()
    val bundleType by argument(help = "For source: binary (default), fat, single or patch").optional()

    val type by option("--type", help = "Specify the build type (default: debug)", metavar = "TYPE").default("debug")
    val level by option("--level", help = "Specify the build level (default: instant)").default("instant")
    val target by option("--target", help = "Specify the build target (default: this machine, when bundling)")
    val overwrite by option("--overwrite", help = "Overwrite generated Meson files, the build directory and the bundle output").flag()

    override fun run() {
        when (kind) {
            null -> compileOnly()
            KIND_SOURCE -> buildSource()
            KIND_RESOURCE -> buildResource()
            else -> fail("Unknown bundle kind '$kind'. Use '$KIND_SOURCE' or '$KIND_RESOURCE'.")
        }
    }

    private fun compileOnly() {
        if (bundleType != null) fail("A bundle type needs a kind first: build $packageName $KIND_SOURCE $bundleType")
        runBooleanCommand(
            progressMessage = "Building package: $packageName with type: $type, level: $level, target: $target...",
            failureMessage = "Build failed for package: $packageName",
            successMessage = "Build completed successfully for package: $packageName",
        ) { compile(packageName, compileArgs(target.orEmpty())) }
    }

    private fun buildSource() {
        val bundle = (bundleType ?: BundleType.BINARY.id).let { id ->
            BundleType.fromId(id)?.takeIf { it != BundleType.RESOURCE }
                ?: fail("Unknown source bundle type '$id'. Use binary, fat, single or patch.")
        }
        val canonical = canonicalTarget()
        val packageDir = packageDir()
        if (!compile(packageName, compileArgs(canonical))) fail("Build failed for package: $packageName (compile)")
        bundle(bundle, packageDir, canonical)
    }

    private fun buildResource() {
        bundleType?.let { fail("'$KIND_RESOURCE' takes no bundle type, got '$it'.") }
        bundle(BundleType.RESOURCE, packageDir(), canonicalTarget())
    }

    private fun bundle(bundle: BundleType, packageDir: File, canonical: String) {
        val request = BundleRequest(
            packageDir = packageDir,
            target = canonical,
            buildType = type,
            buildLevel = level,
            overwrite = overwrite,
        )
        val result = bundlerFor(bundle).bundle(request).getOrElse {
            fail("Bundling '$packageName' as ${bundle.id} failed: ${it.message}")
        }
        result.warnings.forEach { echo("warning: $it", err = true) }
        val output = result.artifactFile ?: result.outputDir
        echo("Bundled '$packageName' as ${bundle.id} for $canonical: $output (${result.fileCount} file(s))")
    }

    /** Bundling needs the canonical triple: the wheel bundlers read `dist/<canonical target>/...`. */
    private fun canonicalTarget(): String {
        val requested = target ?: return Platforms.detectHostTarget()
        return Platforms.normalizeTarget(requested) ?: fail(Platforms.unsupportedTargetMessage(requested))
    }

    private fun packageDir(): File =
        runCatching { resolveWorkspacePackageDir(findWorkspaceRoot(workspaceDir), packageName) }
            .getOrElse { fail(it.message ?: it.toString()) }

    private fun compileArgs(compileTarget: String): Map<String, String> =
        mapOf("overwrite" to overwrite.toString(), "type" to type, "level" to level, "target" to compileTarget)

    private fun fail(message: String): Nothing = throw PrintMessage(message, statusCode = 1, printError = true)

    private companion object {
        const val KIND_SOURCE = "source"
        const val KIND_RESOURCE = "resource"
    }
}
