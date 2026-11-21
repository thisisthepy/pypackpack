package org.thisisthepy.python.multiplatform.packpack.compile.backend.external

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Meson cross builds (docs/SPEC.md *Package build*, #155): the target CPython is found where
 * `pypackpack python install` puts it, the cross file names the native slot's compilers, and the generated
 * `meson.build` builds against the target's headers and `EXT_SUFFIX` without `import('python')`.
 */
class MesonCrossTest {
    @TempDir
    lateinit var root: File

    private fun sysconfig(lib: File, vararg pairs: Pair<String, String>) =
        File(lib, "python3.15t/_sysconfigdata_t_test.py").apply {
            parentFile.mkdirs()
            writeText("build_time_vars = {" + pairs.joinToString(", ") { "'${it.first}': '${it.second}'" } + "}\n")
        }

    private fun headers(dir: File) = File(dir, "Python.h").apply { parentFile.mkdirs(); writeText("") }

    private fun pkg(): File = File(root, "project/pkg").apply { mkdirs() }

    @Test
    fun locatesAndroidLinuxAndIosPythonsWhereInstallPutsThem() {
        val pkg = pkg()
        val android = File(root, "project/android_arm64/prefix")
        headers(File(android, "include/python3.15t"))
        sysconfig(File(android, "lib"), "EXT_SUFFIX" to ".cpython-315t-aarch64-linux-android.so", "LDLIBRARY" to "libpython3.15t.so")
        val linux = File(root, "project/linux_arm64")
        headers(File(linux, "include/python3.15t"))
        sysconfig(File(linux, "lib"), "EXT_SUFFIX" to ".cpython-315t-aarch64-linux-gnu.so")
        val slice = File(root, "project/ios_arm64/Python.xcframework/ios-arm64")
        headers(File(slice, "Python.framework/Headers"))
        sysconfig(File(slice, "lib-arm64"), "EXT_SUFFIX" to ".cpython-315t-iphoneos.so")

        val a = locateTargetPython(pkg, "aarch64-linux-android")
        assertEquals(File(android, "include/python3.15t").canonicalFile, a.includeDir.canonicalFile)
        assertEquals(".cpython-315t-aarch64-linux-android.so", a.extensionSuffix)
        assertEquals(listOf("-L${File(android, "lib").canonicalPath}", "-lpython3.15t"), a.linkArgs)

        val l = locateTargetPython(pkg, "aarch64-unknown-linux-gnu")
        assertEquals(".cpython-315t-aarch64-linux-gnu.so", l.extensionSuffix)
        assertTrue(l.linkArgs.isEmpty())

        val i = locateTargetPython(pkg, "arm64-apple-ios")
        assertEquals(listOf("-F${slice.canonicalPath}", "-framework", "Python"), i.linkArgs)

        val missing = runCatching { locateTargetPython(pkg, "x86_64-linux-android") }.exceptionOrNull()?.message.orEmpty()
        assertTrue("pypackpack python install 3.15t x86_64-linux-android" in missing, missing)
    }

    @Test
    fun crossFilesNameTheSlotsCompilersAndTheHostIsNotCrossBuilt() {
        val pkg = pkg()
        val linux = File(root, "project/linux_arm64")
        headers(File(linux, "include/python3.15t"))
        sysconfig(File(linux, "lib"), "EXT_SUFFIX" to ".cpython-315t-aarch64-linux-gnu.so")
        val slice = File(root, "project/ios_simulator_arm64/Python.xcframework/ios-arm64_x86_64-simulator")
        headers(File(slice, "Python.framework/Headers"))
        sysconfig(File(slice, "lib-arm64"), "EXT_SUFFIX" to ".cpython-315t-iphonesimulator.so")

        assertNull(MesonCross.forTarget(pkg, "aarch64-unknown-linux-gnu", hostTarget = "aarch64-unknown-linux-gnu"))

        val linuxCross = MesonCross.forTarget(pkg, "aarch64-unknown-linux-gnu", hostTarget = "x86_64-unknown-linux-gnu")!!.crossFileText()
        assertTrue("c = ['uv', 'tool', 'run', '--from', 'ziglang', 'python', '-m', 'ziglang', 'cc', '-target', 'aarch64-linux-gnu.2.17']" in linuxCross, linuxCross)
        assertTrue("system = 'linux'" in linuxCross && "cpu_family = 'aarch64'" in linuxCross, linuxCross)

        val ios = MesonCross.forTarget(pkg, "arm64-apple-ios-simulator", hostTarget = "aarch64-apple-darwin")!!.crossFileText()
        assertTrue("c = ['xcrun', '--sdk', 'iphonesimulator', 'clang', '-target', 'arm64-apple-ios13.0-simulator']" in ios, ios)
        assertTrue("system = 'darwin'" in ios && "subsystem = 'ios-simulator'" in ios, ios)

        val windows = runCatching { MesonCross.forTarget(pkg, "aarch64-pc-windows-msvc", hostTarget = "x86_64-pc-windows-msvc") }
        assertTrue("owner decision 2026-11-19" in windows.exceptionOrNull()?.message.orEmpty())
    }

    @Test
    fun crossMesonBuildUsesTheTargetHeadersAndSuffixWithoutThePythonModule() {
        val project = File(root, "cross-project").apply { mkdirs() }
        File(project, "pyproject.toml").writeText("[project]\nname = \"core\"\nversion = \"0.1.0\"\n")
        File(project, "src/main/core").mkdirs()
        File(project, "src/main/core/__init__.py").writeText("")
        File(project, "src/main/core/_fast.c").writeText("#include <Python.h>\n")
        val include = File(root, "target-include").apply { mkdirs() }
        val cross = MesonCross(
            TargetPython("aarch64-linux-android", include, ".cpython-315t-aarch64-linux-android.so", listOf("-L/t/lib", "-lpython3.15t")),
            listOf("cc"), listOf("c++"), listOf("ar"), "android", null, "aarch64",
        )

        val content = Meson().makeMesonBuild(project.absolutePath, cross = cross).getOrThrow()

        assertFalse("import('python')" in content, content)
        assertTrue("target_python_include = include_directories('${include.absolutePath}')" in content, content)
        assertTrue("shared_module(\n  '_fast',\n  files('src/main/core/_fast.c'),\n" in content, content)
        assertTrue("name_suffix: 'cpython-315t-aarch64-linux-android.so'," in content && "name_prefix: ''," in content, content)
        assertTrue("link_args: ['-L/t/lib', '-lpython3.15t']," in content, content)
        assertTrue("install_dir: 'lib/python3/site-packages/core'," in content, content)
        assertTrue("install_data(" in content, content)
    }
}
