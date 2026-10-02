package dev.agentle.core.model

import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
public enum class GenerationMethod { LOCAL_TEMPLATE, BUNDLED_ASSET, LOCAL_TTS, LOCAL_COMPOSITION, AI_PROVIDER }

@Serializable
public enum class MediaKind { IMAGE, VOICE, VIDEO }

/** Metadata of a generated media file (spec §66). The file itself lives in app-private no-backup storage. */
@Serializable
public data class MediaArtifact(
    val id: String,
    val kind: MediaKind,
    val createdAt: Instant,
    val sourceJitaiId: String? = null,
    val decisionKey: String? = null,
    val method: GenerationMethod,
    val localUri: String,
    val mimeType: String,
    val sizeBytes: Long,
    val expiresAt: Instant? = null,
)
