package org.thisisthepy.python.multiplatform.packpack.config

import java.io.File
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * Configuration migration utilities for PyPackPack
 * Handles schema version upgrades and legacy format conversions
 */
class ConfigMigration {
    
    private val parser = PyProjectParser()
    
    /**
     * Migrate configuration from one schema version to another
     * 
     * @param config Current configuration
     * @param targetVersion Target schema version
     * @return Migrated configuration
     */
    fun migrateSchema(config: PyProjectConfig, targetVersion: String): PyProjectConfig {
        val currentVersion = config.tool?.pypackpack?.version ?: "1.0"
        
        return when {
            currentVersion == targetVersion -> config
            currentVersion == "1.0" && targetVersion == "1.1" -> migrateFrom1_0To1_1(config)
            else -> throw UnsupportedMigrationException(
                "Migration from version $currentVersion to $targetVersion is not supported"
            )
        }
    }
    
    /**
     * Convert setup.py to pyproject.toml configuration
     * 
     * @param setupPyPath Path to setup.py file
     * @param projectName Project name (required)
     * @return Converted PyProjectConfig
     */
    fun convertFromSetupPy(setupPyPath: Path, projectName: String): PyProjectConfig {
        if (!setupPyPath.exists()) {
            throw MigrationException("setup.py file not found: $setupPyPath")
        }
        
        val setupPyContent = setupPyPath.readText()
        val extractedInfo = extractSetupPyInfo(setupPyContent)
        
        return PyProjectConfig(
            project = ProjectConfig(
                name = extractedInfo.name ?: projectName,
                version = extractedInfo.version ?: "0.1.0",
                description = extractedInfo.description,
                authors = extractedInfo.author?.let { 
                    listOf(AuthorConfig(name = it, email = extractedInfo.authorEmail))
                },
                dependencies = extractedInfo.installRequires ?: emptyList(),
                requiresPython = extractedInfo.pythonRequires,
                classifiers = extractedInfo.classifiers,
                keywords = extractedInfo.keywords,
                urls = extractedInfo.urls
            ),
            buildSystem = BuildSystemConfig(
                requires = listOf("pypackpack"),
                buildBackend = "pypackpack.build"
            ),
            tool = ToolConfig(
                pypackpack = PyPackPackConfig(
                    version = "1.0",
                    build = BuildConfig(),
                    bundle = BundleConfig(),
                    dependencies = DependencyConfig()
                )
            )
        )
    }
    
    /**
     * Convert setup.cfg to pyproject.toml configuration
     * 
     * @param setupCfgPath Path to setup.cfg file
     * @param projectName Project name (required)
     * @return Converted PyProjectConfig
     */
    fun convertFromSetupCfg(setupCfgPath: Path, projectName: String): PyProjectConfig {
        if (!setupCfgPath.exists()) {
            throw MigrationException("setup.cfg file not found: $setupCfgPath")
        }
        
        val setupCfgContent = setupCfgPath.readText()
        val extractedInfo = extractSetupCfgInfo(setupCfgContent)
        
        return PyProjectConfig(
            project = ProjectConfig(
                name = extractedInfo.name ?: projectName,
                version = extractedInfo.version ?: "0.1.0",
                description = extractedInfo.description,
                authors = extractedInfo.author?.let { 
                    listOf(AuthorConfig(name = it, email = extractedInfo.authorEmail))
                },
                dependencies = extractedInfo.installRequires ?: emptyList(),
                requiresPython = extractedInfo.pythonRequires,
                classifiers = extractedInfo.classifiers,
                keywords = extractedInfo.keywords
            ),
            buildSystem = BuildSystemConfig(
                requires = listOf("pypackpack"),
                buildBackend = "pypackpack.build"
            ),
            tool = ToolConfig(
                pypackpack = PyPackPackConfig(
                    version = "1.0",
                    build = BuildConfig(),
                    bundle = BundleConfig(),
                    dependencies = DependencyConfig()
                )
            )
        )
    }
    
