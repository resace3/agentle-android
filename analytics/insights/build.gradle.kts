plugins {
    id("agentle.jvm.library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":analytics:features"))
    api(project(":ai:api"))
    api(project(":ai:context"))
    testImplementation(project(":core:testing"))
    testImplementation(project(":fakes"))
}
