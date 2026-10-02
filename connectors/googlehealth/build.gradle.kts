plugins {
    id("agentle.jvm.library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":connectors:api"))
    api(project(":core:network"))
    api(project(":core:oauth"))
    testImplementation(project(":fakes"))
    testImplementation(project(":core:testing"))
    testImplementation(libs.okhttp.mockwebserver3)
    testImplementation(libs.okhttp.mockwebserver3.junit5)
}
