plugins {
    id("agentle.android.library")
    id("agentle.android.compose")
}

dependencies {
    api(project(":core:model"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.collections.immutable)
}
