package org.thisisthepy.python.multiplatform.packpack.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

class PyProjectParserTest {
    
    private lateinit var parser: PyProjectParser
    
    @TempDir
    lateinit var tempDir: Path
    
    @BeforeEach
    fun setUp() {
        parser = PyProjectParser()
    }
    
    @Test
    fun `should parse basic pyproject toml`() {
        val tomlContent = """
            [project]
            name = "test-project"
            version = "1.0.0"
            description = "A test project"
            
            [build-system]
            requires = ["pypackpack"]
            build-backend = "pypackpack.build"
            
            [tool.pypackpack]
            version = "1.0"
        """.trimIndent()
        
        val config = parser.parseFromString(tomlContent)
        
        assertNotNull(config.project)
        assertEquals("test-project", config.project?.name)
        assertEquals("1.0.0", config.project?.version)
        assertEquals("A test project", config.project?.description)
        
        assertNotNull(config.buildSystem)
        assertEquals(listOf("pypackpack"), config.buildSystem?.requires)
        assertEquals("pypackpack.build", config.buildSystem?.buildBackend)
        
        assertNotNull(config.tool?.pypackpack)
        assertEquals("1.0", config.tool?.pypackpack?.version)
    }
    
    @Test
    fun `should parse complex pyproject toml with all sections`() {
        val tomlContent = """
            [project]
            name = "complex-project"
            version = "2.1.0"
            description = "A complex test project"
            requires-python = ">=3.13"
            dependencies = ["numpy>=1.20.0", "pandas>=1.3.0"]
            
            [[project.authors]]
            name = "John Doe"
            email = "john@example.com"
            
            [project.license]
            text = "MIT"
            
            [build-system]
            requires = ["pypackpack", "setuptools"]
            build-backend = "pypackpack.build"
            
            [tool.pypackpack]
            version = "1.0"
            
            [tool.pypackpack.build]
            level = "native"
            type = "release"
            
            [tool.pypackpack.build.optimization]
            minification = true
            compression = true
            
            [tool.pypackpack.build.compilation]
            backend = "nuitka"
            flags = ["--standalone", "--onefile"]
            
            [tool.pypackpack.bundle]
            type = "binary"
            format = "wheel"
            
            [tool.pypackpack.targets.windows]
            platform = "windows"
            architecture = "x86_64"
            python-version = "3.13"
            
            [tool.pypackpack.targets.linux]
            platform = "linux"
            architecture = "x86_64"
            python-version = "3.13"
        """.trimIndent()
        
        val config = parser.parseFromString(tomlContent)
        
        // Verify project section
        assertNotNull(config.project)
        assertEquals("complex-project", config.project?.name)
        assertEquals("2.1.0", config.project?.version)
        assertEquals(">=3.13", config.project?.requiresPython)
        assertEquals(listOf("numpy>=1.20.0", "pandas>=1.3.0"), config.project?.dependencies)
        
        // Verify authors
        assertNotNull(config.project?.authors)
        assertEquals(1, config.project?.authors?.size)
        assertEquals("John Doe", config.project?.authors?.get(0)?.name)
        assertEquals("john@example.com", config.project?.authors?.get(0)?.email)
        
        // Verify license
        assertNotNull(config.project?.license)
        assertEquals("MIT", config.project?.license?.text)
        
        // Verify PyPackPack configuration
        val pypackpack = config.tool?.pypackpack
        assertNotNull(pypackpack)
        
        // Verify build configuration
        val build = pypackpack?.build
        assertNotNull(build)
        assertEquals(BuildLevel.NATIVE, build?.level)
        assertEquals(BuildType.RELEASE, build?.type)
        
        // Verify optimization
        val optimization = build?.optimization
        assertNotNull(optimization)
        assertTrue(optimization?.minification ?: false)
        assertTrue(optimization?.compression ?: false)
        
        // Verify compilation
        val compilation = build?.compilation
        assertNotNull(compilation)
        assertEquals(CompilationBackend.NUITKA, compilation?.backend)
        assertEquals(listOf("--standalone", "--onefile"), compilation?.flags)
        
        // Verify bundle configuration
        val bundle = pypackpack?.bundle
        assertNotNull(bundle)
        assertEquals(BundleType.BINARY, bundle?.type)
        assertEquals(BundleFormat.WHEEL, bundle?.format)
        
        // Verify targets
        val targets = pypackpack?.targets
        assertNotNull(targets)
        assertEquals(2, targets?.size)
        
        val windowsTarget = targets?.get("windows")
        assertNotNull(windowsTarget)
        assertEquals("windows", windowsTarget?.platform)
        assertEquals("x86_64", windowsTarget?.architecture)
        assertEquals("3.13", windowsTarget?.pythonVersion)
        
        val linuxTarget = targets?.get("linux")
        assertNotNull(linuxTarget)
        assertEquals("linux", linuxTarget?.platform)
        assertEquals("x86_64", linuxTarget?.architecture)
        assertEquals("3.13", linuxTarget?.pythonVersion)
    }
    
