plugins {
    id("agentle.android.feature")
}

dependencies {
    // The ports in dev.agentle.feature.connections.port expose these domain types (ConnectorMetadata, SyncResult,
    // AiProviderState, AiCapabilities, AiPurpose, AppError, Outcome), so they are part of this module's API. The UI
    // never reaches :data, :connectors:googlehealth or :ai:chatgpt: the wiring team implements the ports on top of them.
    api(project(":core:model"))
    api(project(":core:common"))
    api(project(":core:ui"))
    api(project(":connectors:api"))
    api(project(":ai:api"))
    // StartIntentSenderForResult for Google's consent screen (the needs-resolution PendingIntent).
    implementation(libs.androidx.activity.compose)
}
