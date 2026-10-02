plugins {
    id("agentle.android.library")
    id("agentle.hilt")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core:model"))
    api(project(":core:time"))
    api(libs.kotlinx.coroutines.core)
    implementation(project(":core:common"))
    // Install id (consent grants are bound to this install) and the noBackup directory.
    implementation(project(":core:security"))
    // Typed (kotlinx.serialization) stores; the untyped Preferences API is not used.
    implementation(libs.androidx.datastore)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(project(":core:testing"))
}
