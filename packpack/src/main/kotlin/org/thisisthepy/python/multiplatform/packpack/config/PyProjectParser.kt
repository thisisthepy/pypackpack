package org.thisisthepy.python.multiplatform.packpack.config

import com.akuleshov7.ktoml.Toml
import com.akuleshov7.ktoml.TomlInputConfig
import com.akuleshov7.ktoml.TomlOutputConfig
import com.akuleshov7.ktoml.exceptions.TomlDecodingException
import com.akuleshov7.ktoml.exceptions.TomlEncodingException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Parser and manager for pyproject.toml configuration files
 * Uses ktoml library for TOML 1.0.0 compliant parsing and serialization
 * Includes PEP 518/517 compliance validation and support
 */
class PyProjectParser {
    
    private val toml = Toml(
        inputConfig = TomlInputConfig(
            ignoreUnknownNames = true,  // Allow unknown fields for forward compatibility
            allowEmptyValues = true,    // Allow empty values in TOML
            allowNullValues = true,     // Allow null values for nullable fields
            allowEscapedQuotesInLiteralStrings = true,
            allowEmptyToml = false,     // Require non-empty TOML files
            ignoreDefaultValues = false // Include default values in parsing
        ),
        outputConfig = TomlOutputConfig(
            indentation = com.akuleshov7.ktoml.TomlIndentation.FOUR_SPACES
        )
    )
    
    private val validator = ConfigValidator()
    private val pepSupport = PEPSupport()
    
    /**
     * Parse pyproject.toml from a file path
     * 
     * @param filePath Path to the pyproject.toml file
     * @param applyDefaults Whether to apply default values to the configuration
     * @param validateConfig Whether to validate the configuration
     * @return Parsed PyProjectConfig object
     * @throws PyProjectParseException if parsing fails
     */
    fun parseFromFile(
        filePath: Path, 
        applyDefaults: Boolean = true, 
        validateConfig: Boolean = true
    ): PyProjectConfig {
        if (!filePath.exists()) {
            throw PyProjectParseException("pyproject.toml file not found: $filePath")
        }
        
        return try {
            val content = filePath.readText()
            parseFromString(content, applyDefaults, validateConfig)
        } catch (e: Exception) {
            throw PyProjectParseException("Failed to read pyproject.toml file: $filePath", e)
        }
    }
    
    /**
     * Parse pyproject.toml from a File object
     * 
     * @param file File object pointing to pyproject.toml
     * @param applyDefaults Whether to apply default values to the configuration
     * @param validateConfig Whether to validate the configuration
     * @return Parsed PyProjectConfig object
     * @throws PyProjectParseException if parsing fails
     */
    fun parseFromFile(
        file: File, 
        applyDefaults: Boolean = true, 
        validateConfig: Boolean = true
    ): PyProjectConfig {
        return parseFromFile(file.toPath(), applyDefaults, validateConfig)
    }
    
    /**
     * Parse pyproject.toml from a string
     * 
     * @param content TOML content as string
     * @param applyDefaults Whether to apply default values to the configuration
     * @param validateConfig Whether to validate the configuration
     * @return Parsed PyProjectConfig object
     * @throws PyProjectParseException if parsing fails
     */
    fun parseFromString(
        content: String, 
        applyDefaults: Boolean = true, 
        validateConfig: Boolean = true
    ): PyProjectConfig {
        return try {
            var config = toml.decodeFromString<PyProjectConfig>(content)
            
            if (applyDefaults) {
                config = validator.applyDefaults(config)
            }

            if (validateConfig) {
                val validationResult = validator.validate(config)
                if (!validationResult.isValid) {
                    throw PyProjectParseException("Configuration validation failed:\n${validationResult.getErrorMessage()}")
                }
            }
            
            config
        } catch (e: TomlDecodingException) {
            throw PyProjectParseException("Failed to parse TOML content: ${e.message}", e)
        } catch (e: ConfigValidationException) {
            throw PyProjectParseException("Configuration validation failed: ${e.message}", e)
        } catch (e: Exception) {
            throw PyProjectParseException("Unexpected error during TOML parsing: ${e.message}", e)
        }
    }
    
    /**
     * Write PyProjectConfig to a file
     * 
     * @param config Configuration object to write
     * @param filePath Path where to write the pyproject.toml file
     * @throws PyProjectParseException if writing fails
     */
    fun writeToFile(config: PyProjectConfig, filePath: Path) {
        try {
            val content = toml.encodeToString(config)
            filePath.writeText(content)
        } catch (e: TomlEncodingException) {
            throw PyProjectParseException("Failed to encode TOML content: ${e.message}", e)
        } catch (e: Exception) {
            throw PyProjectParseException("Failed to write pyproject.toml file: $filePath", e)
        }
    }
    
