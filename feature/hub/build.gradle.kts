plugins {
    id("agentle.android.feature")
}

dependencies {
    api(project(":core:time"))
    implementation(project(":connectors:api"))
    api(project(":analytics:features"))
    api(project(":ai:api"))
    implementation(libs.androidx.activity.compose)
    api(libs.androidx.paging.compose)
    testImplementation(libs.androidx.paging.testing)
    testImplementation(libs.compose.ui.test.junit4.accessibility)
}
