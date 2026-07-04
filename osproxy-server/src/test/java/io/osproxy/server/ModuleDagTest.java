package io.osproxy.server;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.library.Architectures;
import org.junit.jupiter.api.Test;

/**
 * Enforces the downward-only module DAG (the analog of the Rust workspace's
 * `cargo xtask arch` gate): each layer may reach only the layers below it,
 * with {@code io.osproxy.core} the I/O-free foundation nothing else may
 * bypass. This test lives in the server module because it is the only module
 * whose test classpath sees every other module.
 */
class ModuleDagTest {

    @Test
    void modulesFormADownwardOnlyDag() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("io.osproxy..");

        // Optional layers: a module with no classes yet simply has nothing to
        // check (ArchUnit does not count package-info as a class).
        Architectures.layeredArchitecture()
                .consideringOnlyDependenciesInAnyPackage("io.osproxy..")
                .withOptionalLayers(true)
                .layer("core").definedBy("io.osproxy.core..")
                .layer("spi").definedBy("io.osproxy.spi..")
                .layer("rewrite").definedBy("io.osproxy.rewrite..")
                .layer("tenancy").definedBy("io.osproxy.tenancy..")
                .layer("sink").definedBy("io.osproxy.sink..")
                .layer("engine").definedBy("io.osproxy.engine..")
                .layer("config").definedBy("io.osproxy.config..")
                .layer("observe").definedBy("io.osproxy.observe..")
                .layer("capture").definedBy("io.osproxy.capture..")
                .layer("otlp").definedBy("io.osproxy.otlp..")
                .layer("kafka").definedBy("io.osproxy.kafka..")
                .layer("server").definedBy("io.osproxy.server..")
                .whereLayer("server").mayNotBeAccessedByAnyLayer()
                .whereLayer("config").mayOnlyBeAccessedByLayers("server")
                .whereLayer("observe").mayOnlyBeAccessedByLayers("otlp", "server")
                .whereLayer("capture").mayOnlyBeAccessedByLayers("kafka", "server")
                .whereLayer("otlp").mayOnlyBeAccessedByLayers("server")
                .whereLayer("kafka").mayOnlyBeAccessedByLayers("server")
                .whereLayer("engine").mayOnlyBeAccessedByLayers("server")
                .whereLayer("sink").mayOnlyBeAccessedByLayers("engine", "server")
                .whereLayer("tenancy").mayOnlyBeAccessedByLayers("engine", "server")
                .whereLayer("rewrite").mayOnlyBeAccessedByLayers("tenancy", "engine", "server")
                .whereLayer("spi").mayOnlyBeAccessedByLayers(
                        "rewrite", "tenancy", "sink", "engine", "server")
                .whereLayer("core").mayOnlyBeAccessedByLayers(
                        "spi", "rewrite", "tenancy", "sink", "engine", "config",
                        "observe", "capture", "otlp", "kafka", "server")
                .check(classes);
    }
}
