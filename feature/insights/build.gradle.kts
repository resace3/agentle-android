plugins {
    id("agentle.android.feature")
}

// The UI is built against ports this module owns (ui-common brief): no `:data` dependency in this wave.
dependencies {
    implementation(project(":jitai:dsl"))
    implementation(project(":analytics:features"))
    implementation(project(":ai:api"))
    implementation(libs.kotlinx.datetime)

    testImplementation(libs.compose.ui.test.junit4.accessibility)
}
