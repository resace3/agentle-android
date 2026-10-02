plugins {
    id("agentle.jvm.library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":jitai:dsl"))
    api(project(":analytics:features"))
    testImplementation(project(":core:testing"))
    testImplementation(project(":fakes"))
}
