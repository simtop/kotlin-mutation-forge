package com.kotlinmutationforge.cli

import java.nio.file.Path
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import kotlin.io.path.exists

class JaCoCoCoverage(private val covered: Set<String>) {
    fun covers(source: Path, line: Int): Boolean {
        val normalized = source.toString().replace('\\', '/')
        val sourceRelative = normalized.substringAfter(
            "/src/main/kotlin/",
            normalized.substringAfter("/src/main/java/", source.fileName.toString()),
        )
        return covered.any { it.endsWith("/$sourceRelative:$line") || it == "$sourceRelative:$line" }
    }

    companion object {
        fun read(report: Path): JaCoCoCoverage {
            require(report.exists()) { "JaCoCo report does not exist: $report" }
            val factory = DocumentBuilderFactory.newInstance().apply {
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", false)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                isXIncludeAware = false
                isExpandEntityReferences = false
            }
            val builder = factory.newDocumentBuilder()
            builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
            val document = builder.parse(report.toFile())
            val result = mutableSetOf<String>()
            val packages = document.getElementsByTagName("package")
            for (packageIndex in 0 until packages.length) {
                val packageElement = packages.item(packageIndex) as org.w3c.dom.Element
                val packageName = packageElement.getAttribute("name")
                val sources = packageElement.getElementsByTagName("sourcefile")
                for (sourceIndex in 0 until sources.length) {
                    val sourceElement = sources.item(sourceIndex) as org.w3c.dom.Element
                    val sourceName = sourceElement.getAttribute("name")
                    val path = if (packageName.isBlank()) sourceName else "$packageName/$sourceName"
                    val lines = sourceElement.getElementsByTagName("line")
                    for (lineIndex in 0 until lines.length) {
                        val line = lines.item(lineIndex) as org.w3c.dom.Element
                        if ((line.getAttribute("ci").toIntOrNull() ?: 0) > 0) {
                            result += "$path:${line.getAttribute("nr")}"
                        }
                    }
                }
            }
            return JaCoCoCoverage(result)
        }
    }
}
