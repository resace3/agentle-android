plugins {
    id("agentle.android.library")
    id("agentle.hilt")
}

dependencies {
    implementation(project(":core:common"))
    implementation(libs.tink.android)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.core.ktx)
    testImplementation(project(":core:testing"))
}
