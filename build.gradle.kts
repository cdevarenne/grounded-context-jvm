// A grounded, composable, deterministic-where-it-matters context layer for LLM agents.
// The core module deliberately carries no framework: the deterministic path is the guaranteed
// deliverable and must run with nothing but a YAML parser.

plugins {
    // The version lives here so it is declared once; the plugin is applied only by the module
    // that produces the executable jar.
    alias(libs.plugins.spring.boot) apply false
}

subprojects {
    apply(plugin = "java")

    group = "io.github.cdevarenne"
    version = "0.1.0-SNAPSHOT"

    tasks.withType<JavaCompile>().configureEach {
        // The floor a consumer must clear, not the JDK this is developed on. Kept below the
        // development JDK on purpose so a reviewer on an older LTS can still build. This is
        // `--release`, not a toolchain: pinning a toolchain would make the JDK a property of
        // the build rather than a floor.
        options.release = 21
        options.encoding = "UTF-8"
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            // Gradle otherwise only links to the HTML report. The drift guards in this repo
            // exist to say what diverged, so the message has to reach the terminal the way
            // Surefire's does. The HTML report is still written; see the README.
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}
