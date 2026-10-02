plugins {
    id("agentle.android.library")
    id("agentle.android.compose")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core:model"))
    // AppError (ErrorState) and the time types of the formatters are part of this module's API.
    api(project(":core:common"))
    api(project(":core:time"))
    // AppRoute implements NavKey so the app can keep its back stack with rememberNavBackStack.
    api(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.collections.immutable)
}
