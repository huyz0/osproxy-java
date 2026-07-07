// OTLP/HTTP JSON span export: a pure encoder plus a fire-and-forget POST to
// {endpoint}/v1/traces over the JDK HttpClient. A leaf adapter — nothing
// depends upward on it, and it never fails a request.
plugins {
    id("osproxy.java-conventions")
    id("osproxy.publish-conventions")
}

description = "OTLP/HTTP JSON span and metrics export for osproxy-java."

dependencies {
    api(project(":osproxy-observe"))
    implementation(libs.jackson.databind)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation(libs.helidon.webserver)
    testImplementation(libs.awaitility)
    testRuntimeOnly(libs.junit.platform.launcher)
}
