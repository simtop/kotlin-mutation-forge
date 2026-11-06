package com.kotlinmutationforge.cli

data class Arguments(
    val project: String,
    val source: String?,
    val all: Boolean,
    val testTask: String?,
    val coverageTask: String?,
    val coverageReport: String?,
    val scanOnly: Boolean,
    val reuseCoverage: Boolean,
    val configFile: String?,
    val includeSupport: Boolean,
    val includePreviews: Boolean,
    val excludePaths: List<String>,
    val excludeModules: List<String>,
    val explainScope: Boolean,
    val allowUncovered: Boolean,
    val maxMutants: Int,
    val timeoutSeconds: Long,
)

fun parseArguments(args: Array<String>): Arguments {
    if (args.contains("--help")) {
        printUsage()
        kotlin.system.exitProcess(0)
    }
    require(args.isNotEmpty()) { "Missing arguments. Use --help for usage." }

    fun value(name: String): String? {
        val index = args.indexOf(name)
        return if (index >= 0 && index + 1 < args.size) args[index + 1] else null
    }

    fun values(name: String): List<String> = args
        .mapIndexedNotNull { index, argument ->
            if (argument == name && index + 1 < args.size) args[index + 1] else null
        }

    val all = args.contains("--all")
    val source = value("--source")
    require(all || source != null) { "Provide --source FILE.kt or --all" }
    require(!all || source == null) { "--all cannot be combined with --source" }

    return Arguments(
        project = value("--project") ?: error("Missing --project"),
        source = source,
        all = all,
        testTask = value("--test-task"),
        coverageTask = value("--coverage-task"),
        coverageReport = value("--coverage-report"),
        scanOnly = args.contains("--scan"),
        reuseCoverage = args.contains("--reuse-coverage"),
        configFile = value("--config"),
        includeSupport = args.contains("--include-support"),
        includePreviews = args.contains("--include-previews"),
        excludePaths = values("--exclude-path"),
        excludeModules = values("--exclude-module"),
        explainScope = args.contains("--explain-scope"),
        allowUncovered = args.contains("--allow-uncovered"),
        maxMutants = value("--max-mutants")?.toIntOrNull()?.coerceAtLeast(1) ?: Int.MAX_VALUE,
        timeoutSeconds = value("--timeout-seconds")?.toLongOrNull()?.coerceAtLeast(1) ?: 300,
    )
}

private fun printUsage() {
    println(
        """
        Kotlin Mutation Forge — Kotlin/KMP mutation testing prototype

        Required:
          --project DIR             Gradle project root
          --source FILE.kt          one production Kotlin source file
          --all                     all production Kotlin source sets, grouped by module
          --test-task TASK          test task for --source mode

        Optional:
          --coverage-task TASK      task that produces JaCoCo XML
          --coverage-report FILE    JaCoCo XML file to read
          --reuse-coverage          do not run --coverage-task
          --config FILE             scope properties (default: .mutation-forge.properties)
          --include-support         include fakes, fixtures, processors, and test utilities
          --include-previews        include Compose preview functions and preview providers
          --exclude-path GLOB       exclude a source path; repeatable
          --exclude-module GLOB     exclude a module path; repeatable
          --explain-scope            print every excluded source and its reason
          --allow-uncovered          mutate modules without JaCoCo XML (exploratory only)
          --scan                    list sites without running tests
          --max-mutants N           cap executions for an experiment
          --timeout-seconds N       timeout for each test run (default: 300)
        """.trimIndent(),
    )
}
