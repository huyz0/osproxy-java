// Typed configuration: load application.yaml via Helidon Config with
// OSPROXY_* env overrides, validate fast-fail. No business logic.
plugins {
    id("osproxy.java-conventions")
    id("osproxy.publish-conventions")
}

description = "Typed configuration loading and validation for osproxy-java."

dependencies {
    api(project(":osproxy-core"))
    implementation(libs.helidon.config)
    runtimeOnly(libs.helidon.config.yaml)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
}
