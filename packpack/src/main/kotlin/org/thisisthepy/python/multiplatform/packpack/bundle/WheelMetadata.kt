package org.thisisthepy.python.multiplatform.packpack.bundle

import org.thisisthepy.python.multiplatform.packpack.utils.toml.TomlEditor
import org.thisisthepy.python.multiplatform.packpack.utils.toml.TomlValue
import java.io.File

internal data class RenderedWheelMetadata(
    val metadataText: String,
    val licenseFiles: Map<String, File>,
)

/**
 * Renders PEP 621 / Core Metadata 2.4 (`METADATA`) from `pyproject.toml` and collects any
 * declared license files for `<dist-info>/licenses/`.
 */
internal object WheelMetadata {
    const val METADATA_VERSION = "2.4"

    fun render(
        packageDir: File,
        editor: TomlEditor,
        packageName: String,
        version: String,
        distInfoDir: String,
    ): RenderedWheelMetadata {
        val canonicalPkgDir = packageDir.canonicalFile
        val canonicalPkgPath = canonicalPkgDir.toPath()
        val licenseFiles = linkedMapOf<String, File>()
        val seenLicenseRelPaths = linkedSetOf<String>()

        fun addLicenseFile(rawPath: String): String? {
            val normalizedInput = rawPath.trim().replace('\\', '/')
            if (normalizedInput.isEmpty()) return null
            val candidate = File(canonicalPkgDir, normalizedInput).canonicalFile
            if (!candidate.isFile) return null
            val candidatePath = candidate.toPath()
            if (!candidatePath.startsWith(canonicalPkgPath)) return null
            val relPath = canonicalPkgPath.relativize(candidatePath).toString().replace(File.separatorChar, '/')
            if (relPath.isEmpty()) return null
            if (seenLicenseRelPaths.add(relPath)) {
                licenseFiles["$distInfoDir/licenses/$relPath"] = candidate
            }
            return relPath
        }

        val description = (editor.getValue("project", "description") as? TomlValue.String)?.value?.takeIf { it.isNotEmpty() }
        val keywords = editor.getArray("project", "keywords").filter { it.isNotBlank() }
        val authors = editor.getInlineTableArray("project", "authors")
        val maintainers = editor.getInlineTableArray("project", "maintainers")
        val licenseValue = editor.getValue("project", "license")
        val declaredLicenseFiles = editor.getArray("project", "license-files")
        val classifiers = editor.getArray("project", "classifiers")
        val requiresPython = (editor.getValue("project", "requires-python") as? TomlValue.String)?.value?.takeIf { it.isNotBlank() }
        val projectUrls = editor.getStringEntries("project.urls")
        val dependencies = editor.getArray("project", "dependencies")
        val optionalExtras = editor.getTableKeys("project.optional-dependencies")
        val readme = resolveReadme(canonicalPkgDir, editor.getValue("project", "readme"))

        val metadataText =
            buildString {
                appendLine("Metadata-Version: $METADATA_VERSION")
                appendLine("Name: $packageName")
                appendLine("Version: $version")

                if (description != null) {
                    appendLine("Summary: ${description.replace('\r', ' ').replace('\n', ' ').trim()}")
                }
                if (keywords.isNotEmpty()) {
                    appendLine("Keywords: ${keywords.joinToString(",")}")
                }

                appendPeopleHeaders("Author", "Author-email", authors)
                appendPeopleHeaders("Maintainer", "Maintainer-email", maintainers)

                when (licenseValue) {
                    is TomlValue.String -> {
                        if (licenseValue.value.isNotBlank()) {
                            appendLine("License-Expression: ${licenseValue.value}")
                        }
                    }

                    is TomlValue.InlineTable -> {
                        val text = (licenseValue.pairs["text"] as? TomlValue.String)?.value
                        if (!text.isNullOrBlank()) {
                            appendLine("License: $text")
                        }
                        val file = (licenseValue.pairs["file"] as? TomlValue.String)?.value
                        if (!file.isNullOrBlank()) {
                            addLicenseFile(file)
                        }
                    }

                    else -> {}
                }

                for (rawLicensePath in declaredLicenseFiles) {
                    addLicenseFile(rawLicensePath)
                }
                for (relPath in seenLicenseRelPaths) {
                    appendLine("License-File: $relPath")
                }

                for (classifier in classifiers) {
                    appendLine("Classifier: $classifier")
                }
                if (requiresPython != null) {
                    appendLine("Requires-Python: $requiresPython")
                }
                for ((label, url) in projectUrls) {
                    appendLine("Project-URL: $label, $url")
                }
                for (dep in dependencies) {
                    appendLine("Requires-Dist: $dep")
                }
                for (extra in optionalExtras) {
                    appendLine("Provides-Extra: $extra")
                    for (dep in editor.getArray("project.optional-dependencies", extra)) {
                        val trimmedDep = dep.trim()
                        if (trimmedDep.isEmpty()) continue
                        val requiresDist =
                            if (';' in trimmedDep) {
                                val req = trimmedDep.substringBefore(';').trim()
                                val marker = trimmedDep.substringAfter(';').trim()
                                "$req; ($marker) and extra == '$extra'"
                            } else {
                                "$trimmedDep; extra == '$extra'"
                            }
                        appendLine("Requires-Dist: $requiresDist")
                    }
                }

                if (readme != null) {
                    appendLine("Description-Content-Type: ${readme.contentType}")
                    appendLine()
                    appendLine(readme.body.trimEnd('\r', '\n'))
                }
            }

        return RenderedWheelMetadata(
            metadataText = metadataText,
            licenseFiles = licenseFiles,
        )
    }

