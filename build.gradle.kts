plugins {
    id("java-library")
    alias(libs.plugins.jmh)
}

group = "dev.simplified"
version = "1.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
    maven(url = "https://jitpack.io")
}

dependencies {
    // Simplified Collections
    api("com.github.simplified-dev:collections:master-SNAPSHOT")

    // JetBrains Annotations
    api(libs.annotations)

    // Logging
    api(libs.log4j2.api)

    // Lombok Annotations
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)

    // Tests
    testImplementation(libs.hamcrest)
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation(libs.junit.platform.launcher)

    // Benchmarks (JMH)
    jmh(libs.jmh.core)
    jmh(libs.jmh.generator)
}

tasks {
    test {
        useJUnitPlatform {
            excludeTags("slow")
        }
    }
    register<Test>("slowTest") {
        description = "Runs slow integration tests (shutdown, thread leak detection)"
        group = "verification"
        testClassesDirs = sourceSets["test"].output.classesDirs
        classpath = sourceSets["test"].runtimeClasspath
        useJUnitPlatform {
            includeTags("slow")
        }
    }
}
