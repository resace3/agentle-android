package dev.agentle.core.model

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The one JSON configuration for persisted payloads. */
public object EventCodec {
    public val json: Json = Json {
        classDiscriminator = "kind"
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

    public fun encode(payload: EventPayload): String = json.encodeToString(EventPayload.serializer(), payload)

    /**
     * Decodes a payload. A `kind` this version does not know becomes [UnknownPayload] (forward compatibility); a
     * malformed document of a known kind is an error the caller must handle (it is never silently dropped).
     */
    public fun decode(text: String): EventPayload {
        val element = json.parseToJsonElement(text)
        val kind = (element as? JsonObject)?.get("kind")?.jsonPrimitive?.content
            ?: throw SerializationException("payload has no 'kind'")
        if (kind !in knownKinds) return UnknownPayload(kind, text)
        return json.decodeFromJsonElement(EventPayload.serializer(), element)
    }

    private val knownKinds: Set<String> by lazy {
        val descriptor = EventPayload.serializer().descriptor
        // Sealed descriptor: element 1 is the polymorphic "value" whose elements are the subclass descriptors.
        val subclasses = descriptor.getElementDescriptor(1)
        (0 until subclasses.elementsCount).map { subclasses.getElementName(it) }.toSet()
    }
}
