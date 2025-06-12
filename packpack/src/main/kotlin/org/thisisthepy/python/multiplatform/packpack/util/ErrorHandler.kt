package org.thisisthepy.python.multiplatform.packpack.util

import java.io.File

/**
 * Centralized error handling and user-friendly message generation
 */
object ErrorHandler {
    
    // ANSI color codes for better terminal output
    const val RED = "\u001B[31m"
    private const val YELLOW = "\u001B[33m"
    private const val GREEN = "\u001B[32m"
    private const val BLUE = "\u001B[34m"
    private const val RESET = "\u001B[0m"
    private const val BOLD = "\u001B[1m"
    
    /**
     * Error types for categorized handling
     */
    enum class ErrorType {
        MISSING_ARGUMENT,
        INVALID_ARGUMENT,
        FILE_NOT_FOUND,
        PERMISSION_DENIED,
        NETWORK_ERROR,
        TOOL_NOT_FOUND,
        OPERATION_FAILED,
        VALIDATION_ERROR,
        UNKNOWN_COMMAND
    }
    
    /**
     * Display an error message with context and suggestions
     */
    fun showError(
        type: ErrorType,
        message: String,
        context: String? = null,
        suggestions: List<String> = emptyList(),
        showHelp: Boolean = false
    ) {
        println("${RED}${BOLD}Error:${RESET} $message")
        
        context?.let {
            println("${YELLOW}Context:${RESET} $it")
        }
        
        if (suggestions.isNotEmpty()) {
            println("\n${GREEN}${BOLD}Suggestions:${RESET}")
            suggestions.forEach { suggestion ->
                println("  • $suggestion")
            }
        }
        
        if (showHelp) {
            println("\n${BLUE}Run 'pypackpack help' for more information.${RESET}")
        }
        
        println() // Empty line for better readability
    }
    
    /**
     * Handle missing argument errors
     */
    fun missingArgument(argumentName: String, command: String, example: String? = null) {
        val suggestions = mutableListOf<String>()
        example?.let { suggestions.add("Example: $it") }
        suggestions.add("Run 'pypackpack help' to see all available commands")
        
        showError(
            type = ErrorType.MISSING_ARGUMENT,
            message = "Missing required argument: $argumentName",
            context = "Command '$command' requires the '$argumentName' argument",
            suggestions = suggestions,
            showHelp = false
        )
    }
    
    /**
     * Handle invalid argument errors
     */
    fun invalidArgument(argumentName: String, value: String, expectedFormat: String? = null) {
        val suggestions = mutableListOf<String>()
        expectedFormat?.let { suggestions.add("Expected format: $it") }
        
        showError(
            type = ErrorType.INVALID_ARGUMENT,
            message = "Invalid value for argument '$argumentName': $value",
            suggestions = suggestions
        )
    }
    
    /**
     * Handle unknown command errors with suggestions
     */
    fun unknownCommand(command: String, availableCommands: List<String> = emptyList()) {
        val suggestions = mutableListOf<String>()
        
        // Find similar commands using simple string distance
        val similarCommands = findSimilarCommands(command, availableCommands)
        if (similarCommands.isNotEmpty()) {
            suggestions.add("Did you mean: ${similarCommands.joinToString(", ")}")
        }
        
        suggestions.add("Run 'pypackpack help' to see all available commands")
        
        showError(
            type = ErrorType.UNKNOWN_COMMAND,
            message = "Unknown command: $command",
            suggestions = suggestions
        )
    }
    
    /**
     * Handle file/directory not found errors
     */
    fun fileNotFound(path: String, expectedType: String = "file") {
        val file = File(path)
        val parentDir = file.parentFile
        
        val suggestions = mutableListOf<String>()
        
        if (parentDir != null && !parentDir.exists()) {
            suggestions.add("Parent directory '${parentDir.path}' does not exist")
        }
        
        when (expectedType.lowercase()) {
            "project" -> {
                suggestions.add("Run 'pypackpack init' to create a new project")
                suggestions.add("Make sure you're in the correct directory")
            }
            "package" -> {
                suggestions.add("Run 'pypackpack package add <name>' to create a new package")
            }
            "pyproject.toml" -> {
                suggestions.add("This doesn't appear to be a Python project directory")
                suggestions.add("Run 'pypackpack init' to initialize a new project")
            }
        }
        
        showError(
            type = ErrorType.FILE_NOT_FOUND,
            message = "Could not find $expectedType: $path",
            suggestions = suggestions
        )
    }
    
