package com.kotlinmutationforge.cli

import com.kotlinmutationforge.core.MutationResult
import com.kotlinmutationforge.core.MutationSite
import com.kotlinmutationforge.core.MutationStatus
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolute
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

fun main(rawArgs: Array<String>) {
    val arguments = parseArguments(rawArgs)
    val project = Path.of(arguments.project).absolute().normalize()
    require(project.exists()) { "Project does not exist: $project" }
    val scope = MutationScope.load(project, arguments)
    val modules = if (arguments.all) {
        val discovery = ProjectDiscovery.discoverWithReport(project, scope)
        printExcludedSources(project, discovery.excludedSources, arguments.explainScope)
        discovery.modules
    } else {
        val source = project.resolve(requireNotNull(arguments.source)).normalize()
        require(source.startsWith(project)) { "Source must be inside --project" }
        require(source.exists()) { "Source does not exist: $source" }
        requireNotNull(arguments.testTask) { "Missing --test-task for --source mode" }
        listOf(
            ProjectModule(
                root = source.parent,
                sourceFiles = listOf(source),
                testTask = arguments.testTask,
                coverageTask = arguments.coverageTask ?: "",
                coverageReport = arguments.coverageReport?.let(project::resolve)
                    ?: error("Missing --coverage-report for --source mode"),
            ),
        )
    }
    require(modules.isNotEmpty()) { "No production Kotlin modules found" }
    val analyzer = KotlinSourceAnalyzer()
    if (arguments.scanOnly) {
        var total = 0
        modules.forEach { module ->
            module.sourceFiles.forEach { source ->
                val sites = analyzer.discover(source, scope.includePreviews)
                total += sites.size
                println("${project.relativize(source)}: ${sites.size} mutation sites")
                sites.forEach { println("  ${project.relativize(source)}:${it.line} ${it.description}") }
            }
        }
        println("Total: $total mutation sites across ${modules.size} modules")
        return
    }
    val runner = ProcessRunner()
    val planned = mutableListOf<PlannedMutation>()
    var skippedWithoutCoverage = 0
    modules.forEach { module ->
        if (!arguments.reuseCoverage) {
            require(module.coverageTask.isNotBlank()) {
                "Missing --coverage-task for ${project.relativize(module.root)}; use --reuse-coverage with an existing report"
            }
            val coverageRun = runner.run(project, module.coverageTask, arguments.timeoutSeconds)
            require(!coverageRun.timedOut && coverageRun.exitCode == 0) {
                "Coverage task failed for ${project.relativize(module.root)} " +
                    "(exit=${coverageRun.exitCode}, timeout=${coverageRun.timedOut})\n${coverageRun.output}"
            }
            println("Coverage passed for ${project.relativize(module.root)} in ${coverageRun.durationMillis} ms")
        }
        val report = module.coverageReport.takeIf { it.exists() }
        val coverage = report?.let(JaCoCoCoverage::read)
        if (report == null && !arguments.allowUncovered) {
            skippedWithoutCoverage++
            println(
                "Skipping ${project.relativize(module.root)}: no JaCoCo XML " +
                    "(use --allow-uncovered for exploratory mutation)",
            )
            return@forEach
        }
        if (report == null) {
            println("Warning: no JaCoCo XML for ${project.relativize(module.root)}; all sites selected")
        }
        module.sourceFiles.forEach { source ->
            val sites = analyzer.discover(source, scope.includePreviews)
            sites.filter { coverage == null || coverage.covers(source, it.line) }.forEach { site ->
                planned += PlannedMutation(source, module.testTask, site)
            }
        }
    }
    val selected = planned.take(arguments.maxMutants)
    if (skippedWithoutCoverage > 0) {
        println("Skipped $skippedWithoutCoverage modules without JaCoCo XML")
    }
    val selectionKind = if (arguments.allowUncovered) "candidate" else "covered"
    println("Selected ${selected.size} $selectionKind mutation sites from ${planned.size} total sites")
    require(selected.isNotEmpty()) { "No mutation sites selected" }
    selected.map { it.testTask }.distinct().forEach { testTask ->
        val baseline = runner.run(project, testTask, arguments.timeoutSeconds)
        require(!baseline.timedOut && baseline.exitCode == 0) {
            "Baseline failed for $testTask (exit=${baseline.exitCode}, timeout=${baseline.timedOut})\n${baseline.output}"
        }
        println("Baseline passed for $testTask in ${baseline.durationMillis} ms")
    }

    val workspace = Workspace.copyProject(project)
    val exitCode = try {
        val results = selected.mapIndexed { index, site ->
            val result = runMutant(
                workspace,
                project,
                site.source,
                site.site,
                site.testTask,
                arguments.timeoutSeconds,
                runner,
            )
            println("${index + 1}/${selected.size} ${result.status} ${project.relativize(site.source)}:${result.site.line} ${result.site.description} (${result.durationMillis} ms)")
            if (result.status == MutationStatus.FAILED_TO_RUN || result.status == MutationStatus.TIMED_OUT) {
                System.err.println(result.output)
            }
            result
        }
        val killed = results.count { it.status == MutationStatus.KILLED }
        val survived = results.count { it.status == MutationStatus.SURVIVED }
        val timedOut = results.count { it.status == MutationStatus.TIMED_OUT }
        val failed = results.count { it.status == MutationStatus.FAILED_TO_RUN }
        println("Summary: $killed killed, $survived survived, $timedOut timed out, $failed failed to run")
        when {
            timedOut + failed > 0 -> 2
            survived > 0 -> 3
            else -> 0
        }
    } finally {
        deleteRecursively(workspace)
    }
    if (exitCode != 0) kotlin.system.exitProcess(exitCode)
}

