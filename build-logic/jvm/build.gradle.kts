plugins {
    `kotlin-dsl`
}

group = "dev.agentle.buildlogic"

kotlin {
    jvmToolchain(21)
}

dependencies {
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.detekt.gradlePlugin)
    compileOnly(libs.spotless.gradlePlugin)
    compileOnly(libs.kover.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("jvmLibrary") {
            id = "agentle.jvm.library"
            implementationClass = "dev.agentle.buildlogic.JvmLibraryConventionPlugin"
        }
        register("quality") {
            id = "agentle.quality"
            implementationClass = "dev.agentle.buildlogic.QualityConventionPlugin"
        }
        register("root") {
            id = "agentle.root"
            implementationClass = "dev.agentle.buildlogic.RootConventionPlugin"
        }
    }
}
