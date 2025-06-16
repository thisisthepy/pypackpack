plugins {
    alias(libs.plugins.kotlin.jvm)
    kotlin("plugin.serialization") version "2.1.20"
    application
    id("org.graalvm.buildtools.native")
}

group = "org.thisisthepy.python.multiplatform"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.koin.core)
    implementation("com.akuleshov7:ktoml-core:0.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.8.1")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
}

application {
    mainClass.set("org.thisisthepy.python.multiplatform.packpack.util.CommandLineKt")
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
        vendor.set(JvmVendorSpec.GRAAL_VM)
    }
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("pypackpack")
            mainClass.set("org.thisisthepy.python.multiplatform.packpack.util.CommandLineKt")
            javaLauncher.set(javaToolchains.launcherFor {
                languageVersion.set(JavaLanguageVersion.of(21))
                vendor.set(JvmVendorSpec.GRAAL_VM)
            })
            
            // Build arguments for optimization
            buildArgs.addAll(
                "--no-fallback",
                "--enable-preview",
                "--install-exit-handlers",
                "--initialize-at-build-time=kotlin,kotlinx.coroutines,org.koin",
                "--initialize-at-run-time=org.thisisthepy.python.multiplatform.packpack.util.Downloader,kotlin.uuid.SecureRandomHolder",
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
                    buildArgs.addAll(
                        "--static",
                        "-H:+StaticExecutableWithDynamicLibC"
                    )
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
    toolchainDetection.set(false)
}
