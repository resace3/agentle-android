plugins {
    id("agentle.android.library")
    id("agentle.hilt")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":connectors:api"))
    api(project(":connectors:googlehealth"))
    implementation(project(":core:datastore"))
    implementation(project(":core:common"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.play.services.location)
    // Recording API (LocalRecordingClient) for on-device step counts (docs/research/03, ARCHITECTURE §6.4 "Steps").
    implementation(libs.play.services.fitness)
    implementation(libs.play.services.auth)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.androidx.health.connect.client)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(project(":core:testing"))
    testImplementation(libs.androidx.health.connect.testing)
}
