plugins {
    id("agentle.android.library")
    id("agentle.hilt")
}

// Built against ports it owns (dev.agentle.background.port); :data, :connectors:android and :interventions are not
// used and are not dependencies (the wiring team adapts them to the ports).
dependencies {
    api(project(":core:common"))
    api(project(":core:time"))
    api(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.androidx.core.ktx)
    testImplementation(project(":core:testing"))
    testImplementation(libs.androidx.work.testing)
}
