plugins {
    alias(libs.plugins.kotlin.jvm)
    kotlin("plugin.serialization") version "2.3.0"
    // `toolchain` is meant to delegate to this project rather than reimplement it, and until this
    // is published nothing outside this repository can resolve it at all -- which is what has been
    // blocking that delegation.
    //
    // The command line lives in this module's own `cli` source set (bottom of this file), so the
    // published library (`main`) never carries the CLI's dependencies (Clikt in particular).
    `maven-publish`
    id("org.graalvm.buildtools.native")
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
// (the PyPI wheels, publish-pypi.yml). Publishing the application distribution here would put a launcher script and every
// runtime jar on the consumer's compile classpath.
//
// Clikt (and the rest of the CLI's own dependencies) never reach a consumer of this artifact: the
// CLI is the `cli` source set, which depends on `main` and is not part of components["java"]. Ktor and
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

// ---------------------------------------------------------------------------------------------
// The command line (`pypackpack`, alias `ppp`)
//
// Inside this module, as the first spec sheet has it (`spec.md`, 686b1ad: `util/CommandLine.kt` is
// the CLI endpoint, each domain's `frontend/` holds its CLI), but in its own `cli` source set, so the
// published library (`main`, components["java"]) never pulls Clikt onto a consumer's classpath
// (#73; the CLI was a root `cli/` module from 488bac0 until then). `cliVersion` is the CLI's own
// version, which `pypackpack version` prints and the PyPI wheel carries; publish-pypi.yml refuses
// to publish unless it equals pyproject.toml's. The library keeps `version`, because toolchain
// resolves `packpack:0.1.0`.
val cliVersion = "0.2.0"
val cliMainClass = "org.thisisthepy.python.multiplatform.packpack.utils.CommandLineKt"

val cli: SourceSet by sourceSets.creating
val cliTest: SourceSet by sourceSets.creating
cli.compileClasspath += sourceSets.main.get().output
cli.runtimeClasspath += sourceSets.main.get().output
cliTest.compileClasspath += cli.output + sourceSets.main.get().output
cliTest.runtimeClasspath += cli.output + sourceSets.main.get().output
configurations[cli.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[cli.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())
configurations[cliTest.implementationConfigurationName].extendsFrom(configurations[cli.implementationConfigurationName])
configurations[cliTest.runtimeOnlyConfigurationName].extendsFrom(configurations[cli.runtimeOnlyConfigurationName])

dependencies {
    "cliImplementation"(libs.clikt)
    "cliTestImplementation"(kotlin("test-junit5"))
    "cliTestImplementation"("org.junit.jupiter:junit-jupiter:5.10.1")
}

kotlin.target.compilations.getByName("cli").apply {
    associateWith(kotlin.target.compilations.getByName("main"))
    // The CLI runs on its own stdlib (native image or launcher), not Gradle's, so the library's
    // apiVersion 1.9 cap does not apply to it.
    compileTaskProvider.configure {
        compilerOptions.apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.DEFAULT)
    }
}
kotlin.target.compilations.getByName("cliTest").apply {
    associateWith(kotlin.target.compilations.getByName("cli"))
    compileTaskProvider.configure {
        compilerOptions.apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.DEFAULT)
    }
}

val cliTestTask = tasks.register<Test>("cliTest") {
    description = "Runs the command line's tests (src/cliTest)."
    group = "verification"
    testClassesDirs = cliTest.output.classesDirs
    classpath = cliTest.runtimeClasspath
    useJUnitPlatform()
    systemProperty("pypackpack.expectedVersion", cliVersion)
}
tasks.check { dependsOn(cliTestTask) }

// `BuildInfo` reads this resource, so the CLI's version is not a literal in the source.
val generateBuildInfo by tasks.registering {
    val version = cliVersion
    val outputDir = layout.buildDirectory.dir("generated/build-info")
    inputs.property("version", version)
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().file("org/thisisthepy/python/multiplatform/packpack/utils/build-info.properties").asFile
        file.parentFile.mkdirs()
        file.writeText("version=$version\n")
    }
}
cli.resources.srcDir(generateBuildInfo)

val cliJar = tasks.register<Jar>("cliJar") {
    archiveBaseName.set("pypackpack-cli")
    archiveVersion.set(cliVersion)
    from(cli.output)
}
val cliRuntimeFiles = files(tasks.jar, cliJar) + configurations[cli.runtimeClasspathConfigurationName]

// `./gradlew :packpack:installCliDist` -> packpack/build/install/pypackpack/bin/pypackpack
val cliStartScripts = tasks.register<CreateStartScripts>("cliStartScripts") {
    applicationName = "pypackpack"
    mainClass.set(cliMainClass)
    outputDir = layout.buildDirectory.dir("cli-scripts").get().asFile
    classpath = cliRuntimeFiles
}
tasks.register<Sync>("installCliDist") {
    description = "Installs the pypackpack launcher into build/install/pypackpack."
    group = "distribution"
    into(layout.buildDirectory.dir("install/pypackpack"))
    into("lib") { from(cliRuntimeFiles) }
    into("bin") { from(cliStartScripts) }
}

// The native `pypackpack` the PyPI wheels carry (publish-pypi.yml), built from the cli source set.
graalvmNative {
    binaries {
        named("main") {
            imageName.set("pypackpack")
            mainClass.set(cliMainClass)
            classpath.setFrom(cliRuntimeFiles)
            buildArgs.addAll(
                "--no-fallback",
                "--enable-preview",
                "--install-exit-handlers",
                // io.ktor reaches org.slf4j (slf4j-nop: no I/O at init); GraalVM 21 refuses it at run time.
                "--initialize-at-build-time=kotlin,kotlinx.coroutines,io.ktor,kotlinx.io,org.slf4j",
                "-H:+ReportExceptionStackTraces",
                "-H:+AddAllCharsets",
                "--gc=serial",
            )
            val osName = System.getProperty("os.name").lowercase()
            when {
                osName.contains("windows") -> buildArgs.add("-H:NativeLinkerOption=-Wl,--allow-multiple-definition")
                // Without this the binary's minimum macOS is the build host's (26.0 on a macOS 26
                // runner), and the wheel's tag must say so (publish-pypi.yml).
                osName.contains("mac") -> buildArgs.add("-H:NativeLinkerOption=-mmacosx-version-min=11.0")
            }
            debug.set(false)
            verbose.set(true)
            resources.autodetect()
        }
    }
    // native-image comes from GRAALVM_HOME/JAVA_HOME, not a toolchain Gradle might pick.
    toolchainDetection.set(false)
}
