package org.thisisthepy.python.multiplatform.packpack.config

/**
 * Comprehensive validator for PyProject configurations
 * Provides enhanced validation rules and default value handling
 */
class ConfigValidator {
    
    /**
     * Validate and apply defaults to a PyProjectConfig
     * 
     * @param config Configuration to validate and enhance
     * @return Enhanced configuration with defaults applied
     * @throws ConfigValidationException if validation fails
     */
    fun validateAndApplyDefaults(config: PyProjectConfig): PyProjectConfig {
        val validationResult = validate(config)
        if (!validationResult.isValid) {
            throw ConfigValidationException(validationResult.getErrorMessage())
        }
        
        return applyDefaults(config)
    }
    
    /**
     * Comprehensive validation of PyProjectConfig
     * 
     * @param config Configuration to validate
     * @return ValidationResult with detailed error information
     */
    fun validate(config: PyProjectConfig): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        
        // Validate project section
        validateProjectSection(config.project, errors, warnings)
        
        // Validate build-system section
        validateBuildSystemSection(config.buildSystem, errors, warnings)
        
        // Validate tool section
        validateToolSection(config.tool, errors, warnings)
        
        return ValidationResult(
            isValid = errors.isEmpty(),
            errors = errors,
            warnings = warnings
        )
    }
    
    /**
     * Apply default values to configuration
     * 
     * @param config Original configuration
     * @return Configuration with defaults applied
     */
    fun applyDefaults(config: PyProjectConfig): PyProjectConfig {
        return config.copy(
            project = applyProjectDefaults(config.project),
            buildSystem = applyBuildSystemDefaults(config.buildSystem),
            tool = applyToolDefaults(config.tool)
        )
    }
    
    private fun validateProjectSection(project: ProjectConfig?, errors: MutableList<String>, warnings: MutableList<String>) {
        if (project == null) {
            errors.add("Missing required [project] section")
            return
        }
        
        // Required fields
        if (project.name.isBlank()) {
            errors.add("Project name is required and cannot be empty")
        } else if (!isValidProjectName(project.name)) {
            errors.add("Invalid project name '${project.name}'. Must contain only letters, numbers, hyphens, and underscores")
        }
        
        // Version validation
        project.version?.let { version ->
            if (!isValidPEP440Version(version)) {
                errors.add("Invalid version format '$version'. Must follow PEP 440 specification")
            }
        } ?: warnings.add("No version specified. Consider adding a version field")
        
        // Python version requirement validation
        project.requiresPython?.let { requiresPython ->
            if (!isValidPythonVersionSpec(requiresPython)) {
                errors.add("Invalid requires-python format '$requiresPython'. Must follow PEP 440 version specifiers")
            }
        }
        
        // Dependencies validation
        project.dependencies?.forEach { dep ->
            if (!isValidDependencySpec(dep)) {
                errors.add("Invalid dependency specification: '$dep'")
            }
        }
        
        // Optional dependencies validation
        project.optionalDependencies?.forEach { (group, deps) ->
            if (group.isBlank()) {
                errors.add("Optional dependency group name cannot be empty")
            }
            deps.forEach { dep ->
                if (!isValidDependencySpec(dep)) {
                    errors.add("Invalid optional dependency specification in group '$group': '$dep'")
                }
            }
        }
        
        // Authors validation
        project.authors?.forEach { author ->
            if ((author.name?.isBlank() != false) && (author.email?.isBlank() != false)) {
                errors.add("Author must have either name or email specified")
            }
            author.email?.let { email ->
                if (!isValidEmail(email)) {
                    errors.add("Invalid email format: '$email'")
                }
            }
        }
        
        // Maintainers validation (same as authors)
        project.maintainers?.forEach { maintainer ->
            if ((maintainer.name?.isBlank() != false) && (maintainer.email?.isBlank() != false)) {
                errors.add("Maintainer must have either name or email specified")
            }
            maintainer.email?.let { email ->
                if (!isValidEmail(email)) {
                    errors.add("Invalid email format: '$email'")
                }
            }
        }
        
        // URLs validation
        project.urls?.forEach { (name, url) ->
            if (name.isBlank()) {
                errors.add("URL name cannot be empty")
            }
            if (!isValidUrl(url)) {
                errors.add("Invalid URL format for '$name': '$url'")
            }
        }
        
        // Classifiers validation
        project.classifiers?.forEach { classifier ->
            if (!isValidClassifier(classifier)) {
                warnings.add("Potentially invalid classifier: '$classifier'")
            }
        }
    }
    
    private fun validateBuildSystemSection(buildSystem: BuildSystemConfig?, errors: MutableList<String>, warnings: MutableList<String>) {
        if (buildSystem == null) {
            warnings.add("No [build-system] section found. Using default build system")
            return
        }
        
        if (buildSystem.requires.isEmpty()) {
            errors.add("build-system.requires cannot be empty")
        } else {
            buildSystem.requires.forEach { requirement ->
                if (!isValidDependencySpec(requirement)) {
                    errors.add("Invalid build requirement: '$requirement'")
                }
            }
        }
        
        if (buildSystem.buildBackend?.isBlank() != false) {
            warnings.add("No build-backend specified. Using default")
        }
    }
    
    private fun validateToolSection(tool: ToolConfig?, errors: MutableList<String>, warnings: MutableList<String>) {
        tool?.pypackpack?.let { pypackpack ->
            validatePyPackPackConfig(pypackpack, errors, warnings)
        }
    }
    
    private fun validatePyPackPackConfig(config: PyPackPackConfig, errors: MutableList<String>, warnings: MutableList<String>) {
        // Validate version
        if (!isValidVersion(config.version)) {
            errors.add("Invalid PyPackPack configuration version: '${config.version}'")
        }
        
        // Validate targets
        config.targets?.forEach { (name, target) ->
            if (name.isBlank()) {
                errors.add("Target name cannot be empty")
            }
            if (target.platform.isBlank()) {
                errors.add("Target '$name' must specify a platform")
            } else if (!isValidPlatform(target.platform)) {
                errors.add("Invalid platform for target '$name': '${target.platform}'")
            }
            
            target.architecture?.let { arch ->
                if (!isValidArchitecture(arch)) {
                    errors.add("Invalid architecture for target '$name': '$arch'")
                }
            }
        }
        
        // Validate build configuration
        config.build?.let { build ->
            // Build level validation is handled by enum type safety
            
            build.compilation?.let { compilation ->
                // Compilation backend validation is handled by enum type safety
                
                compilation.flags?.forEach { flag ->
                    if (flag.isBlank()) {
                        errors.add("Compilation flags cannot be empty")
                    }
                }
            }
        }
        
        // Validate bundle configuration
        config.bundle?.let { bundle ->
            // Bundle type validation is handled by enum type safety
        }
    }
    
    private fun applyProjectDefaults(project: ProjectConfig?): ProjectConfig {
        return project?.copy(
            version = project.version ?: "0.1.0",
            description = project.description ?: "A Python project built with PyPackPack",
            requiresPython = project.requiresPython ?: ">=3.13",
            dependencies = project.dependencies ?: emptyList(),
            optionalDependencies = project.optionalDependencies ?: emptyMap(),
            authors = project.authors ?: emptyList(),
            maintainers = project.maintainers ?: emptyList(),
            urls = project.urls ?: emptyMap(),
            classifiers = project.classifiers ?: emptyList(),
            keywords = project.keywords ?: emptyList()
        ) ?: ProjectConfig(
            name = "unnamed-project",
            version = "0.1.0",
            description = "A Python project built with PyPackPack",
            requiresPython = ">=3.13",
            dependencies = emptyList()
        )
    }
    
    private fun applyBuildSystemDefaults(buildSystem: BuildSystemConfig?): BuildSystemConfig {
        return buildSystem ?: BuildSystemConfig(
            requires = listOf("pypackpack"),
            buildBackend = "pypackpack.build"
        )
    }
    
    private fun applyToolDefaults(tool: ToolConfig?): ToolConfig {
        val pypackpack = tool?.pypackpack?.copy(
            version = tool.pypackpack.version.takeIf { it.isNotBlank() } ?: "1.0",
            build = tool.pypackpack.build ?: BuildConfig(),
            bundle = tool.pypackpack.bundle ?: BundleConfig(),
            dependencies = tool.pypackpack.dependencies ?: DependencyConfig()
        ) ?: PyPackPackConfig(
            version = "1.0",
            build = BuildConfig(),
            bundle = BundleConfig(),
            dependencies = DependencyConfig()
        )
        
        return tool?.copy(pypackpack = pypackpack) ?: ToolConfig(pypackpack = pypackpack)
    }
    
    // Validation helper methods
    private fun isValidProjectName(name: String): Boolean {
        val nameRegex = Regex("""^[a-zA-Z0-9_-]+$""")
        return nameRegex.matches(name)
    }
    
    private fun isValidPEP440Version(version: String): Boolean {
        // Enhanced PEP 440 version validation
        val versionRegex = Regex(
            """^([1-9][0-9]*!)?(0|[1-9][0-9]*)(\.(0|[1-9][0-9]*))*((a|b|rc)(0|[1-9][0-9]*))?(\.post(0|[1-9][0-9]*))?(\.dev(0|[1-9][0-9]*))?$"""
        )
        return versionRegex.matches(version)
    }
    
    private fun isValidPythonVersionSpec(spec: String): Boolean {
        // Enhanced Python version specifier validation
        val specRegex = Regex(
            """^(>=|>|<=|<|==|!=|~=)?\s*\d+(\.\d+)*(\.\*)?(\s*,\s*(>=|>|<=|<|==|!=|~=)?\s*\d+(\.\d+)*(\.\*)?)*$"""
        )
        return specRegex.matches(spec.trim())
    }
    
    private fun isValidDependencySpec(spec: String): Boolean {
        // Basic dependency specification validation
        val depRegex = Regex("""^[a-zA-Z0-9_-]+(\[.*\])?\s*([><=!~]+.*)?$""")
        return depRegex.matches(spec.trim())
    }
    
    private fun isValidEmail(email: String): Boolean {
        val emailRegex = Regex("""^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$""")
        return emailRegex.matches(email)
    }
    
    private fun isValidUrl(url: String): Boolean {
        return try {
            java.net.URL(url)
            true
        } catch (e: Exception) {
            false
        }
    }
    
    private fun isValidClassifier(classifier: String): Boolean {
        // Basic classifier validation - could be enhanced with official classifier list
        return classifier.isNotBlank() && classifier.contains("::")
    }
    
    private fun isValidVersion(version: String): Boolean {
        val versionRegex = Regex("""^\d+(\.\d+)*$""")
        return versionRegex.matches(version)
    }
    
    private fun isValidPlatform(platform: String): Boolean {
        val validPlatforms = setOf("windows", "linux", "macos", "android", "ios", "web")
        return platform.lowercase() in validPlatforms
    }
    
    private fun isValidArchitecture(architecture: String): Boolean {
        val validArchitectures = setOf("x86", "x64", "x86_64", "arm", "arm64", "aarch64", "universal")
        return architecture.lowercase() in validArchitectures
    }
    

}

/**
 * Enhanced validation result with warnings
 */
data class ValidationResult(
    val isValid: Boolean,
    val errors: List<String>,
    val warnings: List<String> = emptyList()
) {
    fun getErrorMessage(): String {
        val result = StringBuilder()
        
        if (errors.isNotEmpty()) {
            result.append("Validation errors:\n")
            errors.forEach { result.append("- $it\n") }
        }
        
        if (warnings.isNotEmpty()) {
            if (result.isNotEmpty()) result.append("\n")
            result.append("Warnings:\n")
            warnings.forEach { result.append("- $it\n") }
        }
        
        if (result.isEmpty()) {
            result.append("Configuration is valid")
        }
        
        return result.toString().trim()
    }
}

/**
 * Exception thrown when configuration validation fails
 */
class ConfigValidationException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause) 