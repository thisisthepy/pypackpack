plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.kotlin.multiplatform).apply(false)
}

group = "org.thisisthepy.python.multiplatform"
version = "1.0.0-alpha01"
