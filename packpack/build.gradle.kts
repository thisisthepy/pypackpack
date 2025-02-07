plugins {
    alias(libs.plugins.kotlin.multiplatform)
}


group = rootProject.group
version = rootProject.version


kotlin {
    jvm("gradle")

    listOf(
        mingwX64(),
        linuxX64(),
        linuxArm64(),
        macosArm64(),
        macosX64()
    ).forEach { target ->
        target.binaries {
            executable {
                entryPoint("org.thisisthepy.python.multiplatform.packpack.main")
                version = "1.0.0"
            }
            sharedLib {
                baseName = "packpack"
            }
        }
    }
}
