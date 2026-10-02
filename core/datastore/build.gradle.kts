plugins {
    id("agentle.android.library")
    id("agentle.hilt")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:time"))
    api(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(project(":core:testing"))
}
