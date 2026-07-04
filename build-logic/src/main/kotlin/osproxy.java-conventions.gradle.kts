// The one convention every module applies: Java 25, warnings are errors,
// JUnit 5, and a JaCoCo line-coverage floor (the analog of the Rust repo's
// `cargo xtask coverage` gate). Kept deliberately small — module build files
// declare only their dependencies.
plugins {
    `java-library`
    jacoco
}

group = "io.osproxy"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
    // Warnings are defects, as in the Rust workspace's `clippy -D warnings`.
    // `-serial`: Java serialization is not used anywhere in this codebase, so
    // serialVersionUID on every exception would be pure noise.
    options.compilerArgs.addAll(listOf("-Xlint:all,-serial", "-Werror"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
        showStackTraces = true
    }
}

// Coverage floor per module (90% lines), verified as part of `check`. A
// module may relax it by setting `coverageFloor` in its gradle.properties
// (the server does, for its main() wiring which only the e2e exercises).
val coverageFloor = (findProperty("coverageFloor") as String?) ?: "0.90"
tasks.jacocoTestCoverageVerification {
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = coverageFloor.toBigDecimal()
            }
        }
    }
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
}
