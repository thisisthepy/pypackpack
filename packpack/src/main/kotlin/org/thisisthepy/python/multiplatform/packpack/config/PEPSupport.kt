package org.thisisthepy.python.multiplatform.packpack.config

/**
 * PEP 518/517 support implementation for PyPackPack
 * 
 * PEP 518: Specifying Minimum Build System Requirements for Python Projects
 * PEP 517: A build-system independent format for source trees
 */
class PEPSupport {
    
    companion object {
        // Standard PEP 518 build-system fields
        const val BUILD_SYSTEM_REQUIRES = "requires"
        const val BUILD_SYSTEM_BUILD_BACKEND = "build-backend"
        const val BUILD_SYSTEM_BACKEND_PATH = "backend-path"
        
        // Common build backends
        val KNOWN_BUILD_BACKENDS = mapOf(
            "setuptools.build_meta" to "setuptools",
            "setuptools.build_meta:__legacy__" to "setuptools",
            "flit_core.buildapi" to "flit",
            "poetry.core.masonry.api" to "poetry",
            "hatchling.build" to "hatch",
            "pdm.backend" to "pdm",
            "pypackpack.build" to "pypackpack"
        )
        
        // Default build requirements for different backends
        val DEFAULT_BUILD_REQUIREMENTS = mapOf(
            "setuptools.build_meta" to listOf("setuptools>=61.0", "wheel"),
            "setuptools.build_meta:__legacy__" to listOf("setuptools>=40.8.0", "wheel"),
            "flit_core.buildapi" to listOf("flit_core >=3.2,<4"),
            "poetry.core.masonry.api" to listOf("poetry-core"),
            "hatchling.build" to listOf("hatchling"),
            "pdm.backend" to listOf("pdm-backend"),
            "pypackpack.build" to listOf("pypackpack")
        )
    }
    
    /**
     * Validate PEP 518 build-system configuration
     * 
     * @param buildSystem Build system configuration to validate
     * @return ValidationResult with PEP 518 specific validation
     */
    fun validatePEP518(buildSystem: BuildSystemConfig?): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        
        if (buildSystem == null) {
            warnings.add("No [build-system] section found. PEP 518 recommends specifying build requirements")
            return ValidationResult(true, errors, warnings)
        }
        
        // PEP 518: requires field is mandatory
        if (buildSystem.requires.isEmpty()) {
            errors.add("PEP 518: [build-system].requires is required and cannot be empty")
        } else {
            // Validate requirement specifications
            buildSystem.requires.forEach { requirement ->
                if (!isValidRequirementSpec(requirement)) {
                    errors.add("PEP 518: Invalid requirement specification '$requirement'")
                }
                
                // Warn about unpinned versions for better reproducibility
                if (!requirement.contains(">=") && !requirement.contains("==") && !requirement.contains("~=") && !requirement.contains(">") && !requirement.contains("<")) {
                    warnings.add("PEP 518: Consider pinning version for '$requirement' (e.g., '$requirement>=1.0') for better build reproducibility")
                }
            }
        }
        
        // PEP 517: build-backend is optional but recommended
        buildSystem.buildBackend?.let { backend ->
            if (!isValidBuildBackend(backend)) {
                warnings.add("PEP 517: Unknown build backend '$backend'. Consider using a standard backend")
            }
            
            // Check if requirements match the backend
            val recommendedRequirements = DEFAULT_BUILD_REQUIREMENTS[backend]
            if (recommendedRequirements != null) {
                val missingRequirements = recommendedRequirements.filter { req ->
                    !buildSystem.requires.any { it.startsWith(req.split(">=")[0].split("==")[0].trim()) }
                }
                if (missingRequirements.isNotEmpty()) {
                    warnings.add("PEP 518: Consider adding recommended requirements for '$backend': ${missingRequirements.joinToString(", ")}")
                }
            }
        } ?: warnings.add("PEP 517: No build-backend specified. Consider specifying a build backend")
        
        // PEP 517: backend-path validation
        buildSystem.backendPath?.let { backendPath ->
            if (backendPath.isEmpty()) {
                errors.add("PEP 517: backend-path cannot be empty if specified")
            }
        }
        
