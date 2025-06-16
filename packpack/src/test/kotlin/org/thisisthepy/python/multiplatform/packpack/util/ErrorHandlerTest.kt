package org.thisisthepy.python.multiplatform.packpack.util

import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.io.ByteArrayOutputStream
import java.io.PrintStream

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ErrorHandlerTest {
    
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
    fun `should show basic error message`() {
        ErrorHandler.showError(
            type = ErrorHandler.ErrorType.VALIDATION_ERROR,
            message = "Test error message"
        )
        
        val output = testOut.toString()
        assertTrue(output.contains("Error:") && output.contains("Test error message"))
    }
    
    @Test
    fun `should show error with context`() {
        ErrorHandler.showError(
            type = ErrorHandler.ErrorType.VALIDATION_ERROR,
            message = "Test error message",
            context = "Additional context information"
        )
        
        val output = testOut.toString()
        assertTrue(output.contains("Error:") && output.contains("Test error message"))
        assertTrue(output.contains("Context:") && output.contains("Additional context information"))
    }
    
    @Test
    fun `should show error with suggestions`() {
        val suggestions = listOf("Try this", "Or try that", "Maybe this works")
        
        ErrorHandler.showError(
            type = ErrorHandler.ErrorType.VALIDATION_ERROR,
            message = "Test error message",
            suggestions = suggestions
        )
        
        val output = testOut.toString()
        assertTrue(output.contains("Error:") && output.contains("Test error message"))
        assertTrue(output.contains("Suggestions:"))
        suggestions.forEach { suggestion ->
            assertTrue(output.contains(suggestion))
        }
    }
    
    @Test
    fun `should show error with help flag`() {
        ErrorHandler.showError(
            type = ErrorHandler.ErrorType.VALIDATION_ERROR,
            message = "Test error message",
            showHelp = true
        )
        
        val output = testOut.toString()
        assertTrue(output.contains("Error:") && output.contains("Test error message"))
        assertTrue(output.contains("Run 'pypackpack help' for more information"))
    }
    
    @Test
    fun `should handle missing argument error`() {
        ErrorHandler.missingArgument(
            argumentName = "python version",
            command = "python use",
            example = "pypackpack python use 3.13"
        )
        
        val output = testOut.toString()
        assertTrue(output.contains("Missing required argument: python version"))
        assertTrue(output.contains("Command 'python use' requires"))
        assertTrue(output.contains("Example: pypackpack python use 3.13"))
    }
    
    @Test
    fun `should handle invalid argument error`() {
        ErrorHandler.invalidArgument(
            argumentName = "python version",
            value = "invalid-version",
            expectedFormat = "X.Y or X.Y.Z"
        )
        
        val output = testOut.toString()
        assertTrue(output.contains("Invalid value for argument 'python version': invalid-version"))
        assertTrue(output.contains("Expected format: X.Y or X.Y.Z"))
    }
    
    @Test
    fun `should handle unknown command with suggestions`() {
        val availableCommands = listOf("init", "python", "package", "target")
        
        ErrorHandler.unknownCommand("initt", availableCommands)
        
        val output = testOut.toString()
        assertTrue(output.contains("Unknown command: initt"))
        assertTrue(output.contains("Did you mean: init")) // Should suggest similar command
    }
    
    @Test
    fun `should handle file not found error`() {
        ErrorHandler.fileNotFound("/path/to/missing/file.txt", "project")
        
        val output = testOut.toString()
        assertTrue(output.contains("Could not find project: /path/to/missing/file.txt"))
        assertTrue(output.contains("Run 'pypackpack init' to create a new project"))
    }
    
    @Test
    fun `should handle tool not found error for UV`() {
        ErrorHandler.toolNotFound("UV")
        
        val output = testOut.toString()
        assertTrue(output.contains("UV is not installed or not found in PATH"))
        assertTrue(output.contains("Install UV using: curl -LsSf"))
    }
    
    @Test
    fun `should handle tool not found error for Python`() {
        ErrorHandler.toolNotFound("Python")
        
        val output = testOut.toString()
        assertTrue(output.contains("Python is not installed or not found in PATH"))
        assertTrue(output.contains("Install Python using UV"))
    }
    
    @Test
    fun `should handle operation failed error`() {
        val suggestions = listOf("Check connection", "Try again")
        
        ErrorHandler.operationFailed(
            operation = "install package",
            reason = "Network timeout",
            suggestions = suggestions
        )
        
        val output = testOut.toString()
        assertTrue(output.contains("Failed to install package"))
        assertTrue(output.contains("Network timeout"))
        assertTrue(output.contains("Check connection"))
        assertTrue(output.contains("Try again"))
    }
    
    @Test
    fun `should handle network error`() {
        ErrorHandler.networkError("download package", "Connection refused")
        
        val output = testOut.toString()
        assertTrue(output.contains("Network error during download package"))
        assertTrue(output.contains("Connection refused"))
        assertTrue(output.contains("Check your internet connection"))
    }
    
    @Test
    fun `should handle permission denied error`() {
        ErrorHandler.permissionDenied("write file", "/protected/path")
        
        val output = testOut.toString()
        assertTrue(output.contains("Permission denied while trying to write file"))
        assertTrue(output.contains("Path: /protected/path"))
        assertTrue(output.contains("Check permissions for: /protected/path"))
    }
    
    @Test
    fun `should validate Python version correctly`() {
        // Valid versions
        assertTrue(ErrorHandler.validatePythonVersion("3.13"))
        assertTrue(ErrorHandler.validatePythonVersion("3.13.2"))
        
        // Invalid versions
        assertFalse(ErrorHandler.validatePythonVersion("3"))
        assertFalse(ErrorHandler.validatePythonVersion("3.13.5.1"))
        assertFalse(ErrorHandler.validatePythonVersion("invalid"))
        assertFalse(ErrorHandler.validatePythonVersion("3.13-beta"))
        assertFalse(ErrorHandler.validatePythonVersion(""))
    }
    
    @Test
    fun `should validate package name correctly`() {
        // Valid package names
        assertTrue(ErrorHandler.validatePackageName("mypackage"))
        assertTrue(ErrorHandler.validatePackageName("my_package"))
        assertTrue(ErrorHandler.validatePackageName("package123"))
        assertTrue(ErrorHandler.validatePackageName("MyPackage"))
        assertTrue(ErrorHandler.validatePackageName("a"))
        
        // Invalid package names
        assertFalse(ErrorHandler.validatePackageName("123package")) // starts with number
        assertFalse(ErrorHandler.validatePackageName("_package")) // starts with underscore
        assertFalse(ErrorHandler.validatePackageName("my-package")) // contains hyphen
        assertFalse(ErrorHandler.validatePackageName("my.package")) // contains dot
        assertFalse(ErrorHandler.validatePackageName("my package")) // contains space
        assertFalse(ErrorHandler.validatePackageName("")) // empty
    }
    
    @Test
    fun `should find similar commands using Levenshtein distance`() {
        val commands = listOf("init", "python", "package", "target", "add", "remove")
        
        // Test through unknownCommand which uses findSimilarCommands internally
        ErrorHandler.unknownCommand("initt", commands)
        val output1 = testOut.toString()
        assertTrue(output1.contains("Did you mean: init"))
        
        // Clear output for next test
        testOut.reset()
        
        ErrorHandler.unknownCommand("pythno", commands)
        val output2 = testOut.toString()
        assertTrue(output2.contains("Did you mean: python"))
        
        // Clear output for next test
        testOut.reset()
        
        ErrorHandler.unknownCommand("packge", commands)
        val output3 = testOut.toString()
        assertTrue(output3.contains("Did you mean: package"))
    }
    
    @Test
    fun `should not suggest commands with high distance`() {
        val commands = listOf("init", "python", "package")
        
        ErrorHandler.unknownCommand("completely_different_command", commands)
        val output = testOut.toString()
        assertFalse(output.contains("Did you mean:"))
    }
    
    @Test
    fun `should show success message`() {
        ErrorHandler.showSuccess("Operation completed successfully")
        
        val output = testOut.toString()
        assertTrue(output.contains("Success:") && output.contains("Operation completed successfully"))
    }
    
    @Test
    fun `should show warning message`() {
        ErrorHandler.showWarning("This is a warning")
        
        val output = testOut.toString()
        assertTrue(output.contains("Warning:") && output.contains("This is a warning"))
    }
    
    @Test
    fun `should show info message`() {
        ErrorHandler.showInfo("This is information")
        
        val output = testOut.toString()
        assertTrue(output.contains("Info:") && output.contains("This is information"))
    }
    
    @Test
    fun `should handle file not found with different types`() {
        // Test pyproject.toml type
        testOut.reset()
        ErrorHandler.fileNotFound("pyproject.toml", "pyproject.toml")
        val output1 = testOut.toString()
        assertTrue(output1.contains("This doesn't appear to be a Python project directory"))
        
        // Test package type
        testOut.reset()
        ErrorHandler.fileNotFound("my_package", "package")
        val output2 = testOut.toString()
        assertTrue(output2.contains("Run 'pypackpack package add <name>' to create a new package"))
    }
    
    @Test
    fun `should test similar command suggestions through public interface`() {
        // Test Levenshtein distance indirectly through unknownCommand
        val commands = listOf("init", "python", "package", "target")
        
        // Test close match
        testOut.reset()
        ErrorHandler.unknownCommand("initt", commands)
        val output1 = testOut.toString()
        assertTrue(output1.contains("Did you mean: init"))
        
        // Test another close match
        testOut.reset()
        ErrorHandler.unknownCommand("pythno", commands)
        val output2 = testOut.toString()
        assertTrue(output2.contains("Did you mean: python"))
        
        // Test no suggestion for very different command
        testOut.reset()
        ErrorHandler.unknownCommand("completely_different", commands)
        val output3 = testOut.toString()
        assertFalse(output3.contains("Did you mean:"))
    }
} 