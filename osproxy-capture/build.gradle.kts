// Traffic capture and queue-producer seams (the Rust osproxy-capture +
// osproxy-kafka analog). No broker client here — the default build links
// nothing; a real producer implements AckProducer in the deployer's own
// artifact.
plugins {
    id("osproxy.java-conventions")
    id("osproxy.publish-conventions")
}

description = "Traffic capture and queue-producer seams for osproxy-java."

dependencies {
    api(project(":osproxy-core"))

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
}
