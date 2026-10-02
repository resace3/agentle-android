plugins {
    id("agentle.android.feature")
}

// The settings screens are built against ports this module owns (package dev.agentle.feature.settings.port); the
// wiring team implements them over :data and the platform later, so there is deliberately no :data dependency here.
dependencies {
    // Shared AI contract: AiProviderState for the ChatGPT state shown by Privacy and Diagnostics.
    implementation(project(":ai:api"))
    // Accessibility Test Framework checks in Compose UI tests (docs/research/08 s8.5).
    testImplementation(libs.compose.ui.test.junit4.accessibility)
}
