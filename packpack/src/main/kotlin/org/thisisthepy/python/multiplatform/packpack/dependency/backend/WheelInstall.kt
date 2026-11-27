package org.thisisthepy.python.multiplatform.packpack.dependency.backend

import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipFile

/*
 * Installing a wheel into a `--target` directory ourselves (PEP 427), for one case only (#82; team lead 2026-11-27):
 * the wasm32-emscripten wheels pypackpack builds in the cross environment. uv has no `--python-platform` for PEP
 * 783's `pyemscripten_2026_0` (measured: uv 0.12.3 and 0.13.6 offer only wasm32-pyodide2024/2025, and both refuse a
 * `pyemscripten_2026_0_wasm32` wheel), so it cannot install them. Every other wheel stays on uv. Remove this path
 * when uv supports that platform.
 */

/** The `INSTALLER` this writes (PEP 376). */
const val WHEEL_INSTALLER_NAME: String = "pypackpack"

/**
 * Installs [wheel] into [targetDir] as PEP 427 says: its tags must include one of [acceptedTags]
 * (`<python>-<abi>-<platform>`); every file in it must be listed in its `<dist>.dist-info/RECORD` with a matching
 * SHA-256 and size, and every listed file must be in it; `<dist>.data/{purelib,platlib}` go to [targetDir],
 * `scripts` to `bin/`, `headers` to `include/<dist>/`, `data` to [targetDir]; then `INSTALLER` and a RECORD of the
 * installed paths are written. A path that would leave [targetDir] is refused. Nothing is written unless every check
 * passes. An earlier install of the same distribution is removed first, by its RECORD.
 */
fun installWheel(wheel: File, targetDir: File, acceptedTags: Set<String>): Result<List<File>> = runCatching {
    val parts = wheel.name.removeSuffix(".whl").split('-')
    require(wheel.name.endsWith(".whl") && parts.size in 5..6) { "${wheel.name} is not a wheel file name (PEP 427)." }
    val (pythons, abis, platforms) = parts.takeLast(3).map { it.split('.') }
    val tags = pythons.flatMap { py -> abis.flatMap { abi -> platforms.map { "$py-$abi-$it" } } }.toSet()
    require(tags.any { it in acceptedTags }) {
        "${wheel.name} is tagged ${tags.sorted().joinToString()}, not ${acceptedTags.sorted().joinToString()}; refusing to install it."
    }
    val distribution = parts[0]
    val version = parts[1]
    val distInfo = "$distribution-$version.dist-info"
    val dataDir = "$distribution-$version.data/"
    val target = targetDir.canonicalFile

    ZipFile(wheel).use { zip ->
        val entries = zip.entries().asSequence().filter { !it.isDirectory }.associateBy { it.name }
        val recordEntry = entries["$distInfo/RECORD"] ?: throw IllegalArgumentException("${wheel.name} has no $distInfo/RECORD.")
        val record = zip.getInputStream(recordEntry).bufferedReader().readLines().filter { it.isNotBlank() }.associate { line ->
            val fields = parseRecordLine(line)
            fields[0] to (fields.getOrElse(1) { "" } to fields.getOrElse(2) { "" })
        }
        // PEP 427: RECORD itself and its signatures carry no hash.
        val unsigned = setOf("$distInfo/RECORD", "$distInfo/RECORD.jws", "$distInfo/RECORD.p7s")
        entries.keys.filter { it !in record && it !in unsigned }.sorted().firstOrNull()?.let {
            throw IllegalArgumentException("${wheel.name} holds $it, which its RECORD does not list.")
        }
        record.keys.filter { it !in entries && it !in unsigned }.sorted().firstOrNull()?.let {
            throw IllegalArgumentException("${wheel.name}'s RECORD lists $it, which it does not hold.")
        }

        // Every check before anything is written.
        val placed = linkedMapOf<String, ByteArray>()
        for ((name, entry) in entries) {
            if (name in unsigned) continue
            val bytes = zip.getInputStream(entry).readBytes()
            val (expected, size) = record.getValue(name)
            require(expected.startsWith("sha256=") && expected == recordHash(bytes) && (size.isEmpty() || size == bytes.size.toString())) {
                "${wheel.name}: $name does not match its RECORD entry ($expected, $size bytes; actual ${recordHash(bytes)}, " +
                    "${bytes.size} bytes); refusing to install it."
            }
            val installed = when {
                name.startsWith(dataDir) -> {
                    val rest = name.removePrefix(dataDir)
                    val scheme = rest.substringBefore('/')
                    val path = rest.substringAfter('/')
                    when (scheme) {
                        "purelib", "platlib", "data" -> path
                        "scripts" -> "bin/$path"
                        "headers" -> "include/$distribution/$path"
                        else -> throw IllegalArgumentException("${wheel.name}: $name is in an unknown .data scheme '$scheme'.")
                    }
                }
                else -> name
            }
            val file = File(target, installed).canonicalFile
            require(file.toPath().startsWith(target.toPath()) && !installed.startsWith("/")) {
                "${wheel.name}: $name would be installed outside $target; refusing to install it."
            }
            placed[file.relativeTo(target).invariantSeparatorsPath] = bytes
        }

        File(target, "$distInfo/RECORD").takeIf { it.isFile }?.readLines()?.forEach { line ->
            parseRecordLine(line).firstOrNull()?.let { path -> File(target, path).canonicalFile.takeIf { it.toPath().startsWith(target.toPath()) }?.delete() }
        }
        val written = placed.map { (path, bytes) ->
            File(target, path).apply { parentFile.mkdirs(); writeBytes(bytes) }
        }
        val installer = "$WHEEL_INSTALLER_NAME\n".toByteArray()
        File(target, "$distInfo/INSTALLER").apply { parentFile.mkdirs(); writeBytes(installer) }
        val lines = (placed + ("$distInfo/INSTALLER" to installer)).map { (path, bytes) -> "$path,${recordHash(bytes)},${bytes.size}" } +
            "$distInfo/RECORD,,"
        File(target, "$distInfo/RECORD").writeText(lines.joinToString("\n", postfix = "\n"))
        written
    }
}

/** `sha256=<urlsafe base64, no padding>`, RECORD's hash form (PEP 376 / 427). */
private fun recordHash(bytes: ByteArray): String =
    "sha256=" + Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))

/** One RECORD line: CSV, a field may be double-quoted (a path with a comma). */
private fun parseRecordLine(line: String): List<String> {
    val fields = mutableListOf<String>()
    val field = StringBuilder()
    var quoted = false
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            quoted && c == '"' && line.getOrNull(i + 1) == '"' -> { field.append('"'); i++ }
            c == '"' -> quoted = !quoted
            c == ',' && !quoted -> { fields += field.toString(); field.clear() }
            else -> field.append(c)
        }
        i++
    }
    fields += field.toString()
    return fields
}
