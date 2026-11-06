plugins {
    id("org.jetbrains.kotlin.jvm")
    id("application")
}

version = providers.gradleProperty("releaseVersion").orElse("0.1.0-SNAPSHOT").get()

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.10")
    testImplementation(kotlin("test"))
}

application {
    applicationName = rootProject.name
    mainClass.set("com.kotlinmutationforge.cli.MainKt")
}

tasks.test {
    useJUnitPlatform()
}

distributions {
    main {
        contents {
            from(rootProject.file("LICENSE"))
        }
    }
}
