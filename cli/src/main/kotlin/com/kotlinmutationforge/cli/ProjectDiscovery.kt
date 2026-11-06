package com.kotlinmutationforge.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

data class ProjectModule(
    val root: Path,
    val sourceFiles: List<Path>,
    val testTask: String,
    val coverageTask: String,
    val coverageReport: Path,
)

/** Finds production source sets and derives the owning Gradle/JVM test boundary for each module. */
object ProjectDiscovery {
    private val excludedSegments = setOf(
        ".git",
        ".gradle",
        "build",
        "build-logic",
        "rod",
        "gradle-user-home",
    )
    private val generatedSegments = setOf("generated", "generated-src", "generated-sources")
    private val supportSegments = setOf(
        "fakes",
        "fixtures",
        "test-utils",
        "test_utils",
        "testing-utils",
        "testing_utils",
        "testing-utils-android",
        "testing_utils_android",
        "snapshot-testing",
        "snapshot_testing",
        "catalog-processor",
        "snapshot-processor",
        "processor",
        "processors",
    )
    private val supportFilePrefixes = setOf("Fake", "Fakes", "Fixture", "Fixtures", "Mock", "Mocks")

    fun discover(project: Path, scope: MutationScope = MutationScope()): List<ProjectModule> =
        discoverWithReport(project, scope).modules

    fun discoverWithReport(project: Path, scope: MutationScope = MutationScope()): DiscoveryResult {
        val files = Files.walk(project).use { paths ->
            paths
                .filter { it.isRegularFile() }
                .filter { it.toString().endsWith(".kt") }
                .filter { isProductionSource(project, it) }
                .sorted()
                .toList()
        }
        val exclusions = files.mapNotNull { file ->
            exclusion(project, file, scope)?.let { ExcludedSource(file, it) }
        }
        val selected = files.filter { exclusion(project, it, scope) == null }
        val modules = selected.groupBy { moduleRoot(project, it) }
            .mapNotNull { (root, sourceFiles) -> module(project, root, sourceFiles) }
            .sortedBy { project.relativize(it.root).toString() }
        return DiscoveryResult(modules, exclusions.sortedBy { project.relativize(it.path).toString() })
    }

    private fun module(project: Path, root: Path, sourceFiles: List<Path>): ProjectModule? {
        if (root == project) return null
        val buildScript = sequenceOf(root.resolve("build.gradle.kts"), root.resolve("build.gradle"))
            .firstOrNull { Files.exists(it) }
            ?: return null
        val relative = project.relativize(root).map(Path::toString)
        val gradlePath = ":" + relative.joinToString(":")
        val android = buildScript.readText().contains("billionbeers.android") ||
            buildScript.readText().contains("com.android")
        return ProjectModule(
            root = root,
            sourceFiles = sourceFiles.sorted(),
            testTask = if (android) "$gradlePath:testDebugUnitTest" else "$gradlePath:test",
            coverageTask = if (android) "$gradlePath:jacocoDebugReport" else "$gradlePath:jacocoTestReport",
            coverageReport = if (android) {
                root.resolve("build/reports/jacoco/jacocoDebugReport/jacocoDebugReport.xml")
            } else {
                root.resolve("build/reports/jacoco/test/jacocoTestReport.xml")
            },
        )
    }

    private fun moduleRoot(project: Path, file: Path): Path {
        var current = file.parent
        while (current != null && current.startsWith(project)) {
            if (Files.exists(current.resolve("build.gradle.kts")) || Files.exists(current.resolve("build.gradle"))) {
                return current
            }
            current = current.parent
        }
        return project
    }

    private fun isProductionSource(project: Path, file: Path): Boolean {
        val parts = project.relativize(file).map(Path::toString)
        if (parts.any { it in excludedSegments }) return false
        if (file.fileName.toString().endsWith("Test.kt")) return false
        val sourceIndex = parts.indexOfLast { it == "src" }
        if (sourceIndex < 0 || sourceIndex + 2 >= parts.size) return false
        val sourceSet = parts[sourceIndex + 1]
        val language = parts[sourceIndex + 2]
        return language in setOf("kotlin", "java") &&
            (sourceSet == "main" || sourceSet.endsWith("Main"))
    }

    private fun exclusion(project: Path, file: Path, scope: MutationScope): String? {
        val parts = project.relativize(file).map(Path::toString)
        if (parts.any { it in generatedSegments }) return "generated source"

        val root = moduleRoot(project, file)
        val modulePath = project.relativize(root).joinToString("/")
        if (scope.excludeModules.any { globMatches(it, modulePath) }) return "module exclusion"

        val relativePath = project.relativize(file).joinToString("/")
        if (scope.excludePaths.any { globMatches(it, relativePath) }) return "path exclusion"

        if (!scope.includeSupport && parts.any { it in supportSegments }) return "support source"
        if (!scope.includeSupport && supportFilePrefixes.any(file.fileName.toString()::startsWith)) return "support source"
        return null
    }

    private fun globMatches(pattern: String, value: String): Boolean = runCatching {
        java.nio.file.FileSystems.getDefault().getPathMatcher("glob:$pattern").matches(Path.of(value))
    }.getOrDefault(false)
}
