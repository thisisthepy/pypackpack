package org.thisisthepy.python.multiplatform.packpack.util

/**
 * Command execution result data class
 */
data class CommandResult(
    val success: Boolean,
    val output: String,
    val error: String,
) {
    /**
     * Convert to string representation
     */
    override fun toString(): String =
        if (success) {
            "Success: $output"
        } else {
            "Error: $error"
        }
}
