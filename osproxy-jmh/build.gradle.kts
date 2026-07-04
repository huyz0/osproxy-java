// Wall-clock + allocation microbenchmarks (JMH with `-prof gc` for
// allocations/op) over the hot transforms — the analog of the Rust repo's
// osproxy-bench. Never a CI gate (wall-clock is host-specific); run locally
// with `./gradlew :osproxy-jmh:jmh`.
plugins {
    id("osproxy.java-conventions")
    id("me.champeau.jmh") version "0.7.3"
}

dependencies {
    jmh(project(":osproxy-rewrite"))
    jmh(project(":osproxy-engine"))
    jmh(project(":osproxy-tenancy"))
    jmh(project(":osproxy-spi"))
    jmh(project(":osproxy-core"))
    jmh(project(":osproxy-sink"))
    jmh(libs.jackson.databind)
}

jmh {
    // Fast local runs by default; raise for a real calibration.
    warmupIterations = 2
    iterations = 3
    fork = 1
    profilers = listOf("gc")
}
