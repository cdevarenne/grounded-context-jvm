// Everything the core deliberately excludes: the CLI, the Elasticsearch hybrid path, the MCP
// server, the eval harness. Depends on the core; the core never depends on this.

plugins {
    alias(libs.plugins.spring.boot)
}

description = "grounded-context app (Spring Boot)"

dependencies {
    implementation(project(":grounded-context-core"))

    implementation(platform(libs.spring.boot.bom))
    implementation(libs.spring.boot.starter)
    implementation(libs.elasticsearch.java)
    implementation(libs.picocli.spring.boot.starter)

    // The MCP surface. Same protocol version as the Python server's `mcp>=2.0.0`, so both
    // implementations answer the same client with the same tool contract.
    implementation(libs.mcp.core)
    implementation(libs.mcp.json.jackson2)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.junit.platform.launcher)
}

springBoot {
    mainClass.set("io.github.cdevarenne.gctx.app.GroundedContextApplication")
}

tasks.bootJar {
    // The CLI is the product; `gctx` is what the README tells people to run.
    archiveFileName.set("gctx.jar")
}

// Fails the build when the README's test count no longer matches what ran. It is not a JUnit
// test, because a test cannot know its own run's final total while that run is in progress.
val checkReadmeTestCount = tasks.register<JavaExec>("checkReadmeTestCount") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Checks the README test count against what the build actually ran."
    mainClass.set("io.github.cdevarenne.gctx.app.ReadmeCountCheck")
    classpath = sourceSets.test.get().runtimeClasspath
    // It reads both modules' counts, so both test runs have to have happened. Maven got this
    // from reactor ordering; here it is stated.
    dependsOn(tasks.test, ":grounded-context-core:test")
    // The module directory arrives as an argument rather than being inferred from the working
    // directory, matching the Maven build and the class's own contract.
    args(projectDir.absolutePath)
    onlyIf {
        // `--tests` deselects rather than skips, so no skip event reaches TestCountRecorder and
        // a narrowed run reads as a shrunken suite. The recorder cannot tell the difference; the
        // invocation can.
        gradle.startParameter.taskRequests.none { "--tests" in it.args }
    }
}

tasks.check {
    dependsOn(checkReadmeTestCount)
}