    /**
     * Write PyProjectConfig to a File object
     * 
     * @param config Configuration object to write
     * @param file File object where to write
     * @throws PyProjectParseException if writing fails
     */
    fun writeToFile(config: PyProjectConfig, file: File) {
        writeToFile(config, file.toPath())
    }
    
    /**
     * Convert PyProjectConfig to TOML string
     * 
     * @param config Configuration object to convert
     * @return TOML content as string
     * @throws PyProjectParseException if encoding fails
     */
    fun writeToString(config: PyProjectConfig): String {
        return try {
            toml.encodeToString(config)
        } catch (e: TomlEncodingException) {
            throw PyProjectParseException("Failed to encode TOML content: ${e.message}", e)
        } catch (e: Exception) {
            throw PyProjectParseException("Unexpected error during TOML encoding: ${e.message}", e)
        }
    }
    
    /**
     * Validate a PyProjectConfig object
     * 
     * @param config Configuration to validate
     * @return ValidationResult containing validation status and any errors
     */
    fun validate(config: PyProjectConfig): ValidationResult {
        return validator.validate(config)
    }
    
    /**
     * Validate and apply defaults to a PyProjectConfig
     * 
     * @param config Configuration to validate and enhance
     * @return Enhanced configuration with defaults applied
     * @throws PyProjectParseException if validation fails
     */
    fun validateAndApplyDefaults(config: PyProjectConfig): PyProjectConfig {
        return try {
            validator.validateAndApplyDefaults(config)
        } catch (e: ConfigValidationException) {
            throw PyProjectParseException("Configuration validation failed: ${e.message}", e)
        }
    }
    
    /**
     * Check if a pyproject.toml file exists and is valid
     * 
     * @param filePath Path to check
     * @return true if file exists and can be parsed, false otherwise
     */
    fun isValidPyProjectFile(filePath: Path): Boolean {
        return try {
            if (!filePath.exists()) return false
            val config = parseFromFile(filePath)
            validate(config).isValid
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Create a default PyProjectConfig for a new project
     * 
     * @param projectName Name of the project
     * @param pythonVersion Python version requirement (optional)
     * @return Default configuration
     */
    fun createDefault(projectName: String, pythonVersion: String? = null): PyProjectConfig {
        return PyProjectConfig(
            project = ProjectConfig(
                name = projectName,
                version = "0.1.0",
                description = "A Python project built with PyPackPack",
                requiresPython = pythonVersion ?: ">=3.8",
                dependencies = emptyList()
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
     * Check PEP 518/517 compliance of a configuration
     * 
     * @param config Configuration to check
     * @return Detailed compliance report
     */
    fun checkPEPCompliance(config: PyProjectConfig): ComplianceReport {
        return pepSupport.checkCompliance(config)
    }
    
    /**
     * Check PEP 518/517 compliance of a pyproject.toml file
     * 
     * @param filePath Path to the pyproject.toml file
     * @return Detailed compliance report
     */
    fun checkPEPCompliance(filePath: Path): ComplianceReport {
        val config = parseFromFile(filePath, applyDefaults = false, validateConfig = false)
        return pepSupport.checkCompliance(config)
    }
    
    /**
     * Generate a PEP 518/517 compliant build-system configuration
     * 
     * @param backend Build backend to use (optional, defaults to pypackpack.build)
     * @param additionalRequirements Additional build requirements
     * @return PEP 518/517 compliant BuildSystemConfig
     */
    fun generateCompliantBuildSystem(
        backend: String? = null,
        additionalRequirements: List<String> = emptyList()
    ): BuildSystemConfig {
        return pepSupport.generateCompliantBuildSystem(backend, additionalRequirements)
    }
    
    /**
     * Migrate legacy setup.py/setup.cfg configuration to PEP 518/517 compliant pyproject.toml
     * 
     * @param legacyConfig Legacy configuration data extracted from setup.py/setup.cfg
     * @return PEP 518/517 compliant PyProjectConfig
     */
    fun migrateLegacyToPEP518517(legacyConfig: Map<String, Any>): PyProjectConfig {
        return pepSupport.migrateToPEP518517(legacyConfig)
    }
    
}

/**
 * Exception thrown when pyproject.toml parsing or writing fails
 */
class PyProjectParseException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause) 