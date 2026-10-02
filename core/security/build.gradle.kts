plugins {
    id("agentle.android.library")
    id("agentle.hilt")
}

dependencies {
    implementation(project(":core:common"))
    api(project(":core:time"))
    implementation(libs.tink.android)
    implementation(libs.androidx.core.ktx)
    testImplementation(project(":core:testing"))
}
