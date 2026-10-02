plugins {
    id("agentle.jvm.library")
    alias(libs.plugins.kotlin.serialization)
}

// Shared by two teams: dev.agentle.fakes.chatgpt/.browser (Sign in with ChatGPT) and
// dev.agentle.fakes.googlehealth/.synth (Google Health). The fakes never depend on the clients they fake
// (no :connectors:googlehealth), so contract tests compare two independent implementations.
dependencies {
    api(project(":core:model"))
    api(project(":core:common"))
    api(project(":core:time"))
    api(project(":ai:api"))
    implementation(project(":ai:chatgpt"))
    implementation(project(":core:network"))
    implementation(libs.nimbus.jose.jwt)
    api(libs.okhttp.mockwebserver3)
    api(libs.kotlinx.serialization.json)
    testImplementation(project(":core:testing"))
    testImplementation(libs.okhttp.mockwebserver3.junit5)
}
