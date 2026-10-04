plugins {
    id("agentle.jvm.library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":ai:api"))
    api(project(":analytics:features"))
    api(project(":connectors:api"))
    api(project(":core:model"))
    api(project(":core:time"))
    testImplementation(project(":core:testing"))
    testImplementation(project(":fakes"))
}
