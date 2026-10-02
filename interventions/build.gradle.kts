plugins {
    id("agentle.android.library")
    id("agentle.hilt")
    // Screenshot goldens of the template cards (R09 §6.3). The compose convention applies Roborazzi to UI modules;
    // this module has no Compose, so it applies the plugin itself with the same golden directory.
    alias(libs.plugins.roborazzi)
}

roborazzi {
    outputDir.set(layout.projectDirectory.dir("src/screenshots"))
}

dependencies {
    // DeliveryPort, RenderedIntervention, Outcome, AgentleClock and AppRoute appear in this module's public API.
    api(project(":core:model"))
    api(project(":core:common"))
    api(project(":core:time"))
    api(project(":core:ui"))
    api(project(":jitai:engine"))
    // AiCapabilities: provider image generation stays behind the capability flag (R09 §7).
    implementation(project(":ai:api"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.effect)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.exoplayer)

    testImplementation(project(":core:testing"))
    testImplementation(testFixtures(project(":jitai:engine")))
    // Bitmap.captureRoboImage for the template card golden.
    testImplementation(libs.roborazzi)

    androidTestImplementation(libs.kotlinx.coroutines.test)
}
