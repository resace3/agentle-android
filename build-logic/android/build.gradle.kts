plugins {
    `kotlin-dsl`
}

group = "dev.agentle.buildlogic"

kotlin {
    jvmToolchain(21)
}

dependencies {
    compileOnly(libs.android.gradleApiPlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.compose.gradlePlugin)
    compileOnly(libs.ksp.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "agentle.android.application"
            implementationClass = "dev.agentle.buildlogic.android.AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "agentle.android.library"
            implementationClass = "dev.agentle.buildlogic.android.AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "agentle.android.compose"
            implementationClass = "dev.agentle.buildlogic.android.AndroidComposeConventionPlugin"
        }
        register("androidFeature") {
            id = "agentle.android.feature"
            implementationClass = "dev.agentle.buildlogic.android.AndroidFeatureConventionPlugin"
        }
        register("hilt") {
            id = "agentle.hilt"
            implementationClass = "dev.agentle.buildlogic.android.HiltConventionPlugin"
        }
        register("room") {
            id = "agentle.room"
            implementationClass = "dev.agentle.buildlogic.android.RoomConventionPlugin"
        }
    }
}
