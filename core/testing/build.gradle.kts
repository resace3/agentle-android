plugins {
    id("agentle.jvm.library")
}

dependencies {
    api(project(":core:time"))
    api(project(":core:common"))
    api(project(":core:model"))
    api(libs.kotlinx.coroutines.test)
    api(libs.truth)
    api(platform(libs.junit.bom))
    api(libs.junit.jupiter)
}