private fun printExcludedSources(project: Path, excluded: List<ExcludedSource>, explain: Boolean) {
    if (excluded.isEmpty()) return
    val counts = excluded.groupingBy(ExcludedSource::reason).eachCount()
    println("Excluded ${excluded.size} source files from mutation scope: " + counts.entries.joinToString { "${it.value} ${it.key}" })
    if (explain) {
        excluded.forEach { println("  ${project.relativize(it.path)} — ${it.reason}") }
    } else {
        println("Use --explain-scope to list excluded files")
    }
}

private data class PlannedMutation(
    val source: Path,
    val testTask: String,
    val site: MutationSite,
)

private fun runMutant(
    workspace: Path,
    originalProject: Path,
    originalSource: Path,
    site: MutationSite,
    testTask: String,
    timeoutSeconds: Long,
    runner: ProcessRunner,
): MutationResult {
    val relativeSource = originalProject.relativize(originalSource)
    val mutantSource = workspace.resolve(relativeSource)
    val original = mutantSource.readText()
    require(original.substring(site.startOffset, site.endOffset) == site.originalText) {
        "Mutation span changed in isolated workspace at $relativeSource:${site.line}"
    }
    mutantSource.writeText(
        original.substring(0, site.startOffset) + site.replacementText + original.substring(site.endOffset),
    )
    return try {
        val run = runner.run(workspace, testTask, timeoutSeconds, observeTestFailures = true)
        MutationResult(
            site = site,
            status = when {
                run.timedOut -> MutationStatus.TIMED_OUT
                run.exitCode == 0 -> MutationStatus.SURVIVED
                run.testsFailed -> MutationStatus.KILLED
                else -> MutationStatus.FAILED_TO_RUN
            },
            durationMillis = run.durationMillis,
            output = run.output,
        )
    } finally {
        mutantSource.writeText(original)
    }
}

private fun deleteRecursively(root: Path) {
    if (!Files.exists(root)) return
    Files.walk(root).use { paths ->
        paths.sorted(Comparator.reverseOrder<Path>()).forEach(Files::deleteIfExists)
    }
}