    @Test
    fun `should handle file parsing`() {
        val tomlFile = tempDir.resolve("pyproject.toml")
        val tomlContent = """
            [project]
            name = "file-test"
            version = "1.0.0"
            
            [build-system]
            requires = ["pypackpack"]
        """.trimIndent()
        
        tomlFile.writeText(tomlContent)
        
        val config = parser.parseFromFile(tomlFile)
        
        assertEquals("file-test", config.project?.name)
        assertEquals("1.0.0", config.project?.version)
        assertEquals(listOf("pypackpack"), config.buildSystem?.requires)
    }
    
    @Test
    fun `should throw exception for non-existent file`() {
        val nonExistentFile = tempDir.resolve("non-existent.toml")
        
        assertThrows(PyProjectParseException::class.java) {
            parser.parseFromFile(nonExistentFile)
        }
    }
    
    @Test
    fun `should throw exception for invalid TOML`() {
        val invalidToml = """
            [project
            name = "invalid"
        """.trimIndent()
        
        assertThrows(PyProjectParseException::class.java) {
            parser.parseFromString(invalidToml)
        }
    }
    
    @Test
    fun `should write configuration to string`() {
        val config = PyProjectConfig(
            project = ProjectConfig(
                name = "write-test",
                version = "1.0.0",
                description = "Test writing"
            ),
            buildSystem = BuildSystemConfig(
                requires = listOf("pypackpack"),
                buildBackend = "pypackpack.build"
            )
        )
        
        val tomlString = parser.writeToString(config)
        
        assertTrue(tomlString.contains("name = \"write-test\""))
        assertTrue(tomlString.contains("version = \"1.0.0\""))
        assertTrue(tomlString.contains("description = \"Test writing\""))
        assertTrue(tomlString.contains("requires = [ \"pypackpack\" ]"))
        assertTrue(tomlString.contains("build-backend = \"pypackpack.build\""))
    }
    
    @Test
    fun `should write configuration to file`() {
        val config = PyProjectConfig(
            project = ProjectConfig(
                name = "file-write-test",
                version = "2.0.0"
            ),
            buildSystem = BuildSystemConfig(
                requires = listOf("pypackpack")
            )
        )
        
        val outputFile = tempDir.resolve("output.toml")
        parser.writeToFile(config, outputFile)
        
        assertTrue(outputFile.toFile().exists())
        
        // Parse it back to verify
        val parsedConfig = parser.parseFromFile(outputFile)
        assertEquals("file-write-test", parsedConfig.project?.name)
        assertEquals("2.0.0", parsedConfig.project?.version)
    }
    
    @Test
    fun `should validate configuration correctly`() {
        val validConfig = PyProjectConfig(
            project = ProjectConfig(
                name = "valid-project",
                version = "1.0.0",
                requiresPython = ">=3.13"
            ),
            buildSystem = BuildSystemConfig(
                requires = listOf("pypackpack")
            )
        )
        
        val result = parser.validate(validConfig)
        assertTrue(result.isValid)
        assertTrue(result.errors.isEmpty())
    }
    
    @Test
    fun `should detect validation errors`() {
        val invalidConfig = PyProjectConfig(
            project = ProjectConfig(
                name = "",  // Empty name should be invalid
                version = "invalid-version",  // Invalid version format
                requiresPython = "invalid-python-spec"  // Invalid Python spec
            ),
            buildSystem = BuildSystemConfig(
                requires = emptyList()  // Empty requires should be invalid
            )
        )
        
        val result = parser.validate(invalidConfig)
        assertFalse(result.isValid)
        assertTrue(result.errors.isNotEmpty())
        
        val errorMessage = result.getErrorMessage()
        assertTrue(errorMessage.contains("Project name is required"))
        assertTrue(errorMessage.contains("Invalid version format"))
        assertTrue(errorMessage.contains("Invalid requires-python format"))
        assertTrue(errorMessage.contains("build-system.requires cannot be empty"))
    }
    
