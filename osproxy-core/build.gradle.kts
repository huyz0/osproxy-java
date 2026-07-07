// Zero-I/O vocabulary of the proxy: ids, endpoint kinds, targets, error
// codes, clock. Depends on nothing — every other module builds on this.
plugins {
    id("osproxy.java-conventions")
    id("osproxy.publish-conventions")
}

description = "Zero-I/O vocabulary of the osproxy proxy: ids, endpoint kinds, targets, error codes, clock."

dependencies {
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
}
