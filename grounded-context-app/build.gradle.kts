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

    // Reading the telemetry log back; declared so the use is not a transitive accident.
    implementation(libs.jackson.databind)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.spring.boot.starter.test)
    // See the core module: Gradle 9 needs the launcher, Surefire supplies its own.
    testRuntimeOnly(libs.junit.platform.launcher)
}

springBoot {
    mainClass.set("io.github.cdevarenne.gctx.app.GroundedContextApplication")
}

tasks.bootJar {
    // The CLI is the product; `gctx` is what the README tells people to run.
    archiveFileName.set("gctx.jar")
}
