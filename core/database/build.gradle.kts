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
    // Encryption at rest (docs/ARCHITECTURE.md §5.4): SQLCipherDriver for Room 3; the key comes from :core:security.
    implementation(libs.sqlcipher.android)
    implementation(project(":core:security"))
    testImplementation(project(":core:testing"))
}
