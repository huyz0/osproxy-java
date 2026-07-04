// Shape-only observability: request metrics, the explain store, structured
// request logs. Depends only on core — never sees tenant values by
// construction (documents carry endpoint kinds, statuses, ids and timings,
// no bodies and no partition values in the wire forms).
plugins {
    id("osproxy.java-conventions")
}

dependencies {
    api(project(":osproxy-core"))

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
}