    /**
     * Detect and convert from any legacy format to pyproject.toml
     * 
     * @param projectDir Project directory to scan
     * @param projectName Project name
     * @return Converted configuration or null if no legacy files found
     */
    fun autoConvertLegacy(projectDir: Path, projectName: String): PyProjectConfig? {
        val setupPy = projectDir.resolve("setup.py")
        val setupCfg = projectDir.resolve("setup.cfg")
        
        return when {
            setupPy.exists() -> convertFromSetupPy(setupPy, projectName)
            setupCfg.exists() -> convertFromSetupCfg(setupCfg, projectName)
            else -> null
        }
    }
    
    /**
     * Check if migration is needed for a configuration
     * 
     * @param config Configuration to check
     * @param targetVersion Target version to check against
     * @return true if migration is needed
     */
    fun isMigrationNeeded(config: PyProjectConfig, targetVersion: String): Boolean {
        val currentVersion = config.tool?.pypackpack?.version ?: "1.0"
        return currentVersion != targetVersion
    }
    
    /**
     * Get available migration paths from a given version
     * 
     * @param fromVersion Source version
     * @return List of available target versions
     */
    fun getAvailableMigrations(fromVersion: String): List<String> {
        return when (fromVersion) {
            "1.0" -> listOf("1.1")
            else -> emptyList()
        }
    }
    
    private fun migrateFrom1_0To1_1(config: PyProjectConfig): PyProjectConfig {
        // Example migration: Add new fields introduced in version 1.1
        val updatedPyPackPack = config.tool?.pypackpack?.copy(
            version = "1.1",
            // Add any new fields that were introduced in 1.1
            dependencies = config.tool.pypackpack.dependencies ?: DependencyConfig()
        )
        
        return config.copy(
            tool = config.tool?.copy(
                pypackpack = updatedPyPackPack
            )
        )
    }
    
    private fun extractSetupPyInfo(content: String): SetupInfo {
        val info = SetupInfo()
        
        // Extract name
        Regex("""name\s*=\s*["']([^"']+)["']""").find(content)?.let {
            info.name = it.groupValues[1]
        }
        
        // Extract version
        Regex("""version\s*=\s*["']([^"']+)["']""").find(content)?.let {
            info.version = it.groupValues[1]
        }
        
        // Extract description
        Regex("""description\s*=\s*["']([^"']+)["']""").find(content)?.let {
            info.description = it.groupValues[1]
        }
        
        // Extract author
        Regex("""author\s*=\s*["']([^"']+)["']""").find(content)?.let {
            info.author = it.groupValues[1]
        }
        
        // Extract author_email
        Regex("""author_email\s*=\s*["']([^"']+)["']""").find(content)?.let {
            info.authorEmail = it.groupValues[1]
        }
        
        // Extract python_requires
        Regex("""python_requires\s*=\s*["']([^"']+)["']""").find(content)?.let {
            info.pythonRequires = it.groupValues[1]
        }
        
        // Extract install_requires (improved with bracket counting)
        extractListFromSetupPy(content, "install_requires")?.let {
            info.installRequires = it
        }
        
        // Extract classifiers (improved with bracket counting)
        extractListFromSetupPy(content, "classifiers")?.let {
            info.classifiers = it
        }
        
        return info
    }
    
