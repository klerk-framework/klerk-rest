import org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode

plugins {
    kotlin("jvm") version "2.4.10"
    `maven-publish`
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
}

val klerkVersion = "1.0.0-beta.7-SNAPSHOT"
val ktorVersion = "3.5.0" // Keep in sync with ktor-bom in klerk-bom.

group = "dev.klerkframework"
version = "0.1.0-SNAPSHOT"

dependencies {
    // Also provides kotlinx-coroutines and kotlinx-serialization-json, in the versions Klerk uses.
    compileOnly("dev.klerkframework:klerk:$klerkVersion")
    compileOnly("io.ktor:ktor-server-core-jvm:$ktorVersion")

    implementation("org.slf4j:slf4j-api:${property("slf4j_version")}")

    testImplementation(kotlin("test"))
    testImplementation("dev.klerkframework:klerk:$klerkVersion")
    testImplementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testRuntimeOnly("org.slf4j:slf4j-simple:${property("slf4j_version")}")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
    explicitApi = ExplicitApiMode.Strict
}

publishing {
    publications {
        create<MavenPublication>("Maven") {
            artifactId = "klerk-rest"
            from(components["java"])
        }
    }
}

java {
    withSourcesJar()
}
