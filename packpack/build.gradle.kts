plugins {
    alias(libs.plugins.kotlin.jvm)
    id("org.graalvm.buildtools.native") version "0.10.6"
}

group = "org.thisisthepy.python.multiplatform"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("pypackpack")
            mainClass.set("org.thisisthepy.python.multiplatform.packpack.util.CommandLineKt")
            javaLauncher.set(javaToolchains.launcherFor {
                languageVersion.set(JavaLanguageVersion.of(22))
            })
        }
    }
    toolchainDetection.set(false)
}
