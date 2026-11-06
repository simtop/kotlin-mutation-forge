package com.kotlinmutationforge.cli

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectDiscoveryTest {
    @Test
    fun groups_production_sources_by_module_and_derives_jvm_tasks() {
        val project = Files.createTempDirectory("mutation-forge-project")
        project.resolve("core/build.gradle.kts").apply {
            parent.createDirectories()
            writeText("plugins { id(\"org.jetbrains.kotlin.jvm\") }")
        }
        project.resolve("core/src/main/kotlin/sample/Logic.kt").apply {
            parent.createDirectories()
            writeText("fun logic() = true")
        }
        project.resolve("core/src/test/kotlin/sample/LogicTest.kt").apply {
            parent.createDirectories()
            writeText("fun test() = true")
        }

        val modules = ProjectDiscovery.discover(project)

        assertEquals(1, modules.size)
        assertEquals(":core:test", modules.single().testTask)
        assertEquals(listOf("core/src/main/kotlin/sample/Logic.kt"), modules.single().sourceFiles.map(project::relativize).map { it.toString() })
    }

    @Test
    fun excludes_support_sources_but_can_include_them_explicitly() {
        val project = Files.createTempDirectory("mutation-forge-project")
        project.resolve("feature/build.gradle.kts").apply {
            parent.createDirectories()
            writeText("plugins { id(\"org.jetbrains.kotlin.jvm\") }")
        }
        project.resolve("feature/src/main/kotlin/sample/Logic.kt").apply {
            parent.createDirectories()
            writeText("fun logic() = true")
        }
        project.resolve("feature/src/main/kotlin/sample/Fake.kt").apply {
            parent.createDirectories()
            writeText("fun fake() = true")
        }

        val filtered = ProjectDiscovery.discoverWithReport(project)
        assertEquals(listOf("feature/src/main/kotlin/sample/Logic.kt"), filtered.modules.single().sourceFiles.map(project::relativize).map { it.toString() })
        assertEquals("support source", filtered.excludedSources.single().reason)

        val included = ProjectDiscovery.discover(project, MutationScope(includeSupport = true))
        assertEquals(2, included.single().sourceFiles.size)
    }

    @Test
    fun applies_path_and_module_globs() {
        val project = Files.createTempDirectory("mutation-forge-project")
        listOf("feature", "catalog").forEach { module ->
            project.resolve("$module/build.gradle.kts").apply {
                parent.createDirectories()
                writeText("plugins { id(\"org.jetbrains.kotlin.jvm\") }")
            }
            project.resolve("$module/src/main/kotlin/sample/Logic.kt").apply {
                parent.createDirectories()
                writeText("fun logic() = true")
            }
        }

        val result = ProjectDiscovery.discoverWithReport(
            project,
            MutationScope(excludePaths = listOf("feature/**"), excludeModules = listOf("catalog")),
        )

        assertTrue(result.modules.isEmpty())
        assertEquals(2, result.excludedSources.size)
    }

    @Test
    fun loads_project_scope_properties_and_resolves_config_relative_to_project() {
        val project = Files.createTempDirectory("mutation-forge-project")
        project.resolve("feature/build.gradle.kts").apply {
            parent.createDirectories()
            writeText("plugins { id(\"org.jetbrains.kotlin.jvm\") }")
        }
        project.resolve("feature/src/main/kotlin/sample/Logic.kt").apply {
            parent.createDirectories()
            writeText("fun logic() = true")
        }
        project.resolve("scope.properties").writeText("excludePath=feature/**")

        val arguments = Arguments(
            project = project.toString(),
            source = null,
            all = true,
            testTask = null,
            coverageTask = null,
            coverageReport = null,
            scanOnly = true,
            reuseCoverage = true,
            configFile = "scope.properties",
            includeSupport = false,
            includePreviews = false,
            excludePaths = emptyList(),
            excludeModules = emptyList(),
            explainScope = false,
            allowUncovered = false,
            maxMutants = Int.MAX_VALUE,
            timeoutSeconds = 300,
        )

        val scope = MutationScope.load(project, arguments)

        assertEquals(listOf("feature/**"), scope.excludePaths)
        assertTrue(scope.excludePaths.single().isNotBlank())
        assertTrue(project.resolve("scope.properties").readText().contains("excludePath"))
        assertTrue(ProjectDiscovery.discoverWithReport(project, scope).modules.isEmpty())
    }
}
