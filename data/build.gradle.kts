plugins {
    id("agentle.android.library")
    id("agentle.hilt")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core:database"))
    api(project(":core:datastore"))
    api(project(":core:security"))
    api(project(":connectors:api"))
    api(project(":analytics:features"))
    api(project(":analytics:insights"))
    api(project(":jitai:engine"))
    api(project(":ai:api"))
    api(project(":ai:context"))
    implementation(project(":core:common"))
    implementation(libs.androidx.paging.runtime)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(project(":core:testing"))
    testImplementation(project(":fakes"))
}
