package com.kotlinmutationforge.cli

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties

/** Controls which production sources are considered application behavior. */
data class MutationScope(
    val includeSupport: Boolean = false,
    val includePreviews: Boolean = false,
    val excludePaths: List<String> = emptyList(),
    val excludeModules: List<String> = emptyList(),
) {
    companion object {
        fun load(project: Path, arguments: Arguments): MutationScope {
            val configuredFile = arguments.configFile?.let(Paths::get)?.let { path ->
                if (path.isAbsolute) path else project.resolve(path)
            } ?: project.resolve(".mutation-forge.properties")
            val properties = Properties()
            if (Files.exists(configuredFile)) {
                configuredFile.toFile().inputStream().use(properties::load)
            }
            return MutationScope(
                includeSupport = arguments.includeSupport || properties.boolean("includeSupport"),
                includePreviews = arguments.includePreviews || properties.boolean("includePreviews"),
                excludePaths = properties.csv("excludePath") + arguments.excludePaths,
                excludeModules = properties.csv("excludeModule") + arguments.excludeModules,
            )
        }

        private fun Properties.boolean(name: String): Boolean =
            getProperty(name)?.trim()?.toBooleanStrictOrNull() ?: false

        private fun Properties.csv(name: String): List<String> =
            getProperty(name)?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
    }
}

data class ExcludedSource(
    val path: Path,
    val reason: String,
)

data class DiscoveryResult(
    val modules: List<ProjectModule>,
    val excludedSources: List<ExcludedSource>,
)
