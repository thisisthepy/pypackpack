plugins {
    alias(libs.plugins.kotlin.jvm)
}

group = "org.thisisthepy.python.multiplatform"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
