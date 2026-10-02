plugins {
    id("agentle.android.feature")
}

dependencies {
    implementation(project(":core:time"))
    implementation(libs.androidx.activity.compose)
    testImplementation(libs.compose.ui.test.junit4.accessibility)
}
