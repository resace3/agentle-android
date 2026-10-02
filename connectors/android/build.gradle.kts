plugins {
    id("agentle.android.library")
    id("agentle.hilt")
}

dependencies {
    api(project(":connectors:api"))
    api(project(":connectors:googlehealth"))
    implementation(project(":core:datastore"))
    implementation(project(":core:common"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.play.services.location)
    implementation(libs.play.services.auth)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.androidx.health.connect.client)
    implementation(libs.androidx.lifecycle.process)
    testImplementation(project(":core:testing"))
    testImplementation(libs.androidx.health.connect.testing)
}
