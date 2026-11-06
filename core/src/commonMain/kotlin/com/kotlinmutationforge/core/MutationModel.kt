package com.kotlinmutationforge.core

enum class MutationStatus {
    SURVIVED,
    KILLED,
    TIMED_OUT,
    FAILED_TO_RUN,
    UNCOVERED,
}

data class MutationSite(
    val sourcePath: String,
    val line: Int,
    val startOffset: Int,
    val endOffset: Int,
    val originalText: String,
    val replacementText: String,
    val description: String,
)

data class MutationResult(
    val site: MutationSite,
    val status: MutationStatus,
    val durationMillis: Long,
    val output: String = "",
)
