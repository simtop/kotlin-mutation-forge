# Kotlin Mutation Forge

Kotlin Mutation Forge is a Kotlin/JVM prototype for mutation testing in Kotlin, Android, and
Kotlin Multiplatform projects.

## Install

Download the versioned ZIP from [GitHub Releases](https://github.com/simtop/kotlin-mutation-forge/releases).
Unpack it and run `bin/kotlin-mutation-forge --help` with Java 17 or later. On Windows, use the `.bat` launcher.

To build from source, run `./gradlew :cli:test :core:jvmTest :cli:installDist`.
The launcher is in `cli/build/install/kotlin-mutation-forge/bin`.

## Single file

```bash
kotlin-mutation-forge \
  --project /path/to/project \
  --source core-common/src/main/kotlin/example/Logic.kt \
  --test-task :core-common:test \
  --coverage-task :core-common:jacocoTestReport \
  --coverage-report core-common/build/reports/jacoco/test/jacocoTestReport.xml
```

## Whole project

```bash
kotlin-mutation-forge \
  --project /path/to/project \
  --all \
  --max-mutants 100
```

`--all` discovers production Kotlin in `src/main/kotlin`, `src/main/java`, and KMP `*Main/kotlin`
source sets, excludes tests/build infrastructure and (by default) obvious support code such as
fakes, fixtures, processors, testing utilities, and generated sources, groups files by their nearest
Gradle subproject, runs each module’s JVM coverage and test task, and executes mutants in a copied
workspace. Use `--include-support` when those modules are part of the quality target.
Use `--scan` to discover and count mutation sites without running Gradle or mutating anything.

Scope can be customized in `.mutation-forge.properties` at the project root:

```properties
# Comma-separated glob patterns, relative to the project root.
excludePath=**/presentation/**,**/*Preview.kt,**/*Demo.kt
excludeModule=**/catalog/**
# Set these to true when support or preview code is part of the quality target.
includeSupport=false
includePreviews=false
```

The same settings can be supplied with repeatable `--exclude-path` and `--exclude-module` options.
Use `--config FILE` for another properties file, `--include-support` to restore the conservative
support-code defaults, and `--explain-scope` to print every excluded source with its reason. Scope
exclusions are reported separately from mutation survivors so an exclusion cannot be mistaken for a
killed mutant or hidden coverage.

Compose preview functions (and classes extending `PreviewParameterProvider`) are also excluded by
default because their sample values are not application behavior. Use `--include-previews` when
preview rendering is an explicit quality target. Path patterns remain available for other UI-only
or demo code.

Mutation runs require a JaCoCo XML report for each module by default. If the report is missing, the
module is skipped because Forge cannot establish that its mutation sites are executed by tests. Use
`--allow-uncovered` only for exploratory runs; those sites are labeled candidates, not covered
mutants, and should not be used as a mutation score.

Whole-project mutation can be expensive. Use `--max-mutants` for an initial sample; the cap is
global across modules and sites are selected in deterministic module/source order.

## Exit status

- `0` means all selected mutations were killed.
- `2` means a build failed before recording a failed test or a mutation timed out.
- `3` means a mutation survived and all test runs completed.

A mutation is killed when Gradle exits unsuccessfully and reports a failed JVM test.

## License

Copyright 2026 Simon Topchyan. Licensed under [Apache 2.0](LICENSE).
