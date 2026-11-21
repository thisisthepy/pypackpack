package org.thisisthepy.python.multiplatform.packpack.bundle.binary

import kotlinx.coroutines.runBlocking
import org.thisisthepy.python.multiplatform.packpack.bundle.BundleRequest
import org.thisisthepy.python.multiplatform.packpack.bundle.BundleResult
import org.thisisthepy.python.multiplatform.packpack.bundle.BundleType
import org.thisisthepy.python.multiplatform.packpack.bundle.BundlerInterface
import org.thisisthepy.python.multiplatform.packpack.dependency.backend.BackendInterface
import org.thisisthepy.python.multiplatform.packpack.dependency.backend.BackendType
import org.thisisthepy.python.multiplatform.packpack.utils.ArchiveWriter
import org.thisisthepy.python.multiplatform.packpack.utils.Platforms
import org.thisisthepy.python.multiplatform.packpack.utils.deleteWithoutFollowingLinks
import org.thisisthepy.python.multiplatform.packpack.utils.toml.TomlEditor
import org.thisisthepy.python.multiplatform.packpack.utils.toml.TomlValue
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

/**
 * The `binary` bundle (docs/SPEC.md *Bundle Type*, #156; the sheet: "binary (.exe, etcs)"): the compiled
 * package, its installed dependencies for the target and the target's CPython in one directory, with a
 * launcher on desktop targets, archived as `.tar.gz` (`.zip` for Windows).
 *
 * [runtimeInstaller] installs CPython `version` for `target` into a directory (the dependency backend's
 * `installPython` with an explicit `installDir`, AGENTS.md rule 13); a test passes its own.
 */
