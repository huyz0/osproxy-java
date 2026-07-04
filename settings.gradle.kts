// osproxy-java: a Java port of osproxy (multi-tenant OpenSearch proxy).
// Gradle multi-project mirroring the Rust workspace's crate layout; the
// dependency DAG between modules is downward-only and enforced by an
// ArchUnit test in each module (the analog of `cargo xtask arch`).
rootProject.name = "osproxy-java"

pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include(
    "osproxy-core",
    "osproxy-spi",
    "osproxy-rewrite",
    "osproxy-tenancy",
    "osproxy-sink",
    "osproxy-observe",
    "osproxy-capture",
    "osproxy-engine",
    "osproxy-config",
    "osproxy-server",
    "osproxy-jmh",
)
