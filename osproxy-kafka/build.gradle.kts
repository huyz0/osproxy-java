// The real Kafka implementation of the AckProducer seam. The one module
// that links a broker client — everything else stays broker-free, so a
// deployment that never uses async writes carries no Kafka code path.
plugins {
    id("osproxy.java-conventions")
}

dependencies {
    api(project(":osproxy-capture"))
    // api, not implementation: javac resolves constructor overloads against
    // every signature on the class, so dependents need the client types on
    // their compile classpath even though the wrapping constructors are
    // package-private.
    api(libs.kafka.clients)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation(libs.awaitility)
    testRuntimeOnly(libs.junit.platform.launcher)
}
