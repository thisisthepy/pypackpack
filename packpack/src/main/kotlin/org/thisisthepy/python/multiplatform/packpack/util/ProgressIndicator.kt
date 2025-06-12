package org.thisisthepy.python.multiplatform.packpack.util

import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Progress indicator for long-running operations in CLI
 */
class ProgressIndicator(
    private val message: String,
    private val showPercentage: Boolean = false,
    private val showElapsedTime: Boolean = true
) {
    private val isRunning = AtomicBoolean(false)
    private val startTime = AtomicLong(0)
    private val currentStep = AtomicInteger(0)
    private val totalSteps = AtomicInteger(0)
    private var progressJob: Job? = null
    
    // ANSI escape codes
    private val CURSOR_HIDE = "\u001B[?25l"
    private val CURSOR_SHOW = "\u001B[?25h"
    private val CLEAR_LINE = "\u001B[2K"
    private val CURSOR_TO_START = "\u001B[1G"
    private val GREEN = "\u001B[32m"
    private val BLUE = "\u001B[34m"
    private val YELLOW = "\u001B[33m"
    private val RESET = "\u001B[0m"
    private val BOLD = "\u001B[1m"
    
    // Spinner characters
    private val spinnerChars = charArrayOf('⠋', '⠙', '⠹', '⠸', '⠼', '⠴', '⠦', '⠧', '⠇', '⠏')
    
    /**
     * Start the progress indicator with indeterminate progress
     */
    fun start() {
        if (isRunning.get()) return
        
        isRunning.set(true)
        startTime.set(System.currentTimeMillis())
        print(CURSOR_HIDE)
        
        progressJob = CoroutineScope(Dispatchers.IO).launch {
            var spinnerIndex = 0
            
            while (isRunning.get()) {
                val elapsed = formatElapsedTime(System.currentTimeMillis() - startTime.get())
                val spinner = spinnerChars[spinnerIndex % spinnerChars.size]
                
                val progressText = buildString {
                    append(CLEAR_LINE)
                    append(CURSOR_TO_START)
                    append("$BLUE$spinner$RESET ")
                    append("$BOLD$message$RESET")
                    
                    if (showElapsedTime) {
                        append(" ${YELLOW}[$elapsed]$RESET")
                    }
                }
                
                print(progressText)
                
                spinnerIndex++
                delay(100) // Update every 100ms
            }
        }
    }
    
    /**
     * Start the progress indicator with determinate progress
     */
    fun start(totalSteps: Int) {
        if (isRunning.get()) return
        
        this.totalSteps.set(totalSteps)
        this.currentStep.set(0)
        isRunning.set(true)
        startTime.set(System.currentTimeMillis())
        print(CURSOR_HIDE)
        
        progressJob = CoroutineScope(Dispatchers.IO).launch {
            var spinnerIndex = 0
            
            while (isRunning.get()) {
                val elapsed = formatElapsedTime(System.currentTimeMillis() - startTime.get())
                val spinner = spinnerChars[spinnerIndex % spinnerChars.size]
                val current = currentStep.get()
                val total = this@ProgressIndicator.totalSteps.get()
                
                val progressText = buildString {
                    append(CLEAR_LINE)
                    append(CURSOR_TO_START)
                    append("$BLUE$spinner$RESET ")
                    append("$BOLD$message$RESET")
                    
                    if (showPercentage && total > 0) {
                        val percentage = (current * 100) / total
                        append(" ${GREEN}[$current/$total - $percentage%]$RESET")
                        
                        // Progress bar
                        val barWidth = 20
                        val filledWidth = (current * barWidth) / total
                        append(" [")
                        repeat(filledWidth) { append("█") }
                        repeat(barWidth - filledWidth) { append("░") }
                        append("]")
                    }
                    
                    if (showElapsedTime) {
                        append(" ${YELLOW}[$elapsed]$RESET")
                    }
                }
                
                print(progressText)
                
                spinnerIndex++
                delay(100) // Update every 100ms
            }
        }
    }
    
    /**
     * Update the current step (for determinate progress)
     */
    fun updateStep(step: Int) {
        currentStep.set(step)
    }
    
    /**
     * Increment the current step by 1
     */
    fun incrementStep() {
        currentStep.incrementAndGet()
    }
    
    /**
     * Update the message
     */
    fun updateMessage(newMessage: String) {
        // For simplicity, we'll create a new indicator with the new message
        // In a more complex implementation, we could update the message dynamically
    }
    
    /**
     * Stop the progress indicator with success message
     */
    fun success(successMessage: String? = null) {
        stop()
        val elapsed = formatElapsedTime(System.currentTimeMillis() - startTime.get())
        val finalMessage = successMessage ?: "Completed"
        
        println("${CLEAR_LINE}${CURSOR_TO_START}${GREEN}✓$RESET $BOLD$finalMessage$RESET ${YELLOW}[$elapsed]$RESET")
        print(CURSOR_SHOW)
    }
    
    /**
     * Stop the progress indicator with error message
     */
    fun error(errorMessage: String? = null) {
        stop()
        val elapsed = formatElapsedTime(System.currentTimeMillis() - startTime.get())
        val finalMessage = errorMessage ?: "Failed"
        
        println("${CLEAR_LINE}${CURSOR_TO_START}${ErrorHandler.RED}✗$RESET $BOLD$finalMessage$RESET ${YELLOW}[$elapsed]$RESET")
        print(CURSOR_SHOW)
    }
    
    /**
     * Stop the progress indicator
     */
    fun stop() {
        if (!isRunning.get()) return
        
        isRunning.set(false)
        progressJob?.cancel()
        progressJob = null
        
        // Clear the line and show cursor
        print("$CLEAR_LINE$CURSOR_TO_START$CURSOR_SHOW")
    }
    
    /**
     * Format elapsed time in human-readable format
     */
    private fun formatElapsedTime(milliseconds: Long): String {
        val seconds = milliseconds / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        
        return when {
            hours > 0 -> String.format("%02d:%02d:%02d", hours, minutes % 60, seconds % 60)
            minutes > 0 -> String.format("%02d:%02d", minutes, seconds % 60)
            else -> String.format("%.1fs", seconds + (milliseconds % 1000) / 1000.0)
        }
    }
    
    companion object {
        /**
         * Execute a block of code with a progress indicator
         */
        suspend fun <T> withProgress(
            message: String,
            showPercentage: Boolean = false,
            showElapsedTime: Boolean = true,
            block: suspend (ProgressIndicator) -> T
        ): T {
            val indicator = ProgressIndicator(message, showPercentage, showElapsedTime)
            return try {
                indicator.start()
                val result = block(indicator)
                indicator.success()
                result
            } catch (e: Exception) {
                indicator.error(e.message)
                throw e
            }
        }
        
        /**
         * Execute a block of code with determinate progress indicator
         */
        suspend fun <T> withDeterminateProgress(
            message: String,
            totalSteps: Int,
            showElapsedTime: Boolean = true,
            block: suspend (ProgressIndicator) -> T
        ): T {
            val indicator = ProgressIndicator(message, showPercentage = true, showElapsedTime)
            return try {
                indicator.start(totalSteps)
                val result = block(indicator)
                indicator.success()
                result
            } catch (e: Exception) {
                indicator.error(e.message)
                throw e
            }
        }
        
        /**
         * Simple progress indicator for quick operations
         */
        fun simple(message: String): ProgressIndicator {
            return ProgressIndicator(message, showPercentage = false, showElapsedTime = false)
        }
        
        /**
         * Detailed progress indicator for complex operations
         */
        fun detailed(message: String, totalSteps: Int): ProgressIndicator {
            return ProgressIndicator(message, showPercentage = true, showElapsedTime = true)
        }
    }
}

/**
 * Extension function to run suspending functions with progress
 */
suspend fun <T> runWithProgress(
    message: String,
    block: suspend (ProgressIndicator) -> T
): T = ProgressIndicator.withProgress(message, block = block)

/**
 * Extension function for non-suspending functions
 */
fun <T> runWithProgressBlocking(
    message: String,
    block: (ProgressIndicator) -> T
): T {
    val indicator = ProgressIndicator(message)
    return try {
        indicator.start()
        val result = block(indicator)
        indicator.success()
        result
    } catch (e: Exception) {
        indicator.error(e.message)
        throw e
    }
} 