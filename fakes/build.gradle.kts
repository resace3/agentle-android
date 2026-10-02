plugins {
    id("agentle.jvm.library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core:model"))
    api(project(":core:time"))
    api(project(":ai:api"))
    implementation(project(":ai:chatgpt"))
    api(libs.okhttp.mockwebserver3)
    api(libs.kotlinx.serialization.json)
    testImplementation(project(":core:testing"))
    testImplementation(libs.okhttp.mockwebserver3.junit5)
}
