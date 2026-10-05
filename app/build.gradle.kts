plugins {
    id("agentle.android.application")
    id("agentle.android.compose")
    id("agentle.hilt")
    alias(libs.plugins.kotlin.serialization)
}

android {
    defaultConfig {
        applicationId = "dev.agentle.app"
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "dev.agentle.app.HiltTestRunner"
    }
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:time"))
    implementation(project(":core:ui"))
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))
    implementation(project(":core:security"))
    implementation(project(":core:network"))
    implementation(project(":data"))
    implementation(project(":connectors:api"))
    implementation(project(":connectors:android"))
    implementation(project(":connectors:googlehealth"))
    implementation(project(":ai:api"))
    implementation(project(":ai:chatgpt"))
    implementation(project(":ai:context"))
    implementation(project(":analytics:features"))
    implementation(project(":analytics:insights"))
    implementation(project(":jitai:dsl"))
    implementation(project(":jitai:engine"))
    implementation(project(":interventions"))
    implementation(project(":background"))
    implementation(project(":feature:onboarding"))
    implementation(project(":feature:hub"))
    implementation(project(":feature:insights"))
    implementation(project(":feature:connections"))
    implementation(project(":feature:settings"))
    "fakeImplementation"(project(":fakes"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.androidx.browser)
    implementation(libs.a2ui.model)
    implementation(libs.a2ui.compose.runtime)
    implementation(libs.a2ui.compose.ui)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(project(":core:testing"))
    testImplementation(project(":fakes"))
    testImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(project(":fakes"))
}
