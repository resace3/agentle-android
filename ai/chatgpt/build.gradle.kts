plugins {
    id("agentle.jvm.library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":ai:api"))
    api(project(":core:oauth"))
    api(project(":core:network"))
    implementation(libs.okhttp.sse)
    implementation(libs.nimbus.jose.jwt)
    testImplementation(project(":fakes"))
    testImplementation(project(":core:testing"))
    testImplementation(libs.okhttp.mockwebserver3)
    testImplementation(libs.okhttp.mockwebserver3.junit5)
}
