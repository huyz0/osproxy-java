// Shape-only observability: request metrics, the explain store, structured
// request logs. Depends only on core — never sees tenant values by
// construction (documents carry endpoint kinds, statuses, ids and timings,
// no bodies and no partition values in the wire forms). TenantMetrics is the
// one deliberate exception (opt-in, bounded by construction — see its
// javadoc), which is why this module also needs Caffeine.
plugins {
    id("osproxy.java-conventions")
}

dependencies {
    api(project(":osproxy-core"))
    implementation(libs.caffeine)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
}
