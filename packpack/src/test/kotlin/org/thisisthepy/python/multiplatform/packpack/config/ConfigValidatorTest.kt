package org.thisisthepy.python.multiplatform.packpack.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class ConfigValidatorTest {
    
    private val validator = ConfigValidator()
    
    @Test
    fun `should validate valid configuration`() {
        val config = PyProjectConfig(
            project = ProjectConfig(
                name = "test-project",
                version = "1.0.0",
                description = "A test project",
                requiresPython = ">=3.13",
                dependencies = listOf("requests>=2.25.0")
            ),
            buildSystem = BuildSystemConfig(
                requires = listOf("pypackpack"),
                buildBackend = "pypackpack.build"
            ),
            tool = ToolConfig(
                pypackpack = PyPackPackConfig(
                    version = "1.0",
                    targets = mapOf(
                        "linux" to TargetConfig(
                            platform = "linux",
                            architecture = "x64"
                        )
                    )
                )
            )
        )
        
        val result = validator.validate(config)
        assertTrue(result.isValid, "Configuration should be valid")
        assertTrue(result.errors.isEmpty(), "Should have no errors")
    }
    
    @Test
    fun `should detect missing project name`() {
        val config = PyProjectConfig(
            project = ProjectConfig(
                name = "",
                version = "1.0.0"
            )
        )
        
        val result = validator.validate(config)
        assertFalse(result.isValid, "Configuration should be invalid")
        assertTrue(result.errors.any { it.contains("Project name is required") })
    }
    
    @Test
    fun `should detect invalid project name`() {
        val config = PyProjectConfig(
            project = ProjectConfig(
                name = "invalid@name",
                version = "1.0.0"
            )
        )
        
        val result = validator.validate(config)
        assertFalse(result.isValid, "Configuration should be invalid")
        assertTrue(result.errors.any { it.contains("Invalid project name") })
    }
    
    @Test
    fun `should detect invalid version format`() {
        val config = PyProjectConfig(
            project = ProjectConfig(
                name = "test-project",
                version = "invalid-version"
            )
        )
        
        val result = validator.validate(config)
        assertFalse(result.isValid, "Configuration should be invalid")
        assertTrue(result.errors.any { it.contains("Invalid version format") })
    }
    
    @Test
    fun `should validate PEP 440 version formats`() {
        val validVersions = listOf(
            "1.0.0",
            "2.1.3",
            "1.0.0a1",
            "1.0.0b2",
            "1.0.0rc1",
            "1.0.0.post1",
            "1.0.0.dev1",
            "1.0.0a1.dev1"
        )
        
        validVersions.forEach { version ->
            val config = PyProjectConfig(
                project = ProjectConfig(
                    name = "test-project",
                    version = version
                )
            )
            
            val result = validator.validate(config)
            assertTrue(result.isValid, "Version $version should be valid")
        }
    }
    
    @Test
    fun `should detect invalid Python version spec`() {
        val config = PyProjectConfig(
            project = ProjectConfig(
                name = "test-project",
                version = "1.0.0",
                requiresPython = "invalid-spec"
            )
        )
        
        val result = validator.validate(config)
        assertFalse(result.isValid, "Configuration should be invalid")
        assertTrue(result.errors.any { it.contains("Invalid requires-python format") })
    }
    
    @Test
    fun `should validate Python version specs`() {
        val validSpecs = listOf(
            ">=3.8",
            ">3.7",
            "==3.9",
            "!=3.6",
            "~=3.8.0",
            ">=3.8,<4.0"
        )
        
        validSpecs.forEach { spec ->
            val config = PyProjectConfig(
                project = ProjectConfig(
                    name = "test-project",
                    version = "1.0.0",
                    requiresPython = spec
                )
            )
            
            val result = validator.validate(config)
            assertTrue(result.isValid, "Python version spec '$spec' should be valid")
        }
    }
    
    @Test
    fun `should detect invalid email format`() {
        val config = PyProjectConfig(
            project = ProjectConfig(
                name = "test-project",
                version = "1.0.0",
                authors = listOf(
                    AuthorConfig(
                        name = "Test Author",
                        email = "invalid-email"
                    )
                )
            )
        )
        
        val result = validator.validate(config)
        assertFalse(result.isValid, "Configuration should be invalid")
        assertTrue(result.errors.any { it.contains("Invalid email format") })
    }
    
    @Test
    fun `should detect invalid URL format`() {
        val config = PyProjectConfig(
            project = ProjectConfig(
                name = "test-project",
                version = "1.0.0",
                urls = mapOf(
                    "homepage" to "not-a-url"
                )
            )
        )
        
        val result = validator.validate(config)
        assertFalse(result.isValid, "Configuration should be invalid")
        assertTrue(result.errors.any { it.contains("Invalid URL format") })
    }
    
    @Test
    fun `should detect empty build system requires`() {
        val config = PyProjectConfig(
            project = ProjectConfig(
                name = "test-project",
                version = "1.0.0"
            ),
            buildSystem = BuildSystemConfig(
                requires = emptyList(),
                buildBackend = "pypackpack.build"
            )
        )
        
        val result = validator.validate(config)
        assertFalse(result.isValid, "Configuration should be invalid")
        assertTrue(result.errors.any { it.contains("build-system.requires cannot be empty") })
    }
    
    @Test
    fun `should detect invalid platform`() {
        val config = PyProjectConfig(
            project = ProjectConfig(
                name = "test-project",
                version = "1.0.0"
            ),
            tool = ToolConfig(
                pypackpack = PyPackPackConfig(
                    version = "1.0",
                    targets = mapOf(
                        "invalid" to TargetConfig(
                            platform = "invalid-platform"
                        )
                    )
                )
            )
        )
        
        val result = validator.validate(config)
        assertFalse(result.isValid, "Configuration should be invalid")
        assertTrue(result.errors.any { it.contains("Invalid platform") })
    }
    
    @Test
    fun `should detect invalid architecture`() {
        val config = PyProjectConfig(
            project = ProjectConfig(
                name = "test-project",
                version = "1.0.0"
            ),
            tool = ToolConfig(
                pypackpack = PyPackPackConfig(
                    version = "1.0",
                    targets = mapOf(
                        "linux" to TargetConfig(
                            platform = "linux",
                            architecture = "invalid-arch"
                        )
                    )
                )
            )
        )
        
        val result = validator.validate(config)
        assertFalse(result.isValid, "Configuration should be invalid")
        assertTrue(result.errors.any { it.contains("Invalid architecture") })
    }
    
    @Test
    fun `should apply defaults to minimal configuration`() {
        val config = PyProjectConfig(
            project = ProjectConfig(
                name = "test-project"
            )
        )
        
        val enhanced = validator.applyDefaults(config)
        
        assertEquals("0.1.0", enhanced.project?.version)
        assertEquals("A Python project built with PyPackPack", enhanced.project?.description)
        assertEquals(">=3.13", enhanced.project?.requiresPython)
        assertEquals(listOf("pypackpack"), enhanced.buildSystem?.requires)
        assertEquals("pypackpack.build", enhanced.buildSystem?.buildBackend)
        assertEquals("1.0", enhanced.tool?.pypackpack?.version)
    }
    
    @Test
    fun `should preserve existing values when applying defaults`() {
        val config = PyProjectConfig(
            project = ProjectConfig(
                name = "test-project",
                version = "2.0.0",
                description = "Custom description"
            )
        )
        
        val enhanced = validator.applyDefaults(config)
        
        assertEquals("2.0.0", enhanced.project?.version)
        assertEquals("Custom description", enhanced.project?.description)
        assertEquals(">=3.13", enhanced.project?.requiresPython) // Default applied
    }
    
    @Test
    fun `should validate and apply defaults successfully`() {
        val config = PyProjectConfig(
            project = ProjectConfig(
                name = "test-project",
                version = "1.0.0"
            )
        )
        
        val enhanced = validator.validateAndApplyDefaults(config)
        
        assertEquals("test-project", enhanced.project?.name)
        assertEquals("1.0.0", enhanced.project?.version)
        assertEquals("A Python project built with PyPackPack", enhanced.project?.description)
        assertEquals(">=3.13", enhanced.project?.requiresPython)
    }
    
    @Test
    fun `should throw exception for invalid configuration in validateAndApplyDefaults`() {
        val config = PyProjectConfig(
            project = ProjectConfig(
                name = "", // Invalid empty name
                version = "1.0.0"
            )
        )
        
        assertFailsWith<ConfigValidationException> {
            validator.validateAndApplyDefaults(config)
        }
    }
    
    @Test
    fun `should generate warnings for missing optional fields`() {
        val config = PyProjectConfig(
            project = ProjectConfig(
                name = "test-project"
                // No version specified
            )
        )
        
        val result = validator.validate(config)
        assertTrue(result.isValid, "Configuration should be valid despite warnings")
        assertTrue(result.warnings.any { it.contains("No version specified") })
    }
    
    @Test
    fun `should validate build levels`() {
        val validLevels = listOf(BuildLevel.INSTANT, BuildLevel.BYTECODE, BuildLevel.NATIVE, BuildLevel.MIXED)
        
        validLevels.forEach { level ->
            val config = PyProjectConfig(
                project = ProjectConfig(
                    name = "test-project",
                    version = "1.0.0"
                ),
                tool = ToolConfig(
                    pypackpack = PyPackPackConfig(
                        version = "1.0",
                        build = BuildConfig(
                            level = level
                        )
                    )
                )
            )
            
            val result = validator.validate(config)
            assertTrue(result.isValid, "Build level '$level' should be valid")
        }
    }
    
    @Test
    fun `should validate compilation backends`() {
        val validBackends = listOf(CompilationBackend.NUITKA, CompilationBackend.CYTHON, CompilationBackend.LPYTHON)
        
        validBackends.forEach { backend ->
            val config = PyProjectConfig(
                project = ProjectConfig(
                    name = "test-project",
                    version = "1.0.0"
                ),
                tool = ToolConfig(
                    pypackpack = PyPackPackConfig(
                        version = "1.0",
                        build = BuildConfig(
                            compilation = CompilationConfig(
                                backend = backend
                            )
                        )
                    )
                )
            )
            
            val result = validator.validate(config)
            assertTrue(result.isValid, "Compilation backend '$backend' should be valid")
        }
    }
    
    @Test
    fun `should validate bundle types`() {
        val validTypes = listOf(BundleType.SINGLE, BundleType.FAT, BundleType.BINARY, BundleType.PATCH)
        
        validTypes.forEach { type ->
            val config = PyProjectConfig(
                project = ProjectConfig(
                    name = "test-project",
                    version = "1.0.0"
                ),
                tool = ToolConfig(
                    pypackpack = PyPackPackConfig(
                        version = "1.0",
                        bundle = BundleConfig(
                            type = type
                        )
                    )
                )
            )
            
            val result = validator.validate(config)
            assertTrue(result.isValid, "Bundle type '$type' should be valid")
        }
    }
} 