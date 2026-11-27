package org.thisisthepy.python.multiplatform.packpack.utils

import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * packpack runs inside Gradle (toolchain's plugin), where Gradle's embedded kotlin-stdlib wins: 2.0.20 in Gradle 8.11.
 * Code compiled by Kotlin 2.2+ at api 2.2+ calls `kotlin.coroutines.jvm.internal.SpillingKt`, which that stdlib lacks,
 * so a suspend call fails with NoClassDefFoundError in a consumer but not in these tests, whose stdlib is newer.
 * packpack's own classes are capped by `apiVersion` (build.gradle.kts); this checks every library it runs with
 * too. ktor 3.4 failed it (#170: torchnative examples/android, the target CPython download).
 */
class GradleStdlibCompatTest {
    private val spilling = "kotlin/coroutines/jvm/internal/SpillingKt".toByteArray()

    private fun ByteArray.contains(needle: ByteArray): Boolean =
        (0..size - needle.size).any { i -> needle.indices.all { this[i + it] == needle[it] } }

    @Test
    fun nothingPackpackRunsNeedsAStdlibNewerThanGradles() {
        val classpath = System.getProperty("packpack.runtimeClasspath")
        assertTrue(!classpath.isNullOrBlank(), "build.gradle.kts passes packpack.runtimeClasspath to the test task")
        val offenders = mutableListOf<String>()
        for (entry in classpath.split(File.pathSeparator).map(::File).filter { it.exists() }) {
            // The stdlib defines the class; Gradle's own copy is what a consumer runs.
            if (entry.name.startsWith("kotlin-stdlib")) continue
            if (entry.isDirectory) {
                entry.walkTopDown().filter { it.extension == "class" && it.readBytes().contains(spilling) }
                    .forEach { offenders += "$entry: ${it.relativeTo(entry)}" }
            } else if (entry.extension == "jar") {
                ZipFile(entry).use { zip ->
                    zip.entries().asSequence().filter { it.name.endsWith(".class") }
                        .firstOrNull { e -> zip.getInputStream(e).use { it.readBytes() }.contains(spilling) }
                        ?.let { offenders += "${entry.name}: ${it.name}" }
                }
            }
        }
        assertTrue(offenders.isEmpty(), "These need kotlin-stdlib 2.2+ (SpillingKt), which Gradle 8 does not have:\n${offenders.joinToString("\n")}")
    }
}