class BinaryBundler(
    private val runtimeInstaller: (version: String, target: String, installDir: File) -> Result<Unit> = { version, target, dir ->
        runBlocking { BackendInterface.create(BackendType.UV).installPython(version, target, dir) }.map { }
    },
) : BundlerInterface {
    override fun bundle(request: BundleRequest): Result<BundleResult> =
        runCatching {
            val packageDir = request.packageDir.canonicalFile
            val pyproject = File(packageDir, "pyproject.toml")
            require(pyproject.isFile) { "No pyproject.toml in ${packageDir.absolutePath}" }
            val canonical = Platforms.normalizeTarget(request.target)
                ?: throw IllegalArgumentException(Platforms.unsupportedTargetMessage(request.target))
            val family = Platforms.getPlatformFamily(canonical)

            val editor = TomlEditor(pyproject.readText())
            val name = (editor.getValue("project", "name") as? TomlValue.String)?.value ?: packageDir.name
            val version = (editor.getValue("project", "version") as? TomlValue.String)?.value ?: "0.0.0"
            val entry = entryPoint(editor, name)

            val destdir = File(packageDir, "dist/$canonical/${request.buildType}/${request.buildLevel}")
            require(destdir.isDirectory) {
                "No compiled output found under ${destdir.absolutePath}. Run `pypackpack build $name --type ${request.buildType} " +
                    "--level ${request.buildLevel} --target $canonical` first."
            }
            val sitePackages = findSitePackages(destdir)

            val pythonVersion = request.pythonVersion ?: DEFAULT_PYTHON_VERSION
            val runtimeSource = File(packageDir, "build/packpack/runtime/$canonical/$pythonVersion")
            if (findInterpreter(runtimeSource, family) == null) {
                runtimeSource.deleteWithoutFollowingLinks()
                runtimeSource.mkdirs()
                runtimeInstaller(pythonVersion, canonical, runtimeSource).getOrElse {
                    throw IllegalStateException("Could not install CPython $pythonVersion for $canonical: ${it.message}", it)
                }
            }
            val sourceInterpreter = findInterpreter(runtimeSource, family)
                ?: throw IllegalStateException("The CPython runtime in ${runtimeSource.absolutePath} has no interpreter.")

            val base = request.outputDir ?: File(packageDir, "build/packpack/binary/${request.buildType}/${request.buildLevel}/$canonical")
            val root = File(base, name)
            if (root.exists()) {
                require(request.overwrite || !root.exists()) {
                    "${root.absolutePath} exists. Re-run with --overwrite."
                }
                root.deleteWithoutFollowingLinks()
            }
            root.mkdirs()

            copyTree(runtimeSource, File(root, RUNTIME_DIR))
            val app = File(root, APP_DIR)
            val fromPackage = copyTree(sitePackages, app)
            val dependencies = File(packageDir, "build/crossenv/$canonical")
            if (dependencies.isDirectory) {
                val clashes = relativeFiles(dependencies).filter { it in fromPackage }
                require(clashes.isEmpty()) {
                    "The package and its dependencies both provide ${clashes.sorted().joinToString(", ")}; refusing to overwrite one with the other."
                }
                copyTree(dependencies, app)
            }

            val interpreter = File(root, RUNTIME_DIR).toPath().resolve(runtimeSource.toPath().relativize(sourceInterpreter.toPath())).toFile()
            val interpreterPath = root.toPath().relativize(interpreter.toPath()).toString().replace(File.separatorChar, '/')
            val launcher = writeLauncher(root, family, name, interpreterPath, entry)

            val manifest = File(root, MANIFEST_FILE)
            manifest.writeText(
                buildString {
                    append("{\n")
                    append("  \"name\": ${json(name)},\n")
                    append("  \"version\": ${json(version)},\n")
                    append("  \"target\": ${json(canonical)},\n")
                    append("  \"buildType\": ${json(request.buildType)},\n")
                    append("  \"buildLevel\": ${json(request.buildLevel)},\n")
                    append("  \"entryPoint\": ${json(entry.describe())},\n")
                    append("  \"interpreter\": ${json(interpreterPath)},\n")
                    append("  \"launcher\": ${launcher?.let { json(root.toPath().relativize(it.toPath()).toString().replace(File.separatorChar, '/')) } ?: "null"}\n")
                    append("}\n")
                },
            )

            val archive =
                if (family == "windows") {
                    File(base, "$name-$version-$canonical.zip").also { it.delete(); ArchiveWriter.writeZip(root, name, it) }
                } else {
                    File(base, "$name-$version-$canonical.tar.gz").also { it.delete(); ArchiveWriter.writeTarGz(root, name, it) }
                }

            BundleResult(
                bundleType = BundleType.BINARY,
                outputDir = root,
                manifestFile = manifest,
                fileCount = relativeFiles(app).size,
                artifactFile = archive,
            )
        }

    /** What the launcher runs: a `[project.scripts]` function, or the package as a module. */
    internal sealed class EntryPoint {
        data class Function(val module: String, val function: String) : EntryPoint()

        data class Module(val module: String) : EntryPoint()

        fun describe(): String = when (this) {
            is Function -> "$module:$function"
            is Module -> "-m $module"
        }

        fun pythonArgs(): List<String> = when (this) {
            is Function -> listOf("-c", "import sys; from $module import $function as _entry; sys.exit(_entry())")
            is Module -> listOf("-m", module)
        }
    }

    private fun entryPoint(editor: TomlEditor, projectName: String): EntryPoint {
        val script = editor.getStringEntries("project.scripts").values.firstOrNull()
        if (script != null) {
            val module = script.substringBefore(':').trim()
            val function = script.substringAfter(':', "").substringBefore('[').trim()
            require(module.isNotEmpty() && function.isNotEmpty()) { "[project.scripts] entry '$script' is not 'module:function'." }
            return EntryPoint.Function(module, function)
        }
        return EntryPoint.Module(projectName.replace('-', '_'))
    }

    private fun writeLauncher(root: File, family: String, name: String, interpreter: String, entry: EntryPoint): File? =
        when (family) {
            "macos", "linux" -> {
                val args = entry.pythonArgs().joinToString(" ") { shellQuote(it) }
                File(root, "bin/$name").apply {
                    parentFile.mkdirs()
                    writeText(
                        "#!/bin/sh\n" +
                            "# pypackpack binary bundle launcher (docs/SPEC.md *Bundle Type*, binary)\n" +
                            "here=\$(CDPATH= cd -- \"\$(dirname -- \"\$0\")/..\" && pwd -P)\n" +
                            "PYTHONPATH=\"\$here/$APP_DIR\" exec \"\$here/$interpreter\" -s $args \"\$@\"\n",
                    )
                    setExecutable(true, false)
                }
            }
            "windows" -> {
                val args = entry.pythonArgs().joinToString(" ") { if (it.contains(' ')) "\"${it.replace("\"", "\\\"")}\"" else it }
                File(root, "$name.cmd").apply {
                    writeText(
                        "@echo off\r\n" +
                            "rem pypackpack binary bundle launcher (docs/SPEC.md *Bundle Type*, binary)\r\n" +
                            "set \"PYTHONPATH=%~dp0$APP_DIR\"\r\n" +
                            "\"%~dp0${interpreter.replace('/', '\\')}\" -s $args %*\r\n",
                    )
                }
            }
            else -> null
        }

    private fun shellQuote(text: String): String = "'" + text.replace("'", "'\\''") + "'"

    private fun json(text: String): String =
        "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private fun findSitePackages(destdir: File): File {
        val matches = destdir.walkTopDown().filter { it.isDirectory && it.name == "site-packages" }.toList()
        require(matches.isNotEmpty()) {
            "No 'site-packages' directory found under ${destdir.absolutePath}. Expected a Meson destdir install; run `pypackpack build` first."
        }
        require(matches.size == 1) {
            "Multiple 'site-packages' directories found under ${destdir.absolutePath}: " +
                matches.joinToString(", ") { it.absolutePath } + ". Refusing to guess which one to bundle."
        }
        return matches.single()
    }

    /** The interpreter of a CPython tree: `python.exe` on Windows, else `bin/python3.X[t]` or `bin/python3`. */
    internal fun findInterpreter(runtime: File, family: String): File? {
        if (!runtime.isDirectory) return null
        val candidates = runtime.walkTopDown().filter { f ->
            (f.isFile || Files.isSymbolicLink(f.toPath())) &&
                if (family == "windows") {
                    f.name.equals("python.exe", ignoreCase = true)
                } else {
                    f.parentFile.name == "bin" && INTERPRETER_NAME.matches(f.name)
                }
        }.toList()
        return candidates.minWithOrNull(compareBy<File>({ it.path.length }, { -it.name.length }))
    }

    /** Copies [from]'s tree into [to] keeping symbolic links as links; returns the relative file paths copied. */
    private fun copyTree(from: File, to: File): Set<String> {
        val copied = mutableSetOf<String>()
        val source = from.toPath()
        Files.walk(source).use { stream ->
            stream.forEach { path ->
                val rel = source.relativize(path)
                val target = to.toPath().resolve(rel.toString())
                when {
                    Files.isSymbolicLink(path) -> {
                        Files.createDirectories(target.parent)
                        Files.deleteIfExists(target)
                        Files.createSymbolicLink(target, Files.readSymbolicLink(path))
                        copied += rel.toString().replace(File.separatorChar, '/')
                    }
                    Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) -> Files.createDirectories(target)
                    else -> {
                        Files.createDirectories(target.parent)
                        Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS)
                        copied += rel.toString().replace(File.separatorChar, '/')
                    }
                }
            }
        }
        return copied
    }

    private fun relativeFiles(root: File): Set<String> =
        root.walkTopDown().filter { it.isFile }.map { root.toPath().relativize(it.toPath()).toString().replace(File.separatorChar, '/') }.toSet()

    private companion object {
        const val DEFAULT_PYTHON_VERSION = "3.15t"
        const val RUNTIME_DIR = "runtime"
        const val APP_DIR = "app"
        const val MANIFEST_FILE = "binary.json"
        val INTERPRETER_NAME = Regex("""python3(\.\d+t?)?""")
    }
}
