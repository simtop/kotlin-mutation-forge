package com.kotlinmutationforge.cli

import com.kotlinmutationforge.core.MutationSite
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.K1Deprecation
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPrefixExpression
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.psiUtil.parents
import java.nio.file.Path
import kotlin.io.path.readText

class KotlinSourceAnalyzer {
    fun discover(file: Path, includePreviews: Boolean = false): List<MutationSite> {
        val source = file.readText()
        val disposable = Disposer.newDisposable("kotlin-mutation-forge")
        return try {
            val environment = environment(disposable)
            val psiFile = KtPsiFactory(environment.project, false).createFile(file.fileName.toString(), source)
            val sites = mutableListOf<MutationSite>()
            psiFile.accept(object : KtTreeVisitorVoid() {
                override fun visitBinaryExpression(expression: KtBinaryExpression) {
                    val operation = expression.operationReference
                    val replacement = binaryReplacement(expression.operationToken)
                    if (replacement != null && (includePreviews || !isPreviewCode(expression))) {
                        sites += site(file, source, operation.textRange.startOffset, operation.textRange.endOffset,
                            operation.text, replacement, "replace ${operation.text} with $replacement")
                    }
                    super.visitBinaryExpression(expression)
                }

                override fun visitPrefixExpression(expression: KtPrefixExpression) {
                    val operation = expression.operationReference
                    if (expression.operationToken == KtTokens.EXCL && (includePreviews || !isPreviewCode(expression))) {
                        sites += site(file, source, operation.textRange.startOffset, operation.textRange.endOffset,
                            operation.text, "", "remove unary !")
                    }
                    super.visitPrefixExpression(expression)
                }

                override fun visitConstantExpression(expression: KtConstantExpression) {
                    val replacement = when (expression.text) {
                        "true" -> "false"
                        "false" -> "true"
                        "0" -> "1"
                        "1" -> "0"
                        else -> null
                    }
                    if (replacement != null &&
                        !isEquivalentChannelCapacityMutation(expression) &&
                        (includePreviews || !isPreviewCode(expression))) {
                        sites += site(file, source, expression.textRange.startOffset, expression.textRange.endOffset,
                            expression.text, replacement, "replace ${expression.text} with $replacement")
                    }
                    super.visitConstantExpression(expression)
                }
            })
            sites.sortedWith(compareBy<MutationSite> { it.line }.thenBy { it.startOffset })
        } finally {
            Disposer.dispose(disposable)
        }
    }

    private fun isPreviewCode(expression: KtExpression): Boolean =
        expression.parents.any { parent ->
            when (parent) {
                is KtNamedFunction ->
                    parent.name?.endsWith("Preview") == true ||
                        parent.annotationEntries.any {
                            it.shortName?.asString()?.contains("Preview") == true
                        }
                is KtClass ->
                    parent.name?.contains("PreviewParameterProvider") == true ||
                        parent.superTypeListEntries.any {
                            it.text.contains("PreviewParameterProvider")
                        }
                else -> false
            }
        }

    /**
     * Channel(0, DROP_OLDEST|DROP_LATEST) is normalized to a one-slot channel by kotlinx.coroutines.
     * Mutating an explicit one-slot capacity to zero therefore does not change behavior and is not a
     * useful mutation target.
     */
    private fun isEquivalentChannelCapacityMutation(expression: KtConstantExpression): Boolean {
        if (expression.text != "1") return false
        val call = expression.parents.filterIsInstance<KtCallExpression>().firstOrNull() ?: return false
        val argument = call.valueArguments.firstOrNull {
            it.getArgumentExpression()?.textRange == expression.textRange
        } ?: return false
        if (argument.getArgumentName()?.asName?.asString() != "capacity") return false
        return call.valueArguments.any {
            it.getArgumentName()?.asName?.asString() == "onBufferOverflow" &&
                it.getArgumentExpression()?.text?.substringAfterLast('.') in
                setOf("DROP_OLDEST", "DROP_LATEST")
        }
    }

    @OptIn(CompilerConfiguration.Internals::class, K1Deprecation::class)
    private fun environment(parent: Disposable): KotlinCoreEnvironment {
        val configuration = CompilerConfiguration().apply {
            put(CommonConfigurationKeys.MODULE_NAME, "kotlin-mutation-forge")
            put(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)
        }
        return KotlinCoreEnvironment.createForProduction(parent, configuration, EnvironmentConfigFiles.JVM_CONFIG_FILES)
    }

    private fun binaryReplacement(token: org.jetbrains.kotlin.com.intellij.psi.tree.IElementType): String? = when (token) {
        KtTokens.EQEQ -> "!="
        KtTokens.EXCLEQ -> "=="
        KtTokens.LT -> ">="
        KtTokens.LTEQ -> ">"
        KtTokens.GT -> "<="
        KtTokens.GTEQ -> "<"
        KtTokens.ANDAND -> "||"
        KtTokens.OROR -> "&&"
        KtTokens.PLUS -> "-"
        KtTokens.MINUS -> "+"
        else -> null
    }

    private fun site(
        file: Path,
        source: String,
        start: Int,
        end: Int,
        original: String,
        replacement: String,
        description: String,
    ): MutationSite = MutationSite(
        sourcePath = file.toString(),
        line = source.substring(0, start).count { it == '\n' } + 1,
        startOffset = start,
        endOffset = end,
        originalText = original,
        replacementText = replacement,
        description = description,
    )
}
