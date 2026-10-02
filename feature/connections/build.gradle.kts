plugins {
    id("agentle.android.feature")
}

dependencies {
    implementation(project(":data"))
    implementation(libs.androidx.browser)
}
