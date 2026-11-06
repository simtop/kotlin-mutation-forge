package com.kotlinmutationforge.cli

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString

data class ProcessResult(
    val exitCode: Int?,
    val timedOut: Boolean,
    val durationMillis: Long,
    val output: String,
    val testsFailed: Boolean,
)

class ProcessRunner {
    fun run(
        project: Path,
        task: String,
        timeoutSeconds: Long,
        observeTestFailures: Boolean = false,
    ): ProcessResult {
        val probe = if (observeTestFailures) Files.createTempDirectory("forge-test-result-") else null
        try {
            val failureMarker = probe?.resolve("failed")
            val initScript = probe?.resolve("observe-tests.init.gradle")?.let {
                Files.writeString(it, testFailureListener)
            }
            val command = buildList {
                add(project.resolve("gradlew").absolutePathString())
                add(task)
                add("--console=plain")
                add("--no-configuration-cache")
                add("--rerun-tasks")
                if (initScript != null) {
                    add("--init-script")
                    add(initScript.absolutePathString())
                }
            }
            val started = System.nanoTime()
            val process = ProcessBuilder(command)
                .directory(project.toFile())
                .redirectErrorStream(true)
                .apply {
                    if (failureMarker != null) {
                        environment()["FORGE_TEST_FAILURE_MARKER"] = failureMarker.absolutePathString()
                    }
                }
                .start()
            val output = CompletableFuture.supplyAsync {
                process.inputStream.readBytes().toString(StandardCharsets.UTF_8)
            }
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) terminateProcessTree(process)
            val capturedOutput = runCatching { output.get(5, TimeUnit.SECONDS) }.getOrDefault("")
            return ProcessResult(
                exitCode = if (finished) process.exitValue() else null,
                timedOut = !finished,
                durationMillis = (System.nanoTime() - started) / 1_000_000,
                output = capturedOutput.takeLast(12_000),
                testsFailed = failureMarker != null && Files.isRegularFile(failureMarker),
            )
        } finally {
            probe?.toFile()?.deleteRecursively()
        }
    }

    private fun terminateProcessTree(process: Process) {
        val handles = process.toHandle().descendants().toList().asReversed()
        handles.forEach { it.destroyForcibly() }
        process.destroyForcibly()
    }
}


private val testFailureListener = """
    allprojects {
        tasks.withType(org.gradle.api.tasks.testing.Test).configureEach { testTask ->
            testTask.afterTest { descriptor, result ->
                if (!testTask.ignoreFailures &&
                        result.resultType == org.gradle.api.tasks.testing.TestResult.ResultType.FAILURE) {
                    new File(System.getenv("FORGE_TEST_FAILURE_MARKER")).createNewFile()
                }
            }
        }
    }
""".trimIndent()
