plugins {
    kotlin("jvm") version "1.8.0"
    `maven-publish`
}

kotlin {
    jvmToolchain(17)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

group = "org.tera201"
version = "1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.slf4j:slf4j-simple:1.7.36")
    implementation("org.xerial:sqlite-jdbc:3.45.3.0")
}

tasks.test {
    useJUnitPlatform()
}