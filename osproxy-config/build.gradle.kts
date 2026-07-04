// Typed configuration: load application.yaml via Helidon Config with
// OSPROXY_* env overrides, validate fast-fail. No business logic.
plugins {
    id("osproxy.java-conventions")
}

dependencies {
    api(project(":osproxy-core"))
    implementation(libs.helidon.config)
    runtimeOnly(libs.helidon.config.yaml)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
}
