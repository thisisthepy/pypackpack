plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":usage-example"))
    testImplementation(kotlin("test"))
}
