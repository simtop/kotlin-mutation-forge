package com.kotlinmutationforge.cli

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KotlinSourceAnalyzerTest {
    @Test
    fun discoversOperatorsWithoutTreatingStringsAsSites() {
        val file = Files.createTempFile("mutation", ".kt")
        file.toFile().deleteOnExit()
        file.toFile().writeText(
            """
            fun classify(value: Int): Boolean {
                val text = "a == b"
                return value > 0 && text.isNotEmpty()
            }
            """.trimIndent(),
        )

        val sites = KotlinSourceAnalyzer().discover(file)

        assertEquals(listOf(">", "0", "&&"), sites.map { it.originalText })
        assertTrue(sites.all { it.description.isNotBlank() })
    }

    @Test
    fun excludes_preview_functions_and_parameter_providers_by_default() {
        val file = Files.createTempFile("mutation", ".kt")
        file.toFile().deleteOnExit()
        file.toFile().writeText(
            """
            annotation class Preview

            class SamplePreviewParameterProvider {
                val values = listOf(true, 0)
            }

            @Preview
            fun samplePreview() = true && false

            fun production(value: Int) = value > 0
            """.trimIndent(),
        )

        val sites = KotlinSourceAnalyzer().discover(file)

        assertEquals(listOf(">", "0"), sites.map { it.originalText })
    }

    @Test
    fun can_include_preview_mutations_explicitly() {
        val file = Files.createTempFile("mutation", ".kt")
        file.toFile().deleteOnExit()
        file.toFile().writeText("fun samplePreview() = true")

        val sites = KotlinSourceAnalyzer().discover(file, includePreviews = true)

        assertEquals(listOf("true"), sites.map { it.originalText })
    }

    @Test
    fun ignores_channel_capacity_mutation_that_is_normalized_by_overflow_policy() {
        val file = Files.createTempFile("mutation", ".kt")
        file.toFile().deleteOnExit()
        file.toFile().writeText(
            """
            fun buffered() {
                Channel<Int>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
            }
            """.trimIndent(),
        )

        val sites = KotlinSourceAnalyzer().discover(file)

        assertTrue(sites.isEmpty())
    }

    @Test
    fun keeps_channel_capacity_mutation_when_overflow_policy_does_not_normalize_it() {
        val file = Files.createTempFile("mutation", ".kt")
        file.toFile().deleteOnExit()
        file.toFile().writeText(
            "fun rendezvous() { Channel<Int>(capacity = 1, onBufferOverflow = BufferOverflow.SUSPEND) }",
        )

        val sites = KotlinSourceAnalyzer().discover(file)

        assertEquals(listOf("1"), sites.map { it.originalText })
    }
}