    private fun extractListFromSetupPy(content: String, listName: String): List<String>? {
        // Find the start of the list
        val startPattern = Regex("""$listName\s*=\s*\[""")
        val startMatch = startPattern.find(content) ?: return null
        
        var pos = startMatch.range.last + 1
        var bracketCount = 1
        var inString = false
        var stringChar = '\u0000'
        var escaped = false
        
        // Find the matching closing bracket
        while (pos < content.length && bracketCount > 0) {
            val char = content[pos]
            
            when {
                escaped -> escaped = false
                char == '\\' -> escaped = true
                !inString && (char == '"' || char == '\'') -> {
                    inString = true
                    stringChar = char
                }
                inString && char == stringChar -> inString = false
                !inString && char == '[' -> bracketCount++
                !inString && char == ']' -> bracketCount--
            }
            pos++
        }
        
        if (bracketCount != 0) return null
        
        // Extract the content between brackets
        val listContent = content.substring(startMatch.range.last + 1, pos - 1)
        
        // Extract quoted strings more carefully
        val result = mutableListOf<String>()
        var stringPos = 0
        
        while (stringPos < listContent.length) {
            val char = listContent[stringPos]
            
            if (char == '"' || char == '\'') {
                val quote = char
                val start = stringPos + 1
                stringPos++
                
                // Find the matching closing quote
                while (stringPos < listContent.length) {
                    if (listContent[stringPos] == '\\') {
                        stringPos += 2 // Skip escaped character
                    } else if (listContent[stringPos] == quote) {
                        result.add(listContent.substring(start, stringPos))
                        stringPos++
                        break
                    } else {
                        stringPos++
                    }
                }
            } else {
                stringPos++
            }
        }
        
        return result
    }
    
    private fun extractSetupCfgInfo(content: String): SetupInfo {
        val info = SetupInfo()
        val lines = content.lines()
        var currentSection = ""
        var currentKey = ""
        var multiLineValues = mutableListOf<String>()
        
        fun processMultiLineValue() {
            if (currentKey.isNotEmpty() && multiLineValues.isNotEmpty()) {
                when (currentSection) {
                    "metadata" -> {
                        when (currentKey) {
                            "classifiers" -> {
                                info.classifiers = multiLineValues.filter { it.isNotEmpty() }
                            }
                        }
                    }
                    "options" -> {
                        when (currentKey) {
                            "install_requires" -> {
                                info.installRequires = multiLineValues.filter { it.isNotEmpty() }
                            }
                        }
                    }
                }
                multiLineValues.clear()
                currentKey = ""
            }
        }
        
        for (line in lines) {
            val trimmed = line.trim()
            
            // Section headers
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                processMultiLineValue() // Process any pending multi-line value
                currentSection = trimmed.substring(1, trimmed.length - 1)
                continue
            }
            
            // Skip comments and empty lines
            if (trimmed.startsWith("#") || trimmed.isEmpty()) continue
            
            // Check if this is a continuation line (starts with whitespace in original line)
            if (line.startsWith("    ") || line.startsWith("\t")) {
                if (currentKey.isNotEmpty()) {
                    multiLineValues.add(trimmed)
                }
                continue
            }
            
            // Process any pending multi-line value before starting a new key
            processMultiLineValue()
            
            // Parse key-value pairs
            val parts = trimmed.split("=", limit = 2)
            if (parts.size != 2) continue
            
            val key = parts[0].trim()
            val value = parts[1].trim()
            
            when (currentSection) {
                "metadata" -> {
                    when (key) {
                        "name" -> info.name = value
                        "version" -> info.version = value
                        "description" -> info.description = value
                        "author" -> info.author = value
                        "author_email" -> info.authorEmail = value
                        "python_requires" -> info.pythonRequires = value
                        "classifiers" -> {
                            currentKey = key
                            if (value.isNotEmpty()) {
                                multiLineValues.add(value)
                            }
                        }
                    }
                }
                "options" -> {
                    when (key) {
                        "install_requires" -> {
                            currentKey = key
                            if (value.isNotEmpty()) {
                                multiLineValues.add(value)
                            }
                        }
                    }
                }
            }
        }
        
        // Process any remaining multi-line value
        processMultiLineValue()
        
        return info
    }
    
    private data class SetupInfo(
        var name: String? = null,
        var version: String? = null,
        var description: String? = null,
        var author: String? = null,
        var authorEmail: String? = null,
        var pythonRequires: String? = null,
        var installRequires: List<String>? = null,
        var classifiers: List<String>? = null,
        var keywords: List<String>? = null,
        var urls: Map<String, String>? = null
    )
}

/**
 * Exception thrown when migration fails
 */
class MigrationException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * Exception thrown when an unsupported migration is requested
 */
class UnsupportedMigrationException(
    message: String
) : Exception(message) 