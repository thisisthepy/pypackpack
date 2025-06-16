package org.thisisthepy.python.multiplatform.packpack.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.nio.file.Files
import java.nio.file.Path

class ConfigMigrationTest {
    
    private val migration = ConfigMigration()
    
    @Test
    fun `should migrate schema from 1_0 to 1_1`() {
        val config = PyProjectConfig(
            project = ProjectConfig(
                name = "test-project",
                version = "1.0.0"
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
        
        val migrated = migration.migrateSchema(config, "1.1")
        
        assertEquals("1.1", migrated.tool?.pypackpack?.version)
        assertEquals("test-project", migrated.project?.name)
        assertNotNull(migrated.tool?.pypackpack?.dependencies)
    }
    
    @Test
    fun `should not migrate when versions are the same`() {
        val config = PyProjectConfig(
            project = ProjectConfig(
                name = "test-project",
                version = "1.0.0"
            ),
            tool = ToolConfig(
                pypackpack = PyPackPackConfig(
                    version = "1.1",
                    build = BuildConfig(),
                    bundle = BundleConfig(),
                    dependencies = DependencyConfig()
                )
            )
        )
        
        val migrated = migration.migrateSchema(config, "1.1")
        
        // Should return the same object
        assertEquals(config, migrated)
    }
    
    @Test
    fun `should throw exception for unsupported migration`() {
        val config = PyProjectConfig(
            project = ProjectConfig(
                name = "test-project",
                version = "1.0.0"
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
        
        assertFailsWith<UnsupportedMigrationException> {
            migration.migrateSchema(config, "2.0")
        }
    }
    
    @Test
    fun `should convert from setup_py`() {
        val setupPyContent = """
            from setuptools import setup, find_packages
            
            setup(
                name="test-package",
                version="1.2.3",
                description="A test package",
                author="Test Author",
                author_email="test@example.com",
                python_requires=">=3.8",
                install_requires=[
                    "requests>=2.25.0",
                    "click>=7.0"
                ],
                classifiers=[
                    "Development Status :: 4 - Beta",
                    "Programming Language :: Python :: 3.8"
                ]
            )
        """.trimIndent()
        
        val tempDir = Files.createTempDirectory("test-migration")
        val setupPyFile = tempDir.resolve("setup.py")
        Files.write(setupPyFile, setupPyContent.toByteArray())
        
        try {
            val config = migration.convertFromSetupPy(setupPyFile, "test-package")
            
            assertEquals("test-package", config.project?.name)
            assertEquals("1.2.3", config.project?.version)
            assertEquals("A test package", config.project?.description)
            assertEquals("Test Author", config.project?.authors?.first()?.name)
            assertEquals("test@example.com", config.project?.authors?.first()?.email)
            assertEquals(">=3.8", config.project?.requiresPython)
            assertTrue(config.project?.dependencies?.contains("requests>=2.25.0") == true)
            assertTrue(config.project?.dependencies?.contains("click>=7.0") == true)
            assertEquals("pypackpack.build", config.buildSystem?.buildBackend)
            assertEquals(listOf("pypackpack"), config.buildSystem?.requires)
        } finally {
            Files.deleteIfExists(setupPyFile)
            Files.deleteIfExists(tempDir)
        }
    }
    
    @Test
    fun `should convert from setup_cfg`() {
        val setupCfgContent = """
            [metadata]
            name = test-package
            version = 2.0.0
            description = A test package from setup.cfg
            author = Config Author
            author_email = config@example.com
            python_requires = >=3.9
            classifiers =
                Development Status :: 5 - Production/Stable
                Programming Language :: Python :: 3.9
            
            [options]
            install_requires =
                numpy>=1.20.0
                pandas>=1.3.0
        """.trimIndent()
        
        val tempDir = Files.createTempDirectory("test-migration")
        val setupCfgFile = tempDir.resolve("setup.cfg")
        Files.write(setupCfgFile, setupCfgContent.toByteArray())
        
        try {
            val config = migration.convertFromSetupCfg(setupCfgFile, "test-package")
            
            assertEquals("test-package", config.project?.name)
            assertEquals("2.0.0", config.project?.version)
            assertEquals("A test package from setup.cfg", config.project?.description)
            assertEquals("Config Author", config.project?.authors?.first()?.name)
            assertEquals("config@example.com", config.project?.authors?.first()?.email)
            assertEquals(">=3.9", config.project?.requiresPython)
            assertTrue(config.project?.dependencies?.contains("numpy>=1.20.0") == true)
            assertTrue(config.project?.dependencies?.contains("pandas>=1.3.0") == true)
            assertEquals("pypackpack.build", config.buildSystem?.buildBackend)
        } finally {
            Files.deleteIfExists(setupCfgFile)
            Files.deleteIfExists(tempDir)
        }
    }
    
    @Test
    fun `should handle missing setup_py file`() {
        val nonExistentPath = Path.of("/non/existent/setup.py")
        
        assertFailsWith<MigrationException> {
            migration.convertFromSetupPy(nonExistentPath, "test-project")
        }
    }
    
    @Test
    fun `should handle missing setup_cfg file`() {
        val nonExistentPath = Path.of("/non/existent/setup.cfg")
        
        assertFailsWith<MigrationException> {
            migration.convertFromSetupCfg(nonExistentPath, "test-project")
        }
    }
    
    @Test
    fun `should auto-convert from setup_py when available`() {
        val setupPyContent = """
            from setuptools import setup
            setup(
                name="auto-test",
                version="1.0.0",
                description="Auto conversion test"
            )
        """.trimIndent()
        
        val tempDir = Files.createTempDirectory("test-auto-migration")
        val setupPyFile = tempDir.resolve("setup.py")
        Files.write(setupPyFile, setupPyContent.toByteArray())
        
        try {
            val config = migration.autoConvertLegacy(tempDir, "auto-test")
            
            assertNotNull(config)
            assertEquals("auto-test", config?.project?.name)
            assertEquals("1.0.0", config?.project?.version)
            assertEquals("Auto conversion test", config?.project?.description)
        } finally {
            Files.deleteIfExists(setupPyFile)
            Files.deleteIfExists(tempDir)
        }
    }
    
    @Test
    fun `should auto-convert from setup_cfg when setup_py not available`() {
        val setupCfgContent = """
            [metadata]
            name = cfg-auto-test
            version = 2.0.0
            description = Auto conversion from cfg
        """.trimIndent()
        
        val tempDir = Files.createTempDirectory("test-auto-migration")
        val setupCfgFile = tempDir.resolve("setup.cfg")
        Files.write(setupCfgFile, setupCfgContent.toByteArray())
        
        try {
            val config = migration.autoConvertLegacy(tempDir, "cfg-auto-test")
            
            assertNotNull(config)
            assertEquals("cfg-auto-test", config?.project?.name)
            assertEquals("2.0.0", config?.project?.version)
        } finally {
            Files.deleteIfExists(setupCfgFile)
            Files.deleteIfExists(tempDir)
        }
    }
    
    @Test
    fun `should return null when no legacy files found`() {
        val tempDir = Files.createTempDirectory("test-no-legacy")
        
        try {
            val config = migration.autoConvertLegacy(tempDir, "no-legacy")
            assertNull(config)
        } finally {
            Files.deleteIfExists(tempDir)
        }
    }
    
    @Test
    fun `should prefer setup_py over setup_cfg in auto-conversion`() {
        val setupPyContent = """
            from setuptools import setup
            setup(name="from-py", version="1.0.0")
        """.trimIndent()
        
        val setupCfgContent = """
            [metadata]
            name = from-cfg
            version = 2.0.0
        """.trimIndent()
        
        val tempDir = Files.createTempDirectory("test-preference")
        val setupPyFile = tempDir.resolve("setup.py")
        val setupCfgFile = tempDir.resolve("setup.cfg")
        Files.write(setupPyFile, setupPyContent.toByteArray())
        Files.write(setupCfgFile, setupCfgContent.toByteArray())
        
        try {
            val config = migration.autoConvertLegacy(tempDir, "test-preference")
            
            assertNotNull(config)
            assertEquals("from-py", config?.project?.name) // Should prefer setup.py
        } finally {
            Files.deleteIfExists(setupPyFile)
            Files.deleteIfExists(setupCfgFile)
            Files.deleteIfExists(tempDir)
        }
    }
    
    @Test
    fun `should detect when migration is needed`() {
        val config = PyProjectConfig(
            tool = ToolConfig(
                pypackpack = PyPackPackConfig(
                    version = "1.0",
                    build = BuildConfig(),
                    bundle = BundleConfig(),
                    dependencies = DependencyConfig()
                )
            )
        )
        
        assertTrue(migration.isMigrationNeeded(config, "1.1"))
        assertFalse(migration.isMigrationNeeded(config, "1.0"))
    }
    
    @Test
    fun `should handle missing version in migration check`() {
        val config = PyProjectConfig(
            project = ProjectConfig(name = "test", version = "1.0.0")
            // No tool section
        )
        
        assertTrue(migration.isMigrationNeeded(config, "1.1"))
        assertFalse(migration.isMigrationNeeded(config, "1.0"))
    }
    
    @Test
    fun `should return available migrations for version 1_0`() {
        val migrations = migration.getAvailableMigrations("1.0")
        assertEquals(listOf("1.1"), migrations)
    }
    
    @Test
    fun `should return empty list for unknown version`() {
        val migrations = migration.getAvailableMigrations("2.0")
        assertTrue(migrations.isEmpty())
    }
    
    @Test
    fun `should extract complex setup_py with multiple dependencies`() {
        val setupPyContent = """
            from setuptools import setup, find_packages
            
            setup(
                name="complex-package",
                version="3.0.0",
                description="A complex package with many dependencies",
                author="Complex Author",
                author_email="complex@example.com",
                python_requires=">=3.7",
                install_requires=[
                    "requests>=2.25.0,<3.0",
                    "click>=7.0",
                    "pydantic[email]>=1.8.0",
                    "fastapi>=0.65.0; python_version>='3.7'"
                ],
                classifiers=[
                    "Development Status :: 4 - Beta",
                    "Intended Audience :: Developers",
                    "License :: OSI Approved :: MIT License",
                    "Programming Language :: Python :: 3.7",
                    "Programming Language :: Python :: 3.8",
                    "Programming Language :: Python :: 3.9"
                ]
            )
        """.trimIndent()
        
        val tempDir = Files.createTempDirectory("test-complex")
        val setupPyFile = tempDir.resolve("setup.py")
        Files.write(setupPyFile, setupPyContent.toByteArray())
        
        try {
            val config = migration.convertFromSetupPy(setupPyFile, "complex-package")
            
            assertEquals("complex-package", config.project?.name)
            assertEquals("3.0.0", config.project?.version)
            assertEquals(">=3.7", config.project?.requiresPython)
            
            val dependencies = config.project?.dependencies ?: emptyList()
            assertTrue(dependencies.contains("requests>=2.25.0,<3.0"))
            assertTrue(dependencies.contains("click>=7.0"))
            assertTrue(dependencies.contains("pydantic[email]>=1.8.0"))
            assertTrue(dependencies.contains("fastapi>=0.65.0; python_version>='3.7'"))
            
            val classifiers = config.project?.classifiers ?: emptyList()
            assertTrue(classifiers.contains("Development Status :: 4 - Beta"))
            assertTrue(classifiers.contains("License :: OSI Approved :: MIT License"))
        } finally {
            Files.deleteIfExists(setupPyFile)
            Files.deleteIfExists(tempDir)
        }
    }
    
    @Test
    fun `should handle setup_py with minimal information`() {
        val setupPyContent = """
            from setuptools import setup
            setup(name="minimal")
        """.trimIndent()
        
        val tempDir = Files.createTempDirectory("test-minimal")
        val setupPyFile = tempDir.resolve("setup.py")
        Files.write(setupPyFile, setupPyContent.toByteArray())
        
        try {
            val config = migration.convertFromSetupPy(setupPyFile, "fallback-name")
            
            assertEquals("minimal", config.project?.name)
            assertEquals("0.1.0", config.project?.version) // Default version
            assertNull(config.project?.description)
            assertTrue(config.project?.dependencies?.isEmpty() == true)
        } finally {
            Files.deleteIfExists(setupPyFile)
            Files.deleteIfExists(tempDir)
        }
    }
    
    @Test
    fun `should use fallback name when setup_py has no name`() {
        val setupPyContent = """
            from setuptools import setup
            setup(version="1.0.0")
        """.trimIndent()
        
        val tempDir = Files.createTempDirectory("test-fallback")
        val setupPyFile = tempDir.resolve("setup.py")
        Files.write(setupPyFile, setupPyContent.toByteArray())
        
        try {
            val config = migration.convertFromSetupPy(setupPyFile, "fallback-name")
            
            assertEquals("fallback-name", config.project?.name)
            assertEquals("1.0.0", config.project?.version)
        } finally {
            Files.deleteIfExists(setupPyFile)
            Files.deleteIfExists(tempDir)
        }
    }
    

} 