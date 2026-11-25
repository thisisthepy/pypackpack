package org.thisisthepy.python.multiplatform.packpack.dependency.backend

import org.thisisthepy.python.multiplatform.packpack.compile.extension.CompileProcessResult
import org.thisisthepy.python.multiplatform.packpack.compile.extension.runCompileProcess
import org.thisisthepy.python.multiplatform.packpack.utils.Platforms
import java.io.File

/**
 * Installs requirements for a cross target, building in pypackpack's cross build environment every pinned
 * dependency that has no wheel for it (docs/SPEC.md *Cross build environment*, #168). The backend-layer
 * counterpart of `CrossEnv`'s sync loop, for callers that run inside a Gradle daemon (toolchain's
 * `pip { jit { localRecipes } }`): every directory is a parameter (AGENTS.md rule 13).
 *
 * The requirements are pinned for the target first (`uv pip compile`, which reads sdist metadata and builds
 * nothing), then the pins are installed with `--only-binary :all:` and `--no-deps`. uv then names each pin
 * without a wheel exactly (`numpy==2.5.5 has no usable wheels`), and nothing is built with the host compiler
 * on the way: `CrossEnv` lets uv try an sdist on the host before it reports one, which for numpy or a Rust
 * package costs minutes.
 */
class CrossTargetInstaller(
    private val crossBuilder: CrossSdistBuilder = CrossSdistBuilder(),
    private val run: (command: List<String>, workingDir: File, env: Map<String, String>) -> CompileProcessResult = ::runCompileProcess,
) {
    /**
     * Installs [requirements] (PEP 508) for [pythonPlatform] into [targetDir].
     *
     * @param packageDir where the cross environment and the built wheels live (`build/packpack/cross/<target>`),
     *   and whose `pyproject.toml`, when it has one, holds `[tool.ppp.recipes.<name>]` tables
     * @param extraArgs `uv pip` options (`--<key> <value>`): `python-version`, `python`, the indexes, `find-links`.
     *   `target`, `python-platform`, `only-binary`, `no-build` and `no-deps` are this function's own and are ignored.
     * @param workingDir where uv runs and the pin files are written
     * @param recipeDirs the recipe directories ([readCrossRecipe]) the builds take their recipes from
     */
    fun install(
        packageDir: File,
        targetDir: File,
        pythonPlatform: String,
        requirements: List<String>,
        extraArgs: Map<String, String>,
        workingDir: File,
        recipeDirs: List<File>,
    ): Result<String> =
        runCatching {
            if (requirements.isEmpty()) return Result.success("No requirements to install")
            require(requirements.none { it.isBlank() || it.startsWith("-") }) { "Requirements must be non-blank specifiers, not options: $requirements" }
            val target = Platforms.normalizeTarget(pythonPlatform) ?: pythonPlatform
            val platform = Platforms.uvPythonPlatform(target)
            val options = extraArgs.filterKeys { it !in OWN_OPTIONS }.flatMap { (key, value) -> listOf("--$key") + listOfNotNull(value.ifEmpty { null }) }
            workingDir.mkdirs()

            val input = File(workingDir, "requirements.in").apply { writeText(requirements.joinToString("\n", postfix = "\n")) }
            val pinned = File(workingDir, "requirements.txt").apply { delete() }
            val compile = run(
                listOf("uv", "pip", "compile", input.absolutePath, "--output-file", pinned.absolutePath, "--python-platform", platform, "--no-header", "--quiet") + options,
                workingDir,
                emptyMap(),
            )
            check(compile.exitCode == 0) { "uv could not resolve ${requirements.joinToString(", ")} for $target:\n${compile.output}" }
            val pins = pinned.readLines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }

            val wheels = crossBuilder.wheelsDir(packageDir, target)
            val built = mutableListOf<String>()
            while (true) {
                val command = listOf("uv", "pip", "install") + pins +
                    listOf("--target", targetDir.absolutePath, "--python-platform", platform, "--only-binary", ":all:", "--no-deps") +
                    options +
                    (if (built.isEmpty()) emptyList() else listOf("--find-links", wheels.absolutePath))
                val result = run(command, workingDir, emptyMap())
                if (result.exitCode == 0) {
                    return Result.success(
                        "Installed ${pins.size} pinned requirement(s) for $target" +
                            if (built.isEmpty()) "" else ", building ${built.joinToString()} in the cross environment",
                    )
                }
                val missing = parseMissingWheel(result.output, target)
                val spec = missing?.packageSpec?.takeIf { missing.sdist == SdistAvailability.UNKNOWN && "==" in it }
                    ?: throw IllegalStateException("uv could not install the pins for $target:\n${result.output}")
                check(spec !in built) { "`$spec` was built for $target in the cross environment, but uv still finds no wheel for it:\n${result.output}" }
                built += spec
                crossBuilder.buildFromPyPI(packageDir, target, spec, recipeDirs).getOrElse {
                    throw IllegalStateException("`$spec` has no wheel for $target, and building its sdist in the cross environment failed: ${it.message}", it)
                }
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }

    private companion object {
        val OWN_OPTIONS = setOf("target", "python-platform", "only-binary", "no-build", "no-deps")
    }
}
