package org.thisisthepy.python.multiplatform.packpack.config

import com.akuleshov7.ktoml.Toml
import com.akuleshov7.ktoml.TomlIndentation
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
 */
class PyprojectParser {
    private val toml =
        Toml(
            inputConfig =
                TomlInputConfig(
                    ignoreUnknownNames = true, // Allow unknown fields for forward compatibility
                    allowEmptyValues = true, // Allow empty values in TOML
                    allowNullValues = true, // Allow null values for nullable fields
                    allowEscapedQuotesInLiteralStrings = true,
                    allowEmptyToml = false, // Require non-empty TOML files
                    ignoreDefaultValues = false, // Include default values in parsing
                ),
            outputConfig =
                TomlOutputConfig(
                    // Conventional TOML format typically does not indent key/value lines.
                    indentation = TomlIndentation.NONE,
                ),
        )

    private val validator = ConfigValidator()

    /**
     * Parse pyproject.toml from a file path
     *
     * @param filePath Path to the pyproject.toml file
     * @param applyDefaults Whether to apply default values to the configuration
     * @param validateConfig Whether to validate the configuration
     * @return Parsed PyprojectConfig object
     * @throws PyProjectParseException if parsing fails
     */
    fun parseFromFile(
        filePath: Path,
        applyDefaults: Boolean = true,
        validateConfig: Boolean = true,
    ): PyprojectConfig {
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
     * @return Parsed PyprojectConfig object
     * @throws PyProjectParseException if parsing fails
     */
    fun parseFromFile(
        file: File,
        applyDefaults: Boolean = true,
        validateConfig: Boolean = true,
    ): PyprojectConfig = parseFromFile(file.toPath(), applyDefaults, validateConfig)

    /**
     * Parse pyproject.toml from a string
     *
     * @param content TOML content as string
     * @param applyDefaults Whether to apply default values to the configuration
     * @param validateConfig Whether to validate the configuration
     * @return Parsed PyprojectConfig object
     * @throws PyProjectParseException if parsing fails
     */
    fun parseFromString(
        content: String,
        applyDefaults: Boolean = true,
        validateConfig: Boolean = true,
    ): PyprojectConfig =
        try {
            var config = toml.decodeFromString<PyprojectConfig>(content)

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

    /**
     * Write PyprojectConfig to a file
     *
     * @param config Configuration object to write
     * @param filePath Path where to write the pyproject.toml file
     * @throws PyProjectParseException if writing fails
     */
    fun writeToFile(
        config: PyprojectConfig,
        filePath: Path,
    ) {
        try {
            val content = toml.encodeToString(normalizeForOutput(config))
            filePath.writeText(content)
        } catch (e: TomlEncodingException) {
            throw PyProjectParseException("Failed to encode TOML content: ${e.message}", e)
        } catch (e: Exception) {
            throw PyProjectParseException("Failed to write pyproject.toml file: $filePath", e)
        }
    }

    /**
     * Write PyprojectConfig to a File object
     *
     * @param config Configuration object to write
     * @param file File object where to write
     * @throws PyProjectParseException if writing fails
     */
    fun writeToFile(
        config: PyprojectConfig,
        file: File,
    ) {
        writeToFile(config, file.toPath())
    }

    /**
     * Convert PyprojectConfig to TOML string
     *
     * @param config Configuration object to convert
     * @return TOML content as string
     * @throws PyProjectParseException if encoding fails
     */
    fun writeToString(config: PyprojectConfig): String =
        try {
            toml.encodeToString(normalizeForOutput(config))
        } catch (e: TomlEncodingException) {
            throw PyProjectParseException("Failed to encode TOML content: ${e.message}", e)
        } catch (e: Exception) {
            throw PyProjectParseException("Unexpected error during TOML encoding: ${e.message}", e)
        }

    private fun normalizeForOutput(config: PyprojectConfig): PyprojectConfig {
        fun <V> Map<String, V>.sortedKeys(): Map<String, V> = toSortedMap()

        fun normalizeProject(project: ProjectConfig): ProjectConfig =
            project.copy(
                urls = project.urls?.sortedKeys(),
                optionalDependencies = project.optionalDependencies?.sortedKeys(),
            )

        fun normalizeTarget(target: TargetConfig): TargetConfig =
            target.copy(
                optionalDependencies = target.optionalDependencies?.sortedKeys(),
                environment = target.environment?.sortedKeys(),
            )

        fun normalizePyPackPack(tool: PyPackPackConfig): PyPackPackConfig =
            tool.copy(
                targets = tool.targets?.toSortedMap()?.mapValues { (_, v) -> normalizeTarget(v) },
                deploy = tool.deploy?.copy(credentials = tool.deploy.credentials?.sortedKeys()),
            )

        fun normalizeTool(tool: ToolConfig): ToolConfig =
            tool.copy(
                pypackpack = tool.pypackpack?.let(::normalizePyPackPack),
                setuptools = tool.setuptools?.sortedKeys(),
                wheel = tool.wheel?.sortedKeys(),
                poetry = tool.poetry?.sortedKeys(),
                hatch = tool.hatch?.sortedKeys(),
                pdm = tool.pdm?.sortedKeys(),
            )

        return config.copy(
            project = config.project?.let(::normalizeProject),
            tool = config.tool?.let(::normalizeTool),
        )
    }

    /**
     * Validate a PyprojectConfig object
     *
     * @param config Configuration to validate
     * @return ValidationResult containing validation status and any errors
     */
    fun validate(config: PyprojectConfig): ValidationResult = validator.validate(config)

    /**
     * Validate and apply defaults to a PyprojectConfig
     *
     * @param config Configuration to validate and enhance
     * @return Enhanced configuration with defaults applied
     * @throws PyProjectParseException if validation fails
     */
    fun validateAndApplyDefaults(config: PyprojectConfig): PyprojectConfig =
        try {
            validator.validateAndApplyDefaults(config)
        } catch (e: ConfigValidationException) {
            throw PyProjectParseException("Configuration validation failed: ${e.message}", e)
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
     * Create a default PyprojectConfig for a new project
     *
     * @param projectName Name of the project
     * @param pythonVersion Python version requirement (optional)
     * @return Default configuration
     */
    fun createDefault(
        projectName: String,
        pythonVersion: String? = null,
    ): PyprojectConfig =
        PyprojectConfig(
            project =
                ProjectConfig(
                    name = projectName,
                    version = "0.1.0",
                    description = "A Python project built with PyPackPack",
                    requiresPython = pythonVersion ?: ">=3.13",
                    dependencies = emptyList(),
                ),
            // buildSystem = BuildSystemConfig(
            //     requires = listOf("pypackpack"),
            //     buildBackend = "pypackpack.build"
            // ),
            tool =
                ToolConfig(
                    pypackpack =
                        PyPackPackConfig(
                            version = "1.0",
                            build = BuildConfig(),
                            bundle = BundleConfig(),
                            dependencies = DependencyConfig(),
                        ),
                ),
        )
}

/**
 * Exception thrown when pyproject.toml parsing or writing fails
 */
class PyProjectParseException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
