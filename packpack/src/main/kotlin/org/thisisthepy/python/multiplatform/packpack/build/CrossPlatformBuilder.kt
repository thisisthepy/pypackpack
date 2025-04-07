package org.thisisthepy.python.multiplatform.packpack.build

import org.thisisthepy.python.multiplatform.packpack.core.Utils
import java.io.File

class CrossPlatformBuilder {
    private val utils = Utils()
    private val os = utils.os
    private val arch = utils.arch

    private val projectDir: String = System.getProperty("user.dir")
    private val toolsDir = File(projectDir, "tools")
    private val uvDir = File(toolsDir, "uv-aarch64-apple-darwin")
    private val uvExecutable = File(uvDir, "uv")
    private val pythonPath = File(uvDir, "cpython-3.13.2-$os-$arch-none")
    private val venvDir = File(uvDir, "venv")
    private val venvPythonDir = File(venvDir, "bin/python")
    private val ndkDir = File(toolsDir, "android-ndk-r28")
    val mesonExecutable = File(venvDir, "bin/meson")

    fun init() {
        utils.downloadAndExtractUV()
        utils.downloadAndExtractNdk()

        val pythonInstaller = ProcessBuilder(
            uvExecutable.absolutePath, "python", "install", "3.13", "--install-dir", uvDir.absolutePath,
        )
        pythonInstaller.directory(uvDir)
        utils.executeProcess(pythonInstaller, "UV python install")
        println("Python 3.13 installation completed successfully")

        val venvCreator = ProcessBuilder(
            uvExecutable.absolutePath, "venv", "venv", "--python", pythonPath.absolutePath,
        )
        venvCreator.directory(uvDir)
        utils.executeProcess(venvCreator, "UV venv creation")
        println("venv created successfully")

        val mesonInstaller = ProcessBuilder(
            uvExecutable.absolutePath, "pip", "install", "meson-python", "--python", venvPythonDir.absolutePath,
        )
        mesonInstaller.directory(uvDir)
        utils.executeProcess(mesonInstaller, "UV meson installation")
        println("meson installation completed successfully")
    }

    fun mesonTest(
        outputPath: File = File(toolsDir, "cross-compile.ini"),
        cCompiler: File = File(ndkDir, "toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android21-clang"),
        cppCompiler: File = File(ndkDir, "toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android21-clang++"),
        strip: File = File(ndkDir, "toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-strip"),
    ) {

        val filePath = utils.generateMesonCompilerFile(
            outputPath = outputPath.absolutePath,
            c = cCompiler.absolutePath,
            cpp = cppCompiler.absolutePath,
            strip = strip.absolutePath,
        )
        println("Compiler configuration file has been created: $filePath")
    }
}
