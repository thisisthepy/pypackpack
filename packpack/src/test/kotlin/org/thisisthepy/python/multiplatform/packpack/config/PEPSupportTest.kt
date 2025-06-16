package org.thisisthepy.python.multiplatform.packpack.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PEPSupportTest {
    
    private val pepSupport = PEPSupport()
    
    @Test
    fun `should validate PEP 518 compliant build-system`() {
        val buildSystem = BuildSystemConfig(
            requires = listOf("pypackpack>=1.0", "wheel"),
            buildBackend = "pypackpack.build"
        )
        
        val result = pepSupport.validatePEP518(buildSystem)
        assertTrue(result.isValid)
        assertTrue(result.errors.isEmpty())
    }
    
    @Test
    fun `should detect PEP 518 violations`() {
        val buildSystem = BuildSystemConfig(
            requires = emptyList(), // PEP 518 violation: requires cannot be empty
            buildBackend = "pypackpack.build"
        )
        
        val result = pepSupport.validatePEP518(buildSystem)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("PEP 518") && it.contains("requires") })
    }
    
    @Test
    fun `should validate PEP 517 compliant build backend`() {
        val buildSystem = BuildSystemConfig(
            requires = listOf("pypackpack"),
            buildBackend = "pypackpack.build"
        )
        
        val result = pepSupport.validatePEP517(buildSystem)
        assertTrue(result.isValid)
        assertTrue(result.errors.isEmpty())
    }
    
    @Test
    fun `should detect PEP 517 violations`() {
        val buildSystem = BuildSystemConfig(
            requires = listOf("pypackpack"),
            buildBackend = "invalid_backend" // PEP 517 violation: should be module path
        )
        
        val result = pepSupport.validatePEP517(buildSystem)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("PEP 517") && it.contains("module path") })
    }
    
    @Test
    fun `should generate compliant build system with default backend`() {
        val buildSystem = pepSupport.generateCompliantBuildSystem()
        
        assertEquals("pypackpack.build", buildSystem.buildBackend)
        assertTrue(buildSystem.requires.contains("pypackpack"))
    }
    
    @Test
    fun `should generate compliant build system with custom backend`() {
        val buildSystem = pepSupport.generateCompliantBuildSystem(
            backend = "setuptools.build_meta",
            additionalRequirements = listOf("custom-package")
        )
        
        assertEquals("setuptools.build_meta", buildSystem.buildBackend)
        assertTrue(buildSystem.requires.contains("setuptools>=61.0"))
        assertTrue(buildSystem.requires.contains("wheel"))
        assertTrue(buildSystem.requires.contains("custom-package"))
    }
    
    @Test
    fun `should check overall compliance for fully compliant config`() {
        val config = PyProjectConfig(
            project = ProjectConfig(
                name = "test-project",
                version = "1.0.0"
            ),
            buildSystem = BuildSystemConfig(
                requires = listOf("pypackpack>=1.0"),
                buildBackend = "pypackpack.build"
            )
        )
        
        val report = pepSupport.checkCompliance(config)
        assertEquals(ComplianceLevel.FULLY_COMPLIANT, report.complianceLevel)
        assertTrue(report.pep518Compliant)
        assertTrue(report.pep517Compliant)
        assertTrue(report.errors.isEmpty())
    }
    
    @Test
    fun `should check overall compliance for partially compliant config`() {
        val config = PyProjectConfig(
            project = ProjectConfig(
                name = "test-project",
                version = "1.0.0"
            ),
            buildSystem = BuildSystemConfig(
                requires = listOf("pypackpack"), // Missing version pin - warning
                buildBackend = "pypackpack.build"
            )
        )
        
        val report = pepSupport.checkCompliance(config)
        assertEquals(ComplianceLevel.PARTIALLY_COMPLIANT, report.complianceLevel)
        assertTrue(report.pep518Compliant)
        assertTrue(report.pep517Compliant)
        assertTrue(report.warnings.isNotEmpty())
    }
    
    @Test
    fun `should check overall compliance for non-compliant config`() {
        val config = PyProjectConfig(
            project = ProjectConfig(
                name = "test-project",
                version = "1.0.0"
            ),
            buildSystem = BuildSystemConfig(
                requires = emptyList(), // PEP 518 violation
                buildBackend = "invalid_backend" // PEP 517 violation
            )
        )
        
        val report = pepSupport.checkCompliance(config)
        assertEquals(ComplianceLevel.NON_COMPLIANT, report.complianceLevel)
        assertFalse(report.pep518Compliant)
        assertFalse(report.pep517Compliant)
        assertTrue(report.errors.isNotEmpty())
    }
    
    @Test
    fun `should migrate legacy configuration to PEP 518-517`() {
        val legacyConfig = mapOf(
            "name" to "legacy-project",
            "version" to "2.0.0",
            "description" to "A legacy project",
            "python_requires" to ">=3.9",
            "install_requires" to listOf("requests", "click"),
            "setup_requires" to listOf("setuptools", "wheel")
        )
        
        val config = pepSupport.migrateToPEP518517(legacyConfig)
        
        assertEquals("legacy-project", config.project?.name)
        assertEquals("2.0.0", config.project?.version)
        assertEquals("A legacy project", config.project?.description)
        assertEquals(">=3.9", config.project?.requiresPython)
        assertEquals(listOf("requests", "click"), config.project?.dependencies)
        
        assertEquals(listOf("setuptools", "wheel"), config.buildSystem?.requires)
        assertEquals("setuptools.build_meta", config.buildSystem?.buildBackend)
    }
    
    @Test
    fun `should generate compliance report summary`() {
        val report = ComplianceReport(
            complianceLevel = ComplianceLevel.PARTIALLY_COMPLIANT,
            pep518Compliant = true,
            pep517Compliant = true,
            errors = emptyList(),
            warnings = listOf("Consider pinning version"),
            recommendations = listOf("Add version constraints")
        )
        
        val summary = report.getSummary()
        assertTrue(summary.contains("PEP 518/517 Compliance Report"))
        assertTrue(summary.contains("PARTIALLY_COMPLIANT"))
        assertTrue(summary.contains("✓"))
        assertTrue(summary.contains("Consider pinning version"))
        assertTrue(summary.contains("Add version constraints"))
    }
    
    @Test
    fun `should validate requirement specifications`() {
        val validRequirements = listOf(
            "pypackpack",
            "pypackpack>=1.0",
            "pypackpack==1.0.0",
            "pypackpack>=1.0,<2.0",
            "pypackpack[extra]>=1.0",
            "pypackpack>=1.0; python_version>='3.8'"
        )
        
        validRequirements.forEach { requirement ->
            val buildSystem = BuildSystemConfig(
                requires = listOf(requirement),
                buildBackend = "pypackpack.build"
            )
            
            val result = pepSupport.validatePEP518(buildSystem)
            assertTrue(result.isValid, "Requirement '$requirement' should be valid")
        }
    }
    
    @Test
    fun `should validate known build backends`() {
        val knownBackends = listOf(
            "setuptools.build_meta",
            "setuptools.build_meta:__legacy__",
            "flit_core.buildapi",
            "poetry.core.masonry.api",
            "hatchling.build",
            "pdm.backend",
            "pypackpack.build"
        )
        
        knownBackends.forEach { backend ->
            val buildSystem = BuildSystemConfig(
                requires = listOf("some-package"),
                buildBackend = backend
            )
            
            val result = pepSupport.validatePEP517(buildSystem)
            assertTrue(result.isValid, "Backend '$backend' should be valid")
        }
    }
    
    @Test
    fun `should handle missing build-system section`() {
        val result = pepSupport.validatePEP518(null)
        assertTrue(result.isValid) // Missing section is valid but generates warnings
        assertTrue(result.warnings.isNotEmpty())
        assertTrue(result.warnings.any { it.contains("PEP 518") })
    }
    
    @Test
    fun `should recommend requirements for known backends`() {
        val buildSystem = BuildSystemConfig(
            requires = listOf("setuptools"), // Missing wheel
            buildBackend = "setuptools.build_meta"
        )
        
        val result = pepSupport.validatePEP518(buildSystem)
        assertTrue(result.isValid)
        assertTrue(result.warnings.any { it.contains("wheel") })
    }
} 