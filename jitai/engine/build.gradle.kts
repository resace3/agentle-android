plugins {
    id("agentle.jvm.library")
    alias(libs.plugins.kotlin.serialization)
    // In-memory fakes of the engine's ports live in src/testFixtures (consumed with testFixtures(project(":jitai:engine"))),
    // so they never reach a production classpath.
    `java-test-fixtures`
}

dependencies {
    api(project(":jitai:dsl"))
    api(project(":analytics:features"))
    api(libs.kotlinx.serialization.json)
    testImplementation(project(":core:testing"))
}

// The fakes are test support: lint them like the rest of the module, but keep them out of the coverage gate.
detekt {
    source.from("src/testFixtures/kotlin")
}

kover {
    currentProject {
        sources {
            excludedSourceSets.add("testFixtures")
        }
    }
}

// testing-build-04: the engine reads zones only from its clock, never the JVM default, so the tests run under a default
// zone that no fixture uses (America/St_Johns, UTC-3:30 with DST).
tasks.withType<Test>().configureEach {
    systemProperty("user.timezone", "America/St_Johns")
}
