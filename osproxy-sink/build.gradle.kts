// The write/read destination seam: Sink + Reader interfaces, WriteBatch
// vocabulary, MemorySink for tests, OpenSearchSink over Helidon WebClient.
plugins {
    id("osproxy.java-conventions")
}

dependencies {
    api(project(":osproxy-core"))
    api(project(":osproxy-spi"))
    implementation(libs.helidon.webclient)
    implementation(libs.jackson.databind)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation(libs.helidon.webserver)
    testRuntimeOnly(libs.junit.platform.launcher)
}
