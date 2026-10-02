plugins {
    id("agentle.jvm.library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core:model"))
    api(project(":core:common"))
    api(project(":core:time"))
    testImplementation(project(":core:testing"))
    testImplementation(project(":fakes"))
}
