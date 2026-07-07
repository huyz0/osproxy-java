// Pure request/response transforms (inject/strip/id-map/demux/wrap). No I/O;
// Jackson streaming on the hot paths. The most heavily property-tested module.
plugins {
    id("osproxy.java-conventions")
    id("osproxy.publish-conventions")
}

description = "Pure request/response transforms (inject/strip/id-map/demux/wrap) for osproxy-java."

dependencies {
    api(project(":osproxy-core"))
    implementation(libs.jackson.core)
    implementation(libs.jackson.databind)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation(libs.jqwik)
    testRuntimeOnly(libs.junit.platform.launcher)
}