    /**
     * Handle tool not found errors
     */
    fun toolNotFound(toolName: String, installInstructions: String? = null) {
        val suggestions = mutableListOf<String>()
        
        when (toolName.lowercase()) {
            "uv" -> {
                suggestions.add("Install UV using: curl -LsSf https://astral.sh/uv/install.sh | sh")
                suggestions.add("Or visit: https://docs.astral.sh/uv/getting-started/installation/")
            }
            "python" -> {
                suggestions.add("Install Python using UV: uv python install <version>")
                suggestions.add("Or download from: https://python.org/downloads/")
            }
        }
        
        installInstructions?.let { suggestions.add(it) }
        
        showError(
            type = ErrorType.TOOL_NOT_FOUND,
            message = "$toolName is not installed or not found in PATH",
            suggestions = suggestions
        )
    }
    
    /**
     * Handle operation failed errors
     */
    fun operationFailed(operation: String, reason: String? = null, suggestions: List<String> = emptyList()) {
        showError(
            type = ErrorType.OPERATION_FAILED,
            message = "Failed to $operation",
            context = reason,
            suggestions = suggestions
        )
    }
    
    /**
     * Handle network-related errors
     */
    fun networkError(operation: String, details: String? = null) {
        val suggestions = listOf(
            "Check your internet connection",
            "Verify that the package index is accessible",
            "Try again in a few moments",
            "Check if you're behind a proxy or firewall"
        )
        
        showError(
            type = ErrorType.NETWORK_ERROR,
            message = "Network error during $operation",
            context = details,
            suggestions = suggestions
        )
    }
    
    /**
     * Handle permission errors
     */
    fun permissionDenied(operation: String, path: String? = null) {
        val suggestions = mutableListOf<String>()
        
        path?.let {
            suggestions.add("Check permissions for: $it")
        }
        
        suggestions.addAll(listOf(
            "Try running with appropriate permissions",
            "Make sure the directory is writable",
            "Check if files are being used by another process"
        ))
        
        showError(
            type = ErrorType.PERMISSION_DENIED,
            message = "Permission denied while trying to $operation",
            context = path?.let { "Path: $it" },
            suggestions = suggestions
        )
    }
    
    /**
     * Validate Python version format
     */
    fun validatePythonVersion(version: String): Boolean {
        val pythonVersionRegex = Regex("""^\d+\.\d+(\.\d+)?$""")
        return pythonVersionRegex.matches(version)
    }
    
    /**
     * Validate package name format
     */
    fun validatePackageName(name: String): Boolean {
        // Python package naming conventions
        val packageNameRegex = Regex("""^[a-zA-Z][a-zA-Z0-9_]*$""")
        return packageNameRegex.matches(name) && !name.startsWith("_")
    }
    
    /**
     * Find similar commands using simple string distance
     */
    private fun findSimilarCommands(input: String, commands: List<String>): List<String> {
        return commands
            .map { it to levenshteinDistance(input.lowercase(), it.lowercase()) }
            .filter { it.second <= 2 } // Only suggest if distance is 2 or less
            .sortedBy { it.second }
            .take(3) // Limit to 3 suggestions
            .map { it.first }
    }
    
    /**
     * Calculate Levenshtein distance between two strings
     */
    private fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j
        
        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                dp[i][j] = if (s1[i - 1] == s2[j - 1]) {
                    dp[i - 1][j - 1]
                } else {
                    1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
                }
            }
        }
        
        return dp[s1.length][s2.length]
    }
    
    /**
     * Show success message
     */
    fun showSuccess(message: String) {
        println("${GREEN}${BOLD}Success:${RESET} $message")
    }
    
    /**
     * Show warning message
     */
    fun showWarning(message: String) {
        println("${YELLOW}${BOLD}Warning:${RESET} $message")
    }
    
    /**
     * Show info message
     */
    fun showInfo(message: String) {
        println("${BLUE}${BOLD}Info:${RESET} $message")
    }
} 