    @Test
    fun `should parse with validation and defaults`() {
        val tomlContent = """
            [project]
            name = "test-project"
            version = "1.0.0"
        """.trimIndent()
        
        val config = parser.parseFromString(tomlContent, applyDefaults = true, validateConfig = true)
        
        assertEquals("test-project", config.project?.name)
        assertEquals("1.0.0", config.project?.version)
        assertEquals("A Python project built with PyPackPack", config.project?.description) // Default applied
        assertEquals(">=3.13", config.project?.requiresPython) // Default applied
        assertEquals(listOf("pypackpack"), config.buildSystem?.requires) // Default applied
    }
    
    @Test
    fun `should parse without validation`() {
        val tomlContent = """
            [project]
            name = ""
            version = "invalid-version"
        """.trimIndent()
        
        // Should not throw exception when validation is disabled
        val config = parser.parseFromString(tomlContent, applyDefaults = false, validateConfig = false)
        
        assertEquals("", config.project?.name)
        assertEquals("invalid-version", config.project?.version)
    }
    
    @Test
    fun `should throw exception for invalid configuration when validation enabled`() {
        val tomlContent = """
            [project]
            name = ""
            version = "1.0.0"
        """.trimIndent()
        
        assertThrows(PyProjectParseException::class.java) {
            parser.parseFromString(tomlContent, applyDefaults = false, validateConfig = true)
        }
    }
    
    @Test
    fun `should validate and apply defaults using dedicated method`() {
        val config = PyProjectConfig(
            project = ProjectConfig(
                name = "test-project",
                version = "1.0.0"
            )
        )
        
        val enhanced = parser.validateAndApplyDefaults(config)
        
        assertEquals("test-project", enhanced.project?.name)
        assertEquals("1.0.0", enhanced.project?.version)
        assertEquals("A Python project built with PyPackPack", enhanced.project?.description)
        assertEquals(">=3.13", enhanced.project?.requiresPython)
        assertEquals(listOf("pypackpack"), enhanced.buildSystem?.requires)
        assertEquals("pypackpack.build", enhanced.buildSystem?.buildBackend)
    }
    
    @Test
    fun `should create default configuration`() {
        val defaultConfig = parser.createDefault("my-project", ">=3.13")
        
        assertEquals("my-project", defaultConfig.project?.name)
        assertEquals("0.1.0", defaultConfig.project?.version)
        assertEquals(">=3.13", defaultConfig.project?.requiresPython)
        assertEquals("A Python project built with PyPackPack", defaultConfig.project?.description)
        
        assertEquals(listOf("pypackpack"), defaultConfig.buildSystem?.requires)
        assertEquals("pypackpack.build", defaultConfig.buildSystem?.buildBackend)
        
        assertNotNull(defaultConfig.tool?.pypackpack)
        assertEquals("1.0", defaultConfig.tool?.pypackpack?.version)
    }
    
    @Test
    fun `should check if pyproject file is valid`() {
        // Create valid file
        val validFile = tempDir.resolve("valid.toml")
        validFile.writeText("""
            [project]
            name = "valid-project"
            version = "1.0.0"
            
            [build-system]
            requires = ["pypackpack"]
        """.trimIndent())
        
        assertTrue(parser.isValidPyProjectFile(validFile))
        
        // Create invalid file
        val invalidFile = tempDir.resolve("invalid.toml")
        invalidFile.writeText("""
            [project
            name = "invalid"
        """.trimIndent())
        
        assertFalse(parser.isValidPyProjectFile(invalidFile))
        
        // Non-existent file
        val nonExistentFile = tempDir.resolve("non-existent.toml")
        assertFalse(parser.isValidPyProjectFile(nonExistentFile))
    }
    
    @Test
    fun `should handle empty optional fields`() {
        val tomlContent = """
            [project]
            name = "minimal-project"
            
            [build-system]
            requires = ["pypackpack"]
        """.trimIndent()
        
        val config = parser.parseFromString(tomlContent, applyDefaults = false, validateConfig = true)
        
        assertEquals("minimal-project", config.project?.name)
        assertNull(config.project?.version)
        assertNull(config.project?.description)
        assertNull(config.project?.authors)
        assertNull(config.tool?.pypackpack)
    }
} 