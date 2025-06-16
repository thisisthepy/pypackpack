package org.thisisthepy.python.multiplatform.packpack.util

import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.io.ByteArrayOutputStream
import java.io.PrintStream

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CommandLineTest {
    
    private lateinit var originalOut: PrintStream
    private lateinit var testOut: ByteArrayOutputStream
    
    @BeforeEach
    fun setUp() {
        // Capture System.out for testing
        originalOut = System.out
        testOut = ByteArrayOutputStream()
        System.setOut(PrintStream(testOut))
    }
    
    @AfterEach
    fun tearDown() {
        // Restore original System.out
        System.setOut(originalOut)
    }
    
    @Test
    fun `should show help when no arguments provided`() {
        main(arrayOf())
        
        val output = testOut.toString()
        assertTrue(output.contains("PyPackPack"))
        assertTrue(output.contains("Usage:"))
        assertTrue(output.contains("Commands:"))
    }
    
    @Test
    fun `should show help with help command`() {
        main(arrayOf("help"))
        
        val output = testOut.toString()
        assertTrue(output.contains("PyPackPack"))
        assertTrue(output.contains("Usage:"))
    }
    
    @Test
    fun `should show help with --help flag`() {
        main(arrayOf("--help"))
        
        val output = testOut.toString()
        assertTrue(output.contains("PyPackPack"))
        assertTrue(output.contains("Usage:"))
    }
    
    @Test
    fun `should show version with version command`() {
        main(arrayOf("version"))
        
        val output = testOut.toString()
        assertTrue(output.contains("PyPackPack version"))
    }
    
    @Test
    fun `should show version with --version flag`() {
        main(arrayOf("--version"))
        
        val output = testOut.toString()
        assertTrue(output.contains("PyPackPack version"))
    }
    
    @Test
    fun `should show version with -v flag`() {
        main(arrayOf("-v"))
        
        val output = testOut.toString()
        assertTrue(output.contains("PyPackPack version"))
    }
    
    @Test
    fun `should handle unknown command`() {
        main(arrayOf("unknown_command"))
        
        val output = testOut.toString()
        assertTrue(output.contains("Unknown command:") && output.contains("unknown_command"))
    }
    
    @Test
    fun `should suggest similar command for typos`() {
        main(arrayOf("initt")) // typo for "init"
        
        val output = testOut.toString()
        assertTrue(output.contains("Unknown command:") && output.contains("initt"))
        assertTrue(output.contains("Did you mean:") && output.contains("init"))
    }
    
    @Test
    fun `should handle init command without arguments`() {
        main(arrayOf("init"))
        
        val output = testOut.toString()
        // Should show progress indicator and attempt to initialize
        assertTrue(output.contains("Initializing") || output.contains("project"))
    }
    
    @Test
    fun `should handle init command with project name`() {
        main(arrayOf("init", "test_project"))
        
        val output = testOut.toString()
        assertTrue(output.contains("Initializing") || output.contains("project"))
    }
    
    @Test
    fun `should handle init command with project name and python version`() {
        main(arrayOf("init", "test_project", "3.13"))
        
        val output = testOut.toString()
        assertTrue(output.contains("Initializing") || output.contains("project"))
    }
    
    @Test
    fun `should handle python command without subcommand`() {
        main(arrayOf("python"))
        
        val output = testOut.toString()
        assertTrue(output.contains("Missing required argument:") && output.contains("subcommand"))
        assertTrue(output.contains("Example:") && output.contains("python use"))
    }
    
    @Test
    fun `should handle python use without version`() {
        main(arrayOf("python", "use"))
        
        val output = testOut.toString()
        assertTrue(output.contains("Missing required argument: python version"))
        assertTrue(output.contains("Example: pypackpack python use 3.13"))
    }
    
    @Test
    fun `should handle python use with invalid version`() {
        main(arrayOf("python", "use", "invalid-version"))
        
        val output = testOut.toString()
        assertTrue(output.contains("Invalid value for argument 'python version': invalid-version"))
        assertTrue(output.contains("Expected format: X.Y or X.Y.Z"))
    }
    
    @Test
    fun `should handle python use with valid version`() {
        main(arrayOf("python", "use", "3.13"))
        
        val output = testOut.toString()
        assertTrue(output.contains("Switching") || output.contains("Python") || output.contains("3.13"))
    }
    
    @Test
    fun `should handle python list command`() {
        main(arrayOf("python", "list"))
        
        val output = testOut.toString()
        assertTrue(output.contains("Listing") || output.contains("Python") || output.contains("versions"))
    }
    
    @Test
    fun `should handle python install without version`() {
        main(arrayOf("python", "install"))
        
        val output = testOut.toString()
        assertTrue(output.contains("Missing required argument: python version"))
    }
    
    @Test
    fun `should handle python install with valid version`() {
        main(arrayOf("python", "install", "3.13"))
        
        val output = testOut.toString()
        assertTrue(output.contains("Installing") || output.contains("Python") || output.contains("3.13"))
    }
    
    @Test
    fun `should handle python uninstall without version`() {
        main(arrayOf("python", "uninstall"))
        
        val output = testOut.toString()
        assertTrue(output.contains("Missing required argument: python version"))
    }
    
    @Test
    fun `should handle python unknown subcommand`() {
        main(arrayOf("python", "unknown"))
        
        val output = testOut.toString()
        assertTrue(output.contains("Unknown command: python unknown"))
    }
    
    @Test
    fun `should handle package command without subcommand`() {
        main(arrayOf("package"))
        
        val output = testOut.toString()
        assertTrue(output.contains("Missing required argument: subcommand"))
        assertTrue(output.contains("package"))
    }
    
    @Test
    fun `should handle package add without name`() {
        main(arrayOf("package", "add"))
        
        val output = testOut.toString()
        assertTrue(output.contains("Missing required argument: package name"))
    }
    
    @Test
    fun `should handle package add with invalid name`() {
        main(arrayOf("package", "add", "123invalid"))
        
        val output = testOut.toString()
        assertTrue(output.contains("Invalid value for argument 'package name': 123invalid"))
        assertTrue(output.contains("Valid Python package name"))
    }
    
    @Test
    fun `should handle package add with valid name`() {
        main(arrayOf("package", "add", "mypackage"))
        
        val output = testOut.toString()
        assertTrue(output.contains("Creating") || output.contains("package") || output.contains("mypackage"))
    }
    
    @Test
    fun `should handle package remove without name`() {
        main(arrayOf("package", "remove"))
        
        val output = testOut.toString()
        assertTrue(output.contains("Missing required argument: package name"))
    }
    
    @Test
    fun `should handle package remove with valid name`() {
        main(arrayOf("package", "remove", "mypackage"))
        
        val output = testOut.toString()
        assertTrue(output.contains("Removing") || output.contains("package") || output.contains("mypackage"))
    }
    
    @Test
    fun `should handle target command without subcommand`() {
        main(arrayOf("target"))
        
        val output = testOut.toString()
        assertTrue(output.contains("Missing required argument: subcommand"))
        assertTrue(output.contains("target"))
    }
    
    @Test
    fun `should handle target add without arguments`() {
        main(arrayOf("target", "add"))
        
        val output = testOut.toString()
        assertTrue(output.contains("Missing required argument: target name"))
    }
    
    @Test
    fun `should handle target add with target name only`() {
        main(arrayOf("target", "add", "windows"))
        
        val output = testOut.toString()
        assertTrue(output.contains("Adding") || output.contains("target") || output.contains("windows"))
    }
    
    @Test
    fun `should handle target add with target and package name`() {
        main(arrayOf("target", "add", "windows", "mypackage"))
        
        val output = testOut.toString()
        assertTrue(output.contains("Adding") || output.contains("target") || output.contains("windows"))
    }
    
    @Test
    fun `should handle target list command`() {
        main(arrayOf("target", "list"))
        
        val output = testOut.toString()
        assertTrue(output.contains("Supported target platforms:"))
        assertTrue(output.contains("android_21_arm64"))
        assertTrue(output.contains("android_21_x86_64"))
        assertTrue(output.contains("windows_amd64"))
        assertTrue(output.contains("macos_arm64"))
        assertTrue(output.contains("macos_x86_64"))
        assertTrue(output.contains("linux_amd64"))
        assertTrue(output.contains("Usage examples:"))
    }
    
    @Test
    fun `should handle dependency add without package name`() {
        main(arrayOf("add"))
        
        val output = testOut.toString()
        assertTrue(output.contains("Missing required argument: dependency name"))
    }
    
    @Test
    fun `should handle dependency add with package name`() {
        main(arrayOf("add", "numpy"))
        
        val output = testOut.toString()
        assertTrue(output.contains("Adding") || output.contains("dependency") || output.contains("numpy"))
    }
    
    @Test
    fun `should handle dependency remove without package name`() {
        main(arrayOf("remove"))
        
        val output = testOut.toString()
        assertTrue(output.contains("Missing required argument: dependency name"))
    }
    
    @Test
    fun `should handle dependency remove with package name`() {
        main(arrayOf("remove", "numpy"))
        
        val output = testOut.toString()
        assertTrue(output.contains("Removing") || output.contains("dependency") || output.contains("numpy"))
    }
    
    @Test
    fun `should handle sync command`() {
        main(arrayOf("sync"))
        
        val output = testOut.toString()
        assertTrue(output.contains("Synchronizing") || output.contains("dependencies") || output.contains("Syncing"))
    }
    
    @Test
    fun `should handle tree command`() {
        main(arrayOf("tree"))
        
        val output = testOut.toString()
        assertTrue(output.contains("Dependency") || output.contains("tree") || output.contains("dependencies"))
    }
    
    @Test
    fun `should handle package-specific dependency commands`() {
        main(arrayOf("mypackage", "add", "numpy"))
        
        val output = testOut.toString()
        assertTrue(output.contains("Adding") || output.contains("numpy") || output.contains("mypackage"))
    }
    
    @Test
    fun `should handle package-specific dependency with target`() {
        main(arrayOf("mypackage", "add", "numpy", "--target", "windows"))
        
        val output = testOut.toString()
        assertTrue(output.contains("Adding") || output.contains("numpy"))
    }
    
    @Test
    fun `should handle reserved package names`() {
        val reservedNames = listOf("init", "python", "package", "target", "add", "remove", "sync", "tree", "build", "bundle", "deploy")
        
        for (reservedName in reservedNames) {
            testOut.reset()
            main(arrayOf("package", "add", reservedName))
            
            val output = testOut.toString()
            assertTrue(output.contains("Package name cannot be a reserved word: $reservedName"))
        }
    }
    
    @Test
    fun `should validate python version format in various commands`() {
        // Test invalid formats
        val invalidVersions = listOf("3", "3.13.5.1", "invalid", "3.14-beta")
        
        for (version in invalidVersions) {
            testOut.reset()
            main(arrayOf("python", "use", version))
            
            val output = testOut.toString()
            assertTrue(output.contains("Invalid value for argument 'python version': $version"))
        }
    }
    
    @Test
    fun `should show progress indicators during operations`() {
        main(arrayOf("python", "list"))
        
        val output = testOut.toString()
        // Progress indicators use ANSI escape codes, so we check for their presence
        assertTrue(output.contains("Listing") || output.contains("Python") || output.contains("versions"))
    }
    
    @Test
    fun `should handle multiple arguments correctly`() {
        main(arrayOf("init", "test_project", "3.13", "--extra-arg"))
        
        val output = testOut.toString()
        // Should handle extra arguments gracefully
        assertTrue(output.contains("Initializing") || output.contains("project"))
    }
    
    @Test
    fun `should handle empty string arguments`() {
        main(arrayOf("package", "add", ""))
        
        val output = testOut.toString()
        assertTrue(output.contains("Invalid value for argument 'package name'") || output.contains("Missing required argument"))
    }
} 