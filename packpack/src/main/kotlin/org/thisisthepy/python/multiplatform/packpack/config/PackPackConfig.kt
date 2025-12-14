package org.thisisthepy.python.multiplatform.packpack.config

import java.io.File

/**
 * Global configuration for PyPackPack Manages default settings and provides centralized access to
 * configuration values
 */
object PackPackConfig {
    private const val FALLBACK_PYTHON_VERSION = "3.13"
    private const val PYPROJECT_FILE = "pyproject.toml"

    /**
     * Default Python version to use when creating new environments This value is initialized from
     * pyproject.toml if available, or falls back to 3.13
     */
    var defaultPythonVersion: String = FALLBACK_PYTHON_VERSION
        private set

    /** Whether the configuration has been initialized */
    private var initialized = false

    /**
     * Initialize configuration from pyproject.toml in the current working directory This should be
     * called once at application startup
     *
     * @return true if configuration was loaded from pyproject.toml, false if using fallback
     */
    fun initialize(): Boolean {
        if (initialized) {
            return false
        }

        initialized = true

        // Find project root with pyproject.toml
        val projectRoot = findProjectRoot()
        if (projectRoot == null) {
            // No project found, use fallback
            defaultPythonVersion = FALLBACK_PYTHON_VERSION
            return false
        }

        // Try to parse pyproject.toml
        val pyprojectFile = File(projectRoot, PYPROJECT_FILE)
        try {
            val parser = PyprojectParser()
            val config =
                parser.parseFromFile(
                    pyprojectFile,
                    applyDefaults = false,
                    validateConfig = false,
                )

            // Extract Python version from requires-python
            config.project?.requiresPython?.let { requiresPython ->
                extractPythonVersion(requiresPython)?.let { version ->
                    defaultPythonVersion = version
                    return true
                }
            }
        } catch (e: Exception) {
            // Ignore parsing errors and use fallback
        }

        // Fallback if parsing failed or no requires-python found
        defaultPythonVersion = FALLBACK_PYTHON_VERSION
        return false
    }

    /**
     * Manually set the default Python version This can be used to override the configuration after
     * initialization
     *
     * @param version Python version string (e.g., "3.13", "3.12.1")
     */
    fun setDefaultPythonVersion(version: String) {
        defaultPythonVersion = version
    }

    /** Reset configuration to uninitialized state Useful for testing */
    internal fun reset() {
        initialized = false
        defaultPythonVersion = FALLBACK_PYTHON_VERSION
    }

    /**
     * Extract a simple Python version from PEP 440 version specifier Supports formats like:
     * ">=3.13", ">3.12", "==3.13.1", "~=3.13.0"
     *
     * @param requiresPython PEP 440 version specifier
     * @return Extracted version string or null if cannot parse
     */
    private fun extractPythonVersion(requiresPython: String): String? {
        // Remove whitespace
        val cleaned = requiresPython.trim()

        // Match common patterns: >=3.13, >3.12, ==3.13.1, ~=3.13.0, 3.13
        val versionRegex = """[><=~!]*\s*(\d+\.\d+(?:\.\d+)?)""".toRegex()
        val match = versionRegex.find(cleaned)

        return match?.groupValues?.getOrNull(1)
    }

    /**
     * Find project root directory by searching for pyproject.toml Starts from current working
     * directory and searches upward
     *
     * @return Project root directory or null if not found
     */
    private fun findProjectRoot(): File? {
        var dir = File(System.getProperty("user.dir"))
        while (dir.parentFile != null) {
            if (File(dir, PYPROJECT_FILE).exists()) {
                return dir
            }
            dir = dir.parentFile
        }
        return null
    }
}
