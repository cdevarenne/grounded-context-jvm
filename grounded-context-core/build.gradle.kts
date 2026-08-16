// Bundle, lookup, router, provenance. One compile dependency — a YAML parser. No Spring, no HTTP
// client, no Elasticsearch: this module is the zero-cloud-dependency guarantee expressed as a
// build constraint rather than a promise in a README.
//
//   $ ./gradlew :grounded-context-core:dependencies --configuration compileClasspath

description = "grounded-context core (no framework)"

dependencies {
    implementation(libs.snakeyaml)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    // TestCountRecorder is a launcher listener, and the launcher is not on the test classpath
    // by default.
    testImplementation(libs.junit.platform.launcher)
}
