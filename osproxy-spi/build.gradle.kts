// The public SPI a user implements: TenancySpi/RoutingSpi, RequestCtx,
// Placement, the sealed SpiException taxonomy. Depends only on core (plus
// Jackson for body access in RequestCtx), like the Rust crate depends only
// on osproxy-core + serde_json.
plugins {
    id("osproxy.java-conventions")
}

dependencies {
    api(project(":osproxy-core"))
    api(libs.jackson.databind)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
}
