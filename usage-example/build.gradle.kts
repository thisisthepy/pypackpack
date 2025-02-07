plugins {
    alias(libs.plugins.kotlin.multiplatform)
}


kotlin {
    jvm("desktop")
    
    sourceSets {
        val desktopMain by getting
    }
}
