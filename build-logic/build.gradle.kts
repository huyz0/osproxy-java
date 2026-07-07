plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    // Central Portal publishing (signing + POM + upload) for the library
    // modules' `osproxy.publish-conventions` plugin.
    implementation("com.vanniktech:gradle-maven-publish-plugin:0.30.0")
}
