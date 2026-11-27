package org.thisisthepy.python.multiplatform.packpack.compile.backend.external

import org.thisisthepy.python.multiplatform.packpack.compile.extension.CompileProcessResult
import org.thisisthepy.python.multiplatform.packpack.compile.extension.runCompileProcess
import org.thisisthepy.python.multiplatform.packpack.utils.Platforms
import java.io.File

/**
 * Emscripten (`emcc`) for the native compile slot's `wasm32-emscripten` target (#82).
 *
 * A wasm extension is an Emscripten side module loaded into python-multiplatform's runtime: CPython 3.15t with
 * pthreads, Emscripten [Platforms.EMSDK_VERSION], on PEP 783's `pyemscripten_2026_0` unwinding ABI. Its
 * `scripts/wasm/build-cpython.sh` compiles and links with [ABI_FLAGS] and `-pthread`, and python.wasm exports the
 * `__cpp_exception` and `__c_longjmp` tags a side module imports. So both steps here take the same flags, the link
 * adds `-sSIDE_MODULE=1`, and nothing links libpython (the side module resolves it from python.wasm when loaded).
 * `emcc` is `$EMSDK/upstream/emscripten/emcc`, else `emcc` on `PATH`. It must be [Platforms.EMSDK_VERSION]: an
 * object built by another Emscripten is not guaranteed to load.
 */
open class Emscripten(
    private val locateEmcc: () -> List<String>? = { defaultEmcc(System.getenv()) },
    private val versionProbe: (emcc: List<String>) -> String? = ::defaultVersionProbe,
    val run: (command: List<String>, workingDir: File, env: Map<String, String>) -> CompileProcessResult = ::runCompileProcess,
) {
    fun emcc(): List<String>? = locateEmcc()

    /**
     * The `emcc (...) X.Y.Z (...)` line of `emcc --version`, or `null` when it cannot run. A first run (or a new
     * `EM_CACHE`) prints `shared:INFO: (Emscripten: Running sanity checks)` before it, so the first line is not it.
     */
    fun versionOf(emcc: List<String>): String? {
        val lines = versionProbe(emcc)?.lineSequence()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toList() ?: return null
        return lines.firstOrNull { it.startsWith("emcc") } ?: lines.firstOrNull { !it.startsWith("shared:") }
    }

    /** The `X.Y.Z` in [versionLine] (`emcc (...) 5.0.3 (hash)`), or `null`. */
    fun emscriptenVersion(versionLine: String): String? = Regex("""\b(\d+\.\d+\.\d+)\b""").find(versionLine)?.groupValues?.get(1)

    fun compileCommand(
        emcc: List<String>,
        buildType: String,
        workingDir: File,
        includeDir: File,
        includeDirs: List<File>,
        defines: Map<String, String?>,
        cFlags: List<String>,
        source: File,
        objectFile: File,
        depFile: File,
    ): List<String> =
        buildList {
            addAll(emcc)
            add("-c")
            addAll(optimization(buildType))
            addAll(ABI_FLAGS)
            add("-pthread")
            add("-fPIC")
            add("-ffp-contract=off")
            add("-fno-fast-math")
            add("-ffile-prefix-map=${workingDir.canonicalFile.path}=.")
            add("-MMD")
            add("-MF")
            add(depFile.path)
            add("-I${includeDir.canonicalFile.path}")
            includeDirs.forEach { dir -> add("-I${dir.canonicalFile.path}") }
            defines.forEach { (k, v) -> add(if (v == null) "-D$k" else "-D$k=$v") }
            addAll(cFlags)
            add(source.path)
            add("-o")
            add(objectFile.path)
        }

    fun linkCommand(
        emcc: List<String>,
        buildType: String,
        objects: List<File>,
        linkFlags: List<String>,
        outputFile: File,
    ): List<String> =
        buildList {
            addAll(emcc)
            addAll(optimization(buildType))
            addAll(ABI_FLAGS)
            add("-pthread")
            add("-fPIC")
            add("-sSIDE_MODULE=1")
            objects.forEach { obj -> add(obj.path) }
            addAll(linkFlags)
            add("-o")
            add(outputFile.path)
        }

    private fun optimization(buildType: String): List<String> =
        if (buildType == "release") listOf("-O2", "-DNDEBUG") else listOf("-O0", "-g")

    companion object {
        /** python-multiplatform's `ABI_FLAGS` (build-cpython.sh): PEP 783 pyemscripten_2026_0's unwinding ABI. */
        val ABI_FLAGS: List<String> = listOf("-fwasm-exceptions", "-sSUPPORT_LONGJMP=wasm")

        fun defaultEmcc(env: Map<String, String>): List<String>? {
            val suffix = if (System.getProperty("os.name").lowercase().contains("win")) ".bat" else ""
            env["EMSDK"]?.takeIf { it.isNotBlank() }?.let { sdk ->
                File(sdk, "upstream/emscripten/emcc$suffix").takeIf { it.isFile }?.let { return listOf(it.path) }
            }
            return env["PATH"].orEmpty().split(File.pathSeparatorChar)
                .map { File(it, "emcc$suffix") }
                .firstOrNull { it.isFile && it.canExecute() }
                ?.let { listOf(it.path) }
        }

        private fun defaultVersionProbe(emcc: List<String>): String? {
            val res = runCompileProcess(emcc + "--version", File("."), emptyMap())
            return res.output.takeIf { res.exitCode == 0 }
        }
    }
}
