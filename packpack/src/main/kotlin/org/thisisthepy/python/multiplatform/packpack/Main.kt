package org.thisisthepy.python.multiplatform.packpack

import org.thisisthepy.python.multiplatform.packpack.bundler.packaging.hostPython


fun main(args: Array<String>) {
    println("Hello, Python!")
    val hostPython = hostPython()
    hostPython.downloadPythonStandalone("macos", "aarch64")
}
