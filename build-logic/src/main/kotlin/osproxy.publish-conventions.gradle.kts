// Maven Central (Central Portal) publishing for a library module. Applied
// only by the modules an integrator actually depends on — not
// `osproxy-server` (the reference binary) or `osproxy-jmh` (benchmarks).
// Requires `mavenCentralUsername`/`mavenCentralPassword` (a Central Portal
// user token) and a GPG key (`signing.keyId`/`signing.password` +
// `signing.secretKeyRingFile`, or the `ORG_GRADLE_PROJECT_signingInMemoryKey`
// env-var form) — see README.md's "Releasing" section.
import com.vanniktech.maven.publish.SonatypeHost

plugins {
    id("com.vanniktech.maven.publish")
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()

    pom {
        name.set(project.name)
        description.set(
            project.description
                ?: "osproxy-java: a Java port of the osproxy multi-tenant OpenSearch routing proxy."
        )
        url.set("https://github.com/huyz0/osproxy-java")

        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("huyz0")
                name.set("Huy Nguyen")
            }
        }
        scm {
            url.set("https://github.com/huyz0/osproxy-java")
            connection.set("scm:git:https://github.com/huyz0/osproxy-java.git")
            developerConnection.set("scm:git:ssh://git@github.com/huyz0/osproxy-java.git")
        }
    }
}
