package org.thisisthepy.python.multiplatform.packpack.runtime

import org.thisisthepy.python.multiplatform.packpack.core.PyprojectTomlHandler
import java.io.File

class CrossEnv {
    private var _currentDir: File? = null
    private val currentDir: File
        get() {
            if (_currentDir == null) {
                _currentDir = File(System.getProperty("user.dir"))
            }
            return _currentDir!!
        }

    private val handler = PyprojectTomlHandler()

    fun init(projectName: String? = null, path: String? = null) {
        setCurrentDir(path)
        val actualProjectName = projectName ?: currentDir.name
        val projectDir = if (actualProjectName != currentDir.name) {
            val newDir = File(currentDir, actualProjectName)
            if (!newDir.exists()) {
                newDir.mkdir()
            }
            newDir
        } else {
            currentDir
        }
        val pyprojectToml = File(projectDir, "pyproject.toml")

        if (!pyprojectToml.exists()) {
            handler.createRootProjectToml(projectName = actualProjectName, projectDir = projectDir)
            println("Initialized project '${projectDir.name}' at '$projectDir'") // Print success message
        } else {
            // Check PPP configuration if the file exists
            val lines = pyprojectToml.readLines()
            val pppSectionIndex = lines.indexOfFirst { it.trim() == "[tool.ppp]" }

            if (pppSectionIndex != -1) {
                // Check if use-ppp is enabled when [tool.ppp] section exists
                val usePppPattern = "^\\s*use-ppp\\s*=\\s*true\\s*$".toRegex()
                for (i in pppSectionIndex + 1 until lines.size) {
                    if (lines[i].trim().startsWith("[")) break // Stop when next section begins
                    if (usePppPattern.matches(lines[i])) {
                        println("\u001B[31mError: \u001B[0mProject is already initialized in '$projectDir' ('pyproject.toml' file exists)")
                        return
                    }
                }
                println("\u001B[31mError: \u001B[0mAnother packaging tool is being used in this project (use-ppp is not true)")
            } else {
                println("\u001B[31mError: \u001B[0mThis project is configured to use another packaging tool (missing [tool.ppp] section)")
            }
        }
    }

    fun addModule(moduleName: String, path: String? = null) {
        setCurrentDir(path)
        val moduleDir = File(currentDir, moduleName)
        val rootPyprojectToml = File(currentDir, "pyproject.toml")
        if (!rootPyprojectToml.exists()) {
            println("\u001B[31mError: \u001B[0mProject not initialized (pyproject.toml not found in $currentDir)")
            return
        }
        val modulePyprojectToml = File(moduleDir, "pyproject.toml")

        // Create module directory if it doesn't exist
        if (!moduleDir.exists()) {
            moduleDir.mkdirs()
        } else {
            println("\u001B[31mError: \u001B[0mModule directory already exists: $moduleName")
        }

        // Create module's pyproject.toml with basic configuration
        if (!modulePyprojectToml.exists()) {
            handler.createModuleProjectToml(moduleName = moduleName, moduleDir = moduleDir, parentModuleName = rootPyprojectToml.parentFile.name)
        }

        // Add module to the root pyproject.toml
        if (rootPyprojectToml.exists()) {
            val lines = rootPyprojectToml.readLines()
            val sectionExists = lines.any { it.trim() == "[tool.ppp.sub-modules]" }
            val moduleEntry = "$moduleName = { path = \"$moduleName\" }"

            if (sectionExists) {
                // If the section exists, add the module entry
                handler.addEntryToSection(rootPyprojectToml, "[tool.ppp.sub-modules]", moduleEntry)
            } else {
                val content = """
                [tool.ppp.sub-modules]
                $moduleEntry
                """.trimIndent()
                rootPyprojectToml.appendText("\n\n$content")
            }

            println("Module '$moduleName' added to the project at '$moduleDir'") // Print success message
        } else {
            println("\u001B[31mError: \u001B[0mpyproject.toml does not exist in $currentDir")
        }
    }

    fun removeModule(moduleName: String, path: String? = null) {
        setCurrentDir(path)
        val moduleDir = File(currentDir, moduleName)
        val rootPyprojectToml = File(currentDir, "pyproject.toml")

        // Remove module directory
        if (moduleDir.exists()) {
            if (moduleDir.deleteRecursively()) {
            } else {
                println("\u001B[31mError: \u001B[0mFailed to delete module directory '$moduleName'")
            }
        } else {
            println("\u001B[31mError: \u001B[0mModule directory '$moduleName' does not exist")
        }

        // Remove module entry from pyproject.toml
        if (rootPyprojectToml.exists()) {
            val removeEntryFromSection = handler.removeEntryFromSection(tomlFile = rootPyprojectToml, sectionName = "[tool.ppp.sub-modules]", entryName = moduleName)

            if (removeEntryFromSection) {
                println("Module '$moduleName' removed from the project") // Print success message
            } else {
                println("\u001B[31mError: \u001B[0mSub-modules section not found in pyproject.toml")
            }
        } else {
            println("\u001B[31mError: \u001B[0mpyproject.toml does not exist in $currentDir")
        }
    }

    private fun setCurrentDir(path: String?) {
        if (path != null && path.isNotEmpty()) {
            val newDir = when {
                File(path).isAbsolute -> File(path)
                path.startsWith("./") -> File(currentDir, path.substring(2))
                path.startsWith("../") -> File(currentDir.parentFile, path.substring(3))
                else -> File(currentDir, path)
            }

            // try to create the directory if it doesn't exist
            if (!newDir.exists()) {
                if (newDir.mkdirs()) {
                } else {
                    println("\u001B[31mError: \u001B[0mFailed to create directory path: ${newDir.absolutePath}")
                    return
                }
            }

            if (newDir.isDirectory) {
                System.setProperty("user.dir", newDir.absolutePath)
                _currentDir = newDir
            } else {
                println("\u001B[31mError: \u001B[0mThe path is not a directory: $path")
            }
        }
    }
}