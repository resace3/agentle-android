plugins {
    id("agentle.android.library")
    id("agentle.hilt")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // Ports only (round 4 correction 5, testing-build-13): the database, the key material and the settings files stay
    // behind :data's interfaces, so :feature:* modules can never reach a DAO or the vault through :data.
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))
    implementation(project(":core:security"))
    api(project(":core:model"))
    api(project(":core:time"))
    api(project(":core:common"))
    api(project(":connectors:api"))
    api(project(":analytics:features"))
    api(project(":analytics:insights"))
    api(project(":jitai:engine"))
    api(project(":ai:api"))
    api(project(":ai:context"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(project(":core:testing"))
    testImplementation(project(":fakes"))
    // Robolectric tests run Room over the bundled SQLite (round 4 correction 4).
    testImplementation(libs.androidx.sqlite.bundled)
    testImplementation(libs.androidx.sqlite.framework)
}
