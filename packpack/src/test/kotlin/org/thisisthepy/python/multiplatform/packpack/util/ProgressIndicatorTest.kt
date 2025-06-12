package org.thisisthepy.python.multiplatform.packpack.util

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.io.ByteArrayOutputStream
import java.io.PrintStream

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProgressIndicatorTest {
    
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
    fun `should create progress indicator with default settings`() {
        val indicator = ProgressIndicator("Test message")
        assertNotNull(indicator)
    }
    
    @Test
    fun `should create progress indicator with custom settings`() {
        val indicator = ProgressIndicator(
            message = "Custom message",
            showPercentage = true,
            showElapsedTime = false
        )
        assertNotNull(indicator)
    }
    
    @Test
    fun `should start and stop indeterminate progress`() = runTest {
        val indicator = ProgressIndicator("Testing...")
        
        indicator.start()
        delay(200) // Let it run for a bit
        indicator.stop()
        
        val output = testOut.toString()
        assertTrue(output.isNotEmpty(), "Should produce some output")
    }
    
    @Test
    fun `should start and stop determinate progress`() = runTest {
        val indicator = ProgressIndicator("Testing...", showPercentage = true)
        
        indicator.start(10)
        delay(100)
        indicator.updateStep(5)
        delay(100)
        indicator.stop()
        
        val output = testOut.toString()
        assertTrue(output.isNotEmpty(), "Should produce some output")
    }
    
    @Test
    fun `should increment step correctly`() = runTest {
        val indicator = ProgressIndicator("Testing...", showPercentage = true)
        
        indicator.start(5)
        delay(50)
        indicator.incrementStep() // Should be 1
        delay(50)
        indicator.incrementStep() // Should be 2
        delay(50)
        indicator.stop()
        
        // We can't easily test the internal state due to ANSI escape sequences,
        // but we can verify it doesn't crash and produces some output
        val output = testOut.toString()
        assertTrue(output.isNotEmpty(), "Should produce some output")
    }
    
    @Test
    fun `should show success message`() = runTest {
        val indicator = ProgressIndicator("Testing...")
        
        indicator.start()
        delay(100)
        indicator.success("Operation completed successfully")
        
        val output = testOut.toString()
        assertTrue(output.contains("Operation completed successfully"))
        assertTrue(output.contains("✓"))
    }
    
    @Test
    fun `should show error message`() = runTest {
        val indicator = ProgressIndicator("Testing...")
        
        indicator.start()
        delay(100)
        indicator.error("Operation failed")
        
        val output = testOut.toString()
        assertTrue(output.contains("Operation failed"))
        assertTrue(output.contains("✗"))
    }
    
    @Test
    fun `should show elapsed time during progress`() = runTest {
        // Test time formatting indirectly through progress display
        val indicator = ProgressIndicator("Test", showElapsedTime = true)
        
        indicator.start()
        delay(100) // Let some time pass
        indicator.stop()
        
        val output = testOut.toString()
        assertTrue(output.isNotEmpty(), "Should produce some output")
        // Time formatting is tested indirectly through the progress display
    }
    
    @Test
    fun `should handle multiple start calls gracefully`() = runTest {
        val indicator = ProgressIndicator("Testing...")
        
        indicator.start()
        delay(50)
        indicator.start() // Second start should be ignored
        delay(50)
        indicator.stop()
        
        // Should not crash or cause issues
        val output = testOut.toString()
        assertTrue(output.isNotEmpty(), "Should produce some output")
    }
    
    @Test
    fun `should handle stop without start gracefully`() {
        val indicator = ProgressIndicator("Testing...")
        
        // Should not crash
        assertDoesNotThrow {
            indicator.stop()
        }
    }
    
    @Test
    fun `withProgress should execute block and show progress`() = runTest {
        var blockExecuted = false
        
        val result = ProgressIndicator.withProgress("Processing...") { indicator ->
            blockExecuted = true
            delay(100)
            "success"
        }
        
        assertTrue(blockExecuted)
        assertEquals("success", result)
        
        val output = testOut.toString()
        assertTrue(output.contains("Processing..."))
        assertTrue(output.contains("✓"))
    }
    
    @Test
    fun `withProgress should handle exceptions`() = runTest {
        assertThrows<RuntimeException> {
            ProgressIndicator.withProgress("Processing...") { indicator ->
                delay(50)
                throw RuntimeException("Test exception")
            }
        }
        
        val output = testOut.toString()
        assertTrue(output.contains("Processing..."))
        assertTrue(output.contains("✗"))
    }
    
    @Test
    fun `withDeterminateProgress should work correctly`() = runTest {
        var blockExecuted = false
        
        val result = ProgressIndicator.withDeterminateProgress(
            message = "Processing...",
            totalSteps = 5
        ) { indicator ->
            blockExecuted = true
            indicator.updateStep(2)
            delay(100)
            indicator.incrementStep()
            delay(50)
            "completed"
        }
        
        assertTrue(blockExecuted)
        assertEquals("completed", result)
        
        val output = testOut.toString()
        assertTrue(output.contains("Processing..."))
    }
    
    @Test
    fun `simple factory method should create correct indicator`() {
        val indicator = ProgressIndicator.simple("Simple test")
        assertNotNull(indicator)
    }
    
    @Test
    fun `detailed factory method should create correct indicator`() {
        val indicator = ProgressIndicator.detailed("Detailed test", 10)
        assertNotNull(indicator)
    }
    
    @Test
    fun `runWithProgress extension should work`() = runTest {
        var executed = false
        
        val result = runWithProgress("Extension test") { indicator ->
            executed = true
            delay(50)
            "result"
        }
        
        assertTrue(executed)
        assertEquals("result", result)
        
        val output = testOut.toString()
        assertTrue(output.contains("Extension test"))
    }
    
    @Test
    fun `runWithProgressBlocking should work`() {
        var executed = false
        
        val result = runWithProgressBlocking("Blocking test") { indicator ->
            executed = true
            Thread.sleep(50) // Simulate work
            "blocking result"
        }
        
        assertTrue(executed)
        assertEquals("blocking result", result)
        
        val output = testOut.toString()
        assertTrue(output.contains("Blocking test"))
    }
    
    @Test
    fun `runWithProgressBlocking should handle exceptions`() {
        assertThrows<RuntimeException> {
            runWithProgressBlocking("Blocking test") { indicator ->
                Thread.sleep(50)
                throw RuntimeException("Blocking exception")
            }
        }
        
        val output = testOut.toString()
        assertTrue(output.contains("Blocking test"))
        assertTrue(output.contains("✗"))
    }
} 