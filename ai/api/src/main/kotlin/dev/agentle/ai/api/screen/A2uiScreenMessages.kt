package dev.agentle.ai.api.screen

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The A2UI 0.9.1 messages that draw a checked [ScreenSpec] with Agentle's catalog. The app writes these itself from
 * the saved screen; the model never sends A2UI messages, so it cannot pick another catalog, a data model or actions.
 */
public object A2uiScreenMessages {
    /** `createSurface` for [surfaceId] on Agentle's catalog. */
    public fun createSurface(surfaceId: String): String = buildJsonObject {
        put("version", ScreenCatalog.A2UI_VERSION)
        putJsonObject("createSurface") {
            put("surfaceId", surfaceId)
            put("catalogId", ScreenCatalog.ID)
        }
    }.toString()

    /** `updateComponents` with every part of [screen]: `{"id":..,"component":..,<fields>}` each, the root among them. */
    public fun updateComponents(surfaceId: String, screen: ScreenSpec): String = buildJsonObject {
        put("version", ScreenCatalog.A2UI_VERSION)
        putJsonObject("updateComponents") {
            put("surfaceId", surfaceId)
            put("components", JsonArray(screen.components.map(::component)))
        }
    }.toString()

    private fun component(part: ScreenPart): JsonObject = ScreenJson.encodeToJsonElement(ScreenPart.serializer(), part) as JsonObject
}
