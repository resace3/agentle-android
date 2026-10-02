plugins {
    id("agentle.android.library")
    id("agentle.hilt")
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:time"))
    implementation(project(":core:datastore"))
    implementation(project(":jitai:engine"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.effect)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.exoplayer)
    testImplementation(project(":core:testing"))
}
