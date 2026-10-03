plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":packpack"))
    testImplementation(kotlin("test"))
}
