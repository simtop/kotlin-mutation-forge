package com.kotlinmutationforge.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.name

object Workspace {
    private val excludedSegments = setOf(".git", ".gradle", "build", "rod", "gradle-user-home")

    fun copyProject(source: Path): Path {
        val destination = Files.createTempDirectory("kotlin-mutation-forge-")
        Files.walk(source).use { paths ->
            paths.forEach { path ->
                val relative = source.relativize(path)
                val excluded = relative.any { it.name in excludedSegments }
                if (excluded) return@forEach
                val target = destination.resolve(relative.toString())
                if (path.isDirectory()) Files.createDirectories(target) else Files.copy(path, target)
            }
        }
        return destination
    }
}
