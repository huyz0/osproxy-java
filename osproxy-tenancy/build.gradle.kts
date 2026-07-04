// Adapts a user's TenancySpi into the routing decision the engine consumes:
// TenancyRouter, PlacementTable, partition-spec resolution, fail-closed
// isolation checks.
plugins {
    id("osproxy.java-conventions")
}

dependencies {
    api(project(":osproxy-core"))
    api(project(":osproxy-spi"))
    implementation(project(":osproxy-rewrite"))

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
}
