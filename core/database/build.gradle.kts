plugins {
    id("agentle.android.library")
    id("agentle.room")
    id("agentle.hilt")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core:model"))
    api(project(":core:time"))
    implementation(project(":core:common"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(project(":core:testing"))
}
