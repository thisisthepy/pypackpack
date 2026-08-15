plugins {
    alias(libs.plugins.kotlin.jvm)
    kotlin("plugin.serialization") version "2.3.0"
    application
    id("org.graalvm.buildtools.native")
    // `toolchain` is meant to delegate to this project rather than reimplement it, and until this
    // is published nothing outside this repository can resolve it at all -- which is what has been
    // blocking that delegation. `application` and the native-image plugin stay: the CLI binary and
    // the library are the same code, and `docs/SPEC.md` calls for both.
    `maven-publish`
}

group = "org.thisisthepy.python.multiplatform"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.clikt)
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

application {
    mainClass.set("org.thisisthepy.python.multiplatform.packpack.cli.CommandKt")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

// Custom tasks for native compilation
tasks.register("buildNativeExecutable") {
    group = "native"
    description = "Build native executable for current platform"
    dependsOn("nativeCompile")
    
    doLast {
        val osName = System.getProperty("os.name").lowercase()
        val executableName = when {
            osName.contains("windows") -> "pypackpack.exe"
            else -> "pypackpack"
        }
        
        val buildDir = layout.buildDirectory.get().asFile
        val nativeExecutable = File(buildDir, "native/nativeCompile/$executableName")
        val outputDir = File(buildDir, "distributions")
        outputDir.mkdirs()
        
        if (nativeExecutable.exists()) {
            val targetFile = File(outputDir, executableName)
            nativeExecutable.copyTo(targetFile, overwrite = true)
            println("Native executable created: ${targetFile.absolutePath}")
        } else {
            throw GradleException("Native executable not found: ${nativeExecutable.absolutePath}")
        }
    }
}

tasks.register("buildAllPlatforms") {
    group = "native"
    description = "Build native executables for all supported platforms (requires Docker)"
    
    doLast {
        println("Building for all platforms requires cross-compilation setup")
        println("This task will be implemented when cross-compilation infrastructure is ready")
    }
}

tasks.register("packageNative") {
    group = "distribution"
    description = "Package native executable with resources"
    dependsOn("buildNativeExecutable")
    
    doLast {
        val buildDir = layout.buildDirectory.get().asFile
        val distributionsDir = File(buildDir, "distributions")
        val packageDir = File(distributionsDir, "pypackpack-native")
        
        packageDir.mkdirs()
        
        // Copy executable
        val osName = System.getProperty("os.name").lowercase()
        val executableName = when {
            osName.contains("windows") -> "pypackpack.exe"
            else -> "pypackpack"
        }
        
        val executable = File(distributionsDir, executableName)
        if (executable.exists()) {
            executable.copyTo(File(packageDir, executableName), overwrite = true)
        }
        
        // Copy resources if needed
        val resourcesDir = File(projectDir, "src/main/resources")
        if (resourcesDir.exists()) {
            resourcesDir.copyRecursively(File(packageDir, "resources"), overwrite = true)
        }
        
        // Create README
        val readme = File(packageDir, "README.txt")
        readme.writeText("""
            PyPackPack Native Executable
            ===========================
            
            This is a native executable built with GraalVM Native Image.
            
            Usage: ./$executableName <command> [options]
            
            For help: ./$executableName help
        """.trimIndent())
        
        println("Native package created: ${packageDir.absolutePath}")
    }
}

// Environment variable configuration
tasks.named("nativeCompile") {
    doFirst {
        // Set GraalVM environment variables if not already set
        val graalvmHome = System.getenv("GRAALVM_HOME")
        if (graalvmHome == null) {
            println("Warning: GRAALVM_HOME environment variable is not set")
            println("Please set GRAALVM_HOME to your GraalVM installation directory")
        }
        
        // Print build information
        println("Building native image with the following configuration:")
        println("- Java Version: ${System.getProperty("java.version")}")
        println("- OS: ${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
        println("- GraalVM Home: ${graalvmHome ?: "Not set"}")
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("pypackpack")
            mainClass.set("org.thisisthepy.python.multiplatform.packpack.cli.CommandKt")
            // Build arguments for optimization
            buildArgs.addAll(
                "--no-fallback",
                "--enable-preview",
                "--install-exit-handlers",
                "--initialize-at-build-time=kotlin,kotlinx.coroutines,io.ktor,kotlinx.io",
                "-H:+ReportExceptionStackTraces",
                "-H:+AddAllCharsets",
                "--gc=serial"
            )

            // Platform-specific optimizations
            val osName = System.getProperty("os.name").lowercase()
            when {
                osName.contains("windows") -> {
                    // Windows-specific optimizations
                    buildArgs.addAll(
                        "-H:NativeLinkerOption=-Wl,--allow-multiple-definition"
                    )
                }
                osName.contains("linux") -> {
                    // Default to dynamic linking on glibc-based Linux.
                    // Static builds require a musl toolchain and separate setup.
                }
                osName.contains("mac") -> {
                    // macOS uses default dynamic linking - no additional flags needed
                }
            }

            // Debug build configuration
            debug.set(false)
            verbose.set(true)

            // Resource configuration
            resources.autodetect()
        }
    }
    // When toolchain detection is disabled, the plugin uses GRAALVM_HOME/JAVA_HOME.
    // This avoids Gradle selecting a cached GraalVM toolchain that may not have a
    // working native-image binary.
    toolchainDetection.set(false)
}

// ---------------------------------------------------------------------------------------------
// Publishing
//
// `toolchain` consumes this as an ordinary Maven dependency. `docs/SPEC.md` names the coordinate,
// and `group`/`version` above already carry it, so the publication only has to name the component.
//
// `from(components["java"])` and not the shadow/native artefacts: what a consumer needs is the
// library, and the CLI binary is a different deliverable of the same source. Publishing the
// application distribution here would put a launcher script and every runtime jar on the
// consumer's compile classpath.
//
// The CLI's own dependencies do reach a consumer, because they are declared `implementation` and
// that maps to POM scope `runtime`. Clikt is the visible one -- a library consumer resolves an
// argument parser it never calls. Narrowing that means splitting the CLI out of this module, which
// is a larger change than this, so it is recorded rather than done.
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
