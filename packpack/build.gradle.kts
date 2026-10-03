plugins {
    alias(libs.plugins.kotlin.jvm)
    kotlin("plugin.serialization") version "2.3.0"
    // `toolchain` is meant to delegate to this project rather than reimplement it, and until this
    // is published nothing outside this repository can resolve it at all -- which is what has been
    // blocking that delegation.
    //
    // `application` and `org.graalvm.buildtools.native` moved to `:cli` (see cli/build.gradle.kts).
    // This module is the library only: `toolchain` depends on it as an ordinary Maven artifact and
    // must not see the CLI's own dependencies (Clikt in particular) on its runtime classpath.
    `maven-publish`
}

group = "org.thisisthepy.python.multiplatform"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation("com.akuleshov7:ktoml-core:0.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.8.1")
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation("com.github.luben:zstd-jni:1.5.7-9")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.17")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
}

kotlin {
    jvmToolchain(21)
    // packpack runs inside Gradle plugins (toolchain), where Gradle's own kotlin-stdlib wins:
    // Gradle 8.9 embeds 1.9.23. Compiled at api 2.3, the coroutine code referenced
    // kotlin.coroutines.jvm.internal.SpillingKt (stdlib 2.2+), and every suspend call failed with
    // NoClassDefFoundError in a real consumer, though not in tests, whose stdlib is newer.
    // apiVersion 1.9 keeps the bytecode to the stdlib API Gradle 8.9 ships. Raise it only with the
    // oldest Gradle toolchain supports.
    compilerOptions { apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_1_9) }
}

tasks.test {
    useJUnitPlatform()
}

// ---------------------------------------------------------------------------------------------
// Publishing
//
// `toolchain` consumes this as an ordinary Maven dependency. `docs/SPEC.md` names the coordinate,
// and `group`/`version` above already carry it, so the publication only has to name the component.
//
// `from(components["java"])` and not the shadow/native artefacts: what a consumer needs is the
// library, and the CLI binary is a different deliverable of the same source, published separately
// from `:cli`. Publishing the application distribution here would put a launcher script and every
// runtime jar on the consumer's compile classpath.
//
// Clikt (and the rest of the CLI's own dependencies) no longer reach a consumer of this artifact:
// the CLI moved to `:cli`, which depends on this module rather than the other way around. Ktor and
// zstd-jni stay here because the library itself uses them (`utils/Downloader.kt`,
// `utils/Archive.kt`), not just the CLI, so they are still `implementation` (POM scope `runtime`)
// and still visible to a consumer -- that part of the leak was never CLI-specific and is unchanged
// by this split.
publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "packpack"
            from(components["java"])
            pom {
                name.set("packpack")
                description.set(
                    "Python packaging for Kotlin Multiplatform: distribution management, " +
                        "dependency resolution, cross-compilation environments and bundling."
                )
            }
        }
    }
}
