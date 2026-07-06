// The executable: Helidon SE WebServer on virtual threads, bearer auth,
// reference tenancy, and the main() that wires every module together. The
// only module allowed to depend on all the others; also hosts the ArchUnit
// test that enforces the whole downward-only module DAG.
plugins {
    id("osproxy.java-conventions")
    application
    id("com.google.protobuf") version "0.10.0"
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
    implementation(project(":osproxy-observe"))
    implementation(project(":osproxy-capture"))
    implementation(project(":osproxy-otlp"))
    implementation(project(":osproxy-kafka"))
    implementation(project(":osproxy-rewrite"))
    implementation(libs.helidon.webserver)
    // HTTP/2 self-registers via ServiceLoader (Http2ConnectionProvider +
    // an Http1UpgradeProvider for h2c): on the classpath is on, negotiated
    // alongside HTTP/1.1 on the same port, no code change needed.
    implementation(libs.helidon.webserver.http2)
    // gRPC ingress: its own protocol handler over the same HTTP/2 stack
    // above, so it shares the WebServer's port and TLS config with REST.
    implementation(libs.helidon.webserver.grpc)
    implementation(libs.grpc.stub)
    implementation(libs.grpc.protobuf)
    implementation(libs.protobuf.java)
    compileOnly(libs.javax.annotation.api)
    // The FIPS 140-3 validated JCE module (CMVP cert on the BC-FIPS 2.1 line).
    // Bundled but dormant: it is registered only when `osproxy.fips` is set.
    implementation(libs.bc.fips)
    implementation(libs.jackson.databind)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation(libs.archunit)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.kafka.clients)
    testImplementation(libs.helidon.webclient.http2)
    // A real (client-channel) gRPC stub to drive GrpcDocumentServiceTest;
    // netty-shaded is the plain-cleartext client transport grpc-java tests
    // normally use and is independent of Helidon's own server-side stack.
    testImplementation(libs.grpc.netty.shaded)
    testImplementation(project(":osproxy-jmh"))
    // Docker engine 29+ refuses docker-java 3.4's pinned API version (1.32,
    // min is now 1.40) with a 400; 3.5 negotiates a supported version.
    testImplementation(platform("com.github.docker-java:docker-java-bom:3.5.1"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Integration tests (Testcontainers vs a real OpenSearch) are tagged and
// excluded from the default `test`/`check` run; opt in with
// `./gradlew :osproxy-server:test -PincludeIntegration`.
tasks.test {
    useJUnitPlatform {
        // fips engagement mutates the JVM's provider order; it runs in its
        // own forked JVM (the fipsTest task), never with the main suite.
        if (project.hasProperty("includeIntegration")) {
            excludeTags("fips")
        } else {
            excludeTags("integration", "fips")
        }
    }
}

// The one fips-tagged test in a fresh JVM, part of `check`.
val fipsTest = tasks.register<Test>("fipsTest") {
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("fips")
    }
    forkEvery = 1
}

tasks.check {
    dependsOn(fipsTest)
}

// Compiles proto/osproxy.proto into the DocumentService server stub + message
// types; protoc and the grpc-java codegen plugin are fetched by the plugin
// itself (no protoc install required on the build host).
protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:${libs.versions.grpc.get()}"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                create("grpc")
            }
        }
    }
}

// Generated gRPC/protobuf code carries no hand-authored logic to unit-test
// and would otherwise drag the module's coverage average down; excluded the
// same way the Rust sibling excludes its own generated `pb` module.
tasks.withType<JacocoReport>().configureEach {
    classDirectories.setFrom(
        classDirectories.files.map { fileTree(it) { exclude("io/osproxy/server/grpc/pb/**") } }
    )
}
tasks.withType<JacocoCoverageVerification>().configureEach {
    classDirectories.setFrom(
        classDirectories.files.map { fileTree(it) { exclude("io/osproxy/server/grpc/pb/**") } }
    )
}