    private fun StringBuilder.appendPeopleHeaders(
        nameHeader: String,
        emailHeader: String,
        people: List<Map<String, String>>,
    ) {
        val namesOnly = mutableListOf<String>()
        val emails = mutableListOf<String>()
        for (person in people) {
            val name = person["name"]?.trim()?.takeIf { it.isNotEmpty() }
            val email = person["email"]?.trim()?.takeIf { it.isNotEmpty() }
            when {
                name != null && email != null -> emails += "$name <$email>"
                name != null -> namesOnly += name
                email != null -> emails += email
            }
        }
        if (namesOnly.isNotEmpty()) {
            appendLine("$nameHeader: ${namesOnly.joinToString(", ")}")
        }
        if (emails.isNotEmpty()) {
            appendLine("$emailHeader: ${emails.joinToString(", ")}")
        }
    }

    private data class ResolvedReadme(
        val contentType: String,
        val body: String,
    )

    private fun resolveReadme(
        packageDir: File,
        readmeValue: TomlValue?,
    ): ResolvedReadme? =
        when (readmeValue) {
            is TomlValue.String -> {
                val file = File(packageDir, readmeValue.value.trim())
                if (file.isFile) {
                    ResolvedReadme(
                        contentType = inferContentType(file.name),
                        body = file.readText(),
                    )
                } else {
                    null
                }
            }

            is TomlValue.InlineTable -> {
                val explicitType = (readmeValue.pairs["content-type"] as? TomlValue.String)?.value?.takeIf { it.isNotBlank() }
                val filePath = (readmeValue.pairs["file"] as? TomlValue.String)?.value?.trim()
                val text = (readmeValue.pairs["text"] as? TomlValue.String)?.value
                when {
                    text != null -> {
                        ResolvedReadme(
                            contentType = explicitType ?: "text/plain",
                            body = text,
                        )
                    }

                    !filePath.isNullOrEmpty() -> {
                        val file = File(packageDir, filePath)
                        if (file.isFile) {
                            ResolvedReadme(
                                contentType = explicitType ?: inferContentType(file.name),
                                body = file.readText(),
                            )
                        } else {
                            null
                        }
                    }

                    else -> null
                }
            }

            else -> null
        }

    private fun inferContentType(fileName: String): String =
        when (fileName.substringAfterLast('.', "").lowercase()) {
            "md", "markdown" -> "text/markdown"
            "rst" -> "text/x-rst"
            else -> "text/plain"
        }
}
