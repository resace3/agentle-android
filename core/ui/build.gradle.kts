plugins {
    id("agentle.android.library")
    id("agentle.android.compose")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core:model"))
    // AppRoute implements NavKey so the app can keep its back stack with rememberNavBackStack.
    api(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.collections.immutable)
}