        return ValidationResult(errors.isEmpty(), errors, warnings)
    }
    
    /**
     * Validate PEP 517 build backend interface compliance
     * 
     * @param buildSystem Build system configuration
     * @return ValidationResult with PEP 517 specific validation
     */
    fun validatePEP517(buildSystem: BuildSystemConfig?): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        
        buildSystem?.buildBackend?.let { backend ->
            // Check if backend follows PEP 517 naming conventions
            if (!backend.contains(".")) {
                errors.add("PEP 517: build-backend should be a module path (e.g., 'package.module')")
            }
            
            // Validate known backends
            if (backend !in KNOWN_BUILD_BACKENDS) {
                warnings.add("PEP 517: Using custom build backend '$backend'. Ensure it implements PEP 517 interface")
            }
        }
        
        return ValidationResult(errors.isEmpty(), errors, warnings)
    }
    
    /**
     * Generate PEP 518/517 compliant build-system configuration
     * 
     * @param backend Build backend to use (optional)
     * @param additionalRequirements Additional build requirements
     * @return BuildSystemConfig that complies with PEP 518/517
     */
    fun generateCompliantBuildSystem(
        backend: String? = null,
        additionalRequirements: List<String> = emptyList()
    ): BuildSystemConfig {
        val selectedBackend = backend ?: "pypackpack.build"
        val baseRequirements = DEFAULT_BUILD_REQUIREMENTS[selectedBackend] ?: listOf("pypackpack")
        val allRequirements = (baseRequirements + additionalRequirements).distinct()
        
        return BuildSystemConfig(
            requires = allRequirements,
            buildBackend = selectedBackend,
            backendPath = null // Usually not needed for standard backends
        )
    }
    
    /**
     * Check if a configuration is PEP 518/517 compliant
     * 
     * @param config PyProject configuration to check
     * @return Compliance report with detailed information
     */
    fun checkCompliance(config: PyProjectConfig): ComplianceReport {
        val pep518Result = validatePEP518(config.buildSystem)
        val pep517Result = validatePEP517(config.buildSystem)
        
        val allErrors = pep518Result.errors + pep517Result.errors
        val allWarnings = pep518Result.warnings + pep517Result.warnings
        
        val complianceLevel = when {
            allErrors.isNotEmpty() -> ComplianceLevel.NON_COMPLIANT
            allWarnings.isNotEmpty() -> ComplianceLevel.PARTIALLY_COMPLIANT
            else -> ComplianceLevel.FULLY_COMPLIANT
        }
        
        return ComplianceReport(
            complianceLevel = complianceLevel,
            pep518Compliant = pep518Result.isValid,
            pep517Compliant = pep517Result.isValid,
            errors = allErrors,
            warnings = allWarnings,
            recommendations = generateRecommendations(config)
        )
    }
    
    /**
     * Migrate legacy setup.py/setup.cfg to PEP 518/517 compliant pyproject.toml
     * 
     * @param legacyConfig Legacy configuration data
     * @return PEP 518/517 compliant PyProjectConfig
     */
    fun migrateToPEP518517(legacyConfig: Map<String, Any>): PyProjectConfig {
        // Extract build requirements from legacy setup
        val buildRequires = extractBuildRequirements(legacyConfig)
        val buildBackend = determineBuildBackend(legacyConfig)
        
        return PyProjectConfig(
            project = extractProjectMetadata(legacyConfig),
            buildSystem = BuildSystemConfig(
                requires = buildRequires,
                buildBackend = buildBackend
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
    
    private fun isValidRequirementSpec(requirement: String): Boolean {
        // Basic PEP 508 requirement specification validation
        val requirementRegex = Regex(
            """^[a-zA-Z0-9]([a-zA-Z0-9._-]*[a-zA-Z0-9])?(\[[a-zA-Z0-9,._-]+\])?\s*([><=!~]+[a-zA-Z0-9._-]+(\s*,\s*[><=!~]+[a-zA-Z0-9._-]+)*)?(\s*;\s*.+)?$"""
        )
        return requirementRegex.matches(requirement.trim())
    }
    
    private fun isValidBuildBackend(backend: String): Boolean {
        return backend.isNotBlank() && 
               (backend in KNOWN_BUILD_BACKENDS || backend.contains("."))
    }
    
    private fun generateRecommendations(config: PyProjectConfig): List<String> {
        val recommendations = mutableListOf<String>()
        
        config.buildSystem?.let { buildSystem ->
            // Recommend specific improvements
            if (buildSystem.buildBackend == null) {
                recommendations.add("Add a build-backend field to specify the build system")
            }
            
            if (buildSystem.requires.size == 1 && buildSystem.requires[0] == "pypackpack") {
                recommendations.add("Consider pinning PyPackPack version (e.g., 'pypackpack>=1.0')")
            }
            
            // Check for modern practices
            if (buildSystem.buildBackend?.startsWith("setuptools.build_meta:__legacy__") == true) {
                recommendations.add("Consider upgrading to modern setuptools.build_meta backend")
            }
        } ?: recommendations.add("Add a [build-system] section for PEP 518 compliance")
        
        return recommendations
    }
    
    private fun extractBuildRequirements(legacyConfig: Map<String, Any>): List<String> {
        // Extract from setup_requires, build_requires, or use defaults
        val setupRequires = legacyConfig["setup_requires"] as? List<String> ?: emptyList()
        val buildRequires = legacyConfig["build_requires"] as? List<String> ?: emptyList()
        
        return when {
            buildRequires.isNotEmpty() -> buildRequires
            setupRequires.isNotEmpty() -> setupRequires
            else -> listOf("pypackpack")
        }
    }
    
    private fun determineBuildBackend(legacyConfig: Map<String, Any>): String {
        // Determine appropriate build backend based on legacy configuration
        return when {
            legacyConfig.containsKey("pypackpack") -> "pypackpack.build"
            legacyConfig.containsKey("poetry") -> "poetry.core.masonry.api"
            legacyConfig.containsKey("flit") -> "flit_core.buildapi"
            else -> "setuptools.build_meta"
        }
    }
    
    private fun extractProjectMetadata(legacyConfig: Map<String, Any>): ProjectConfig {
        return ProjectConfig(
            name = legacyConfig["name"] as? String ?: "unknown-project",
            version = legacyConfig["version"] as? String ?: "0.1.0",
            description = legacyConfig["description"] as? String,
            requiresPython = legacyConfig["python_requires"] as? String ?: ">=3.8",
            dependencies = legacyConfig["install_requires"] as? List<String> ?: emptyList()
        )
    }
}

/**
 * PEP 518/517 compliance levels
 */
enum class ComplianceLevel {
    FULLY_COMPLIANT,
    PARTIALLY_COMPLIANT,
    NON_COMPLIANT
}

/**
 * Detailed compliance report for PEP 518/517
 */
data class ComplianceReport(
    val complianceLevel: ComplianceLevel,
    val pep518Compliant: Boolean,
    val pep517Compliant: Boolean,
    val errors: List<String>,
    val warnings: List<String>,
    val recommendations: List<String>
) {
    fun getSummary(): String {
        val summary = StringBuilder()
        
        summary.append("PEP 518/517 Compliance Report\n")
        summary.append("============================\n")
        summary.append("Overall Compliance: $complianceLevel\n")
        summary.append("PEP 518 (Build Requirements): ${if (pep518Compliant) "✓" else "✗"}\n")
        summary.append("PEP 517 (Build Backend): ${if (pep517Compliant) "✓" else "✗"}\n\n")
        
        if (errors.isNotEmpty()) {
            summary.append("Errors:\n")
            errors.forEach { summary.append("- $it\n") }
            summary.append("\n")
        }
        
        if (warnings.isNotEmpty()) {
            summary.append("Warnings:\n")
            warnings.forEach { summary.append("- $it\n") }
            summary.append("\n")
        }
        
        if (recommendations.isNotEmpty()) {
            summary.append("Recommendations:\n")
            recommendations.forEach { summary.append("- $it\n") }
        }
        
        return summary.toString().trim()
    }
} 