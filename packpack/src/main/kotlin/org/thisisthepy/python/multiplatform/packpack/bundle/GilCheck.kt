package org.thisisthepy.python.multiplatform.packpack.bundle

import java.io.File
import java.util.concurrent.TimeUnit

/** The ABI an extension module's file name declares (PEP 3149 / PEP 803 tags). */
enum class ExtensionAbi {
    /** `cpython-3XYt-<platform>`, `cp3XYt-<platform>` or `abi3t`: built for free-threaded CPython. */
    FREE_THREADED,

    /** `cpython-3XY-<platform>`, `cp3XY-<platform>` or `abi3`: built for a GIL CPython. */
    GIL,

    /** A bare `name.so` / `name.pyd`: any interpreter tries to load it, and the tag says nothing. */
    UNTAGGED,
}

/** How one probe process ended. */
sealed interface ProbeOutcome {
    /** The process exited 0; [stdout] is its last line of output. */
    data class Finished(
        val stdout: String,
    ) : ProbeOutcome

    data object TimedOut : ProbeOutcome

    /** The process exited non-zero; [message] is the last line of its error output. */
    data class Failed(
        val message: String,
    ) : ProbeOutcome
}

/**
 * The build-time warning for dependencies that make CPython 3.15t run with the GIL (`docs/SPEC.md`,
 * Scope decisions; #72). Neither check fails a build.
 *
 * - [tagWarnings], on every target: an extension tagged for a GIL CPython. A free-threaded interpreter's
 *   `EXTENSION_SUFFIXES` (`.cpython-315t-<platform>.so`, `.abi3t-<platform>.so`, `.abi3t.so`, `.so`) do
 *   not include it, so it will not load at all.
 * - [probeWarnings], on the host only: a free-threaded extension can still lack
 *   `Py_mod_gil = Py_MOD_GIL_NOT_USED`, which CPython keeps in the module's data, not its name. Each one
 *   is imported in its own isolated (`-I`) process of the project's 3.15t interpreter with a timeout, and
 *   `sys._is_gil_enabled()` says whether the import turned the GIL on. This runs the package's code.
 */
object GilCheck {
    const val PROBE_TIMEOUT_SECONDS = 30L

    private val FREE_THREADED_TAG = Regex("""\.(cpython-3\d+t-[^.]+|cp3\d+t-[^.]+|abi3t(-[^.]+)?)\.(so|pyd)$""")
    private val GIL_TAG = Regex("""\.(cpython-3\d+-[^.]+|cp3\d+-[^.]+|abi3)\.(so|pyd)$""")
    private val UNTAGGED = Regex("""^[^.]+\.(so|pyd)$""")

    private const val PROBE_SCRIPT =
        "import importlib, sys; sys.path.insert(0, sys.argv[1]); " +
            "importlib.import_module(sys.argv[2]); print(int(sys._is_gil_enabled()))"
    private const val FREE_THREADED_SCRIPT =
        "import sysconfig; print(int(bool(sysconfig.get_config_var('Py_GIL_DISABLED'))))"

    /** The ABI [fileName] declares, or `null` when it is not an extension module. */
    fun extensionAbi(fileName: String): ExtensionAbi? =
        when {
            FREE_THREADED_TAG.containsMatchIn(fileName) -> ExtensionAbi.FREE_THREADED
            GIL_TAG.containsMatchIn(fileName) -> ExtensionAbi.GIL
            UNTAGGED.matches(fileName) -> ExtensionAbi.UNTAGGED
            else -> null
        }

    /** One warning per GIL-tagged extension among [paths] (`/`-separated, relative to the Python root). */
    fun tagWarnings(paths: Collection<String>): List<String> =
        paths
            .filter { extensionAbi(it.substringAfterLast('/')) == ExtensionAbi.GIL }
            .map { path ->
                "${moduleName(path)} ($path) is built for a GIL CPython: CPython 3.15t will not load it. " +
                    "Use a free-threaded (cp315t or abi3t) build of its package."
            }

    /** The dotted names of the free-threaded extensions among [paths]: the ones [probeWarnings] imports. */
    fun extensionModules(paths: Collection<String>): List<String> =
        paths
            .filter { extensionAbi(it.substringAfterLast('/')) == ExtensionAbi.FREE_THREADED }
            .map(::moduleName)

    /**
     * Imports each of [modules] from [pythonRoot] with [interpreter], one process per module so one
     * import cannot hide another, and returns a warning for each that turned the GIL on, timed out or
     * failed to import.
     */
    fun probeWarnings(
        pythonRoot: File,
        modules: List<String>,
        interpreter: File,
        run: (command: List<String>, workingDir: File) -> ProbeOutcome = ::runProcess,
    ): List<String> =
        modules.mapNotNull { module ->
            val command = listOf(interpreter.path, "-I", "-c", PROBE_SCRIPT, pythonRoot.path, module)
            when (val outcome = run(command, pythonRoot)) {
                is ProbeOutcome.Finished ->
                    if (outcome.stdout.trim() == "1") {
                        "$module re-enables the GIL when imported: it does not declare " +
                            "Py_mod_gil = Py_MOD_GIL_NOT_USED, so the app would run with the GIL."
                    } else {
                        null
                    }
                ProbeOutcome.TimedOut ->
                    "$module could not be checked for the GIL: its import timed out after $PROBE_TIMEOUT_SECONDS s."
                is ProbeOutcome.Failed ->
                    "$module could not be checked for the GIL: its import failed (${outcome.message})."
            }
        }

    /** Whether [interpreter] is a free-threaded build (`Py_GIL_DISABLED`). */
    fun isFreeThreaded(
        interpreter: File,
        run: (command: List<String>, workingDir: File) -> ProbeOutcome = ::runProcess,
    ): Boolean {
        val outcome = run(listOf(interpreter.path, "-I", "-c", FREE_THREADED_SCRIPT), interpreter.absoluteFile.parentFile)
        return outcome is ProbeOutcome.Finished && outcome.stdout.trim() == "1"
    }

    private fun moduleName(path: String): String {
        val directories = path.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() }
        return (directories + path.substringAfterLast('/').substringBefore('.')).joinToString(".")
    }

    private fun runProcess(
        command: List<String>,
        workingDir: File,
    ): ProbeOutcome {
        val stdout = File.createTempFile("gil-probe", ".out")
        val stderr = File.createTempFile("gil-probe", ".err")
        try {
            val process =
                runCatching {
                    ProcessBuilder(command)
                        .directory(workingDir)
                        .redirectOutput(stdout)
                        .redirectError(stderr)
                        .start()
                }.getOrElse { return ProbeOutcome.Failed(it.message ?: it.toString()) }
            if (!process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return ProbeOutcome.TimedOut
            }
            return if (process.exitValue() == 0) {
                ProbeOutcome.Finished(stdout.readLines().lastOrNull().orEmpty())
            } else {
                ProbeOutcome.Failed(stderr.readLines().lastOrNull { it.isNotBlank() } ?: "exit ${process.exitValue()}")
            }
        } finally {
            stdout.delete()
            stderr.delete()
        }
    }
}
