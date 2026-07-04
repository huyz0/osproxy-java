// The executable: Helidon SE WebServer on virtual threads, bearer auth,
// reference tenancy, and the main() that wires every module together. The
// only module allowed to depend on all the others; also hosts the ArchUnit
// test that enforces the whole downward-only module DAG.
plugins {
    id("osproxy.java-conventions")
    application
}

application {
    mainClass = "io.osproxy.server.Main"
    // Generational ZGC: pause-free collection and a flat memory profile under
    // sustained proxy load (the performance posture from the plan).
    applicationDefaultJvmArgs = listOf("-XX:+UseZGC", "-XX:+ZGenerational")
}

dependencies {
    implementation(project(":osproxy-core"))
    implementation(project(":osproxy-spi"))
    implementation(project(":osproxy-tenancy"))
    implementation(project(":osproxy-sink"))
    implementation(project(":osproxy-engine"))
    implementation(project(":osproxy-config"))
    implementation(project(":osproxy-rewrite"))
    implementation(libs.helidon.webserver)
    implementation(libs.jackson.databind)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation(libs.archunit)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit)
    // Docker engine 29+ refuses docker-java 3.4's pinned API version (1.32,
    // min is now 1.40) with a 400; 3.5 negotiates a supported version.
    testImplementation(platform("com.github.docker-java:docker-java-bom:3.5.1"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Integration tests (Testcontainers vs a real OpenSearch) are tagged and
// excluded from the default `test`/`check` run; opt in with
// `./gradlew :osproxy-server:test -PincludeIntegration`.
tasks.test {
    if (!project.hasProperty("includeIntegration")) {
        useJUnitPlatform {
            excludeTags("integration")
        }
    }
}
