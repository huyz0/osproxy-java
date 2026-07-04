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

// main() wiring is exercised by the e2e integration test, not unit tests, so
// the server module carries a lower unit-coverage floor than the libraries.
tasks.jacocoTestCoverageVerification {
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.50".toBigDecimal()
            }
        }
    }
}
