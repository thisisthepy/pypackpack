plugins {
    alias(libs.plugins.kotlin.jvm)
    application
    id("org.graalvm.buildtools.native")
}

group = "org.thisisthepy.python.multiplatform"
// The CLI's version, which `pypackpack version` prints and the PyPI wheel carries: publish-pypi.yml
// refuses to publish unless it equals pyproject.toml's. `:packpack` versions separately, because
// toolchain resolves `packpack:0.1.0` from mavenLocal.
version = "0.2.0"

repositories {
    mavenCentral()
}

dependencies {
    // The library: `bundle.*`, `dependency.*`, `compile.*`, `utils.*`. Depending on the sibling
    // project (not the published coordinate) so this module always builds against the library's
    // current source, not whatever was last published to mavenLocal.
    implementation(project(":packpack"))
    implementation(libs.clikt)
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
}

application {
    mainClass.set("org.thisisthepy.python.multiplatform.packpack.cli.CommandKt")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
    systemProperty("pypackpack.expectedVersion", project.version.toString())
}

// `BuildInfo` reads this resource, so the version is not a literal in the source.
val generateBuildInfo by tasks.registering {
    val cliVersion = project.version.toString()
    val outputDir = layout.buildDirectory.dir("generated/build-info")
    inputs.property("version", cliVersion)
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().file("org/thisisthepy/python/multiplatform/packpack/cli/build-info.properties").asFile
        file.parentFile.mkdirs()
        file.writeText("version=$cliVersion\n")
    }
}
sourceSets.main { resources.srcDir(generateBuildInfo) }

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
                    // Without this the binary's minimum macOS is the build host's (26.0 on a
                    // macOS 26 runner), and the wheel's tag must say so (publish-pypi.yml).
                    buildArgs.add("-H:NativeLinkerOption=-mmacosx-version-min=11.0")
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
