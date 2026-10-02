plugins {
    id("agentle.android.library")
    id("agentle.hilt")
}

dependencies {
    implementation(project(":data"))
    implementation(project(":connectors:android"))
    implementation(project(":interventions"))
    implementation(project(":core:common"))
    api(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.androidx.core.ktx)
    testImplementation(project(":core:testing"))
    testImplementation(libs.androidx.work.testing)
}
