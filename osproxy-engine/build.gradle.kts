// Pipeline orchestration: classify → resolve → rewrite → dispatch → shape.
// Transport-free (the server module owns the HTTP ingress); everything here
// is testable with a MemorySink and an injected TenancySpi.
plugins {
    id("osproxy.java-conventions")
}

dependencies {
    api(project(":osproxy-core"))
    api(project(":osproxy-spi"))
    api(project(":osproxy-sink"))
    implementation(project(":osproxy-tenancy"))
    implementation(project(":osproxy-rewrite"))
    implementation(libs.jackson.databind)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
}
