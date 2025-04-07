package org.thisisthepy.python.multiplatform.packpack.core

import java.io.File

class PyprojectTomlHandler {
    fun createRootProjectToml(projectName: String, projectDir: File): File {
        val pyprojectToml = File(projectDir, "pyproject.toml")
        val content = """
            [project]
            name = "${projectName.replace("_", "-")}"
            version = "0.1.0"
            description = "Add your description here"
            readme = "README.md"
            requires-python = ">=3.13"
            dependencies = [
            ]

            [tool.ppp]
            use-ppp = true

            [tool.ppp.sub-modules]
        """.trimIndent()

        pyprojectToml.writeText(content)
        return pyprojectToml
    }

    fun createModuleProjectToml(moduleName: String, moduleDir: File, parentModuleName: String): File {
        val modulePyprojectToml = File(moduleDir, "pyproject.toml")
        val moduleContent = """
            [project]
            name = "$moduleName"
            version = "0.1.0"
            description = "Submodule of the project"
            requires-python = ">=3.13"
            dependencies = [
                # Add module-specific dependencies here
            ]
            
            [build-system]
            requires = ["ppp"]
            build-backend = "ppp-build"
            
            [tool.ppp]
            parent-module = "$parentModuleName"
        """.trimIndent()

        modulePyprojectToml.writeText(moduleContent)
        return modulePyprojectToml
    }

    fun addEntryToSection(tomlFile: File, sectionName: String, entry: String) {
        val lines = tomlFile.readLines()
        val updatedContent = lines.toMutableList()
        val sectionIndex = lines.indexOfFirst { it.trim() == sectionName }

        var endIndex = lines.size
        for (i in sectionIndex + 1 until lines.size) {
            if (lines[i].trim().startsWith("[") && lines[i].trim().endsWith("]")) {
                endIndex = i
                break
            }
        }

        var insertIndex = sectionIndex + 1
        for (i in sectionIndex + 1 until endIndex) {
            val line = lines[i].trim()
            if (line.isNotEmpty() && !line.startsWith("#")) {
                insertIndex = i + 1
            }
        }

        updatedContent.add(insertIndex, entry)
        tomlFile.writeText(updatedContent.joinToString("\n"))
    }

    fun removeEntryFromSection(tomlFile: File, sectionName: String, entryName: String): Boolean {
        val lines = tomlFile.readLines()
        val sectionIndex = lines.indexOfFirst { it.trim() == sectionName }

        if (sectionIndex != -1) {
            val updatedContent = removeEntryFromSection(lines, sectionIndex, entryName)
            tomlFile.writeText(updatedContent.joinToString("\n"))
            return true
        }

        return false
    }

    private fun removeEntryFromSection(lines: List<String>, sectionIndex: Int, moduleName: String): List<String> {
        val updatedContent = lines.toMutableList()
        val moduleEntryPattern = "^\\s*$moduleName\\s*=.*".toRegex()

        var endIndex = lines.size
        for (i in sectionIndex + 1 until lines.size) {
            if (lines[i].trim().startsWith("[") && lines[i].trim().endsWith("]")) {
                endIndex = i
                break
            }
        }

        for (i in sectionIndex + 1 until endIndex) {
            if (moduleEntryPattern.matches(lines[i])) {
                updatedContent.removeAt(i)
                return updatedContent
            }
        }

        return updatedContent
    }
}