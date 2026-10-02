package dev.agentle.core.datastore

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * The JSON format of every store: unknown keys are ignored (a file written by a newer version stays readable), an
 * unknown enum value falls back to the field's default instead of failing the whole file, and defaults are written
 * out so the file documents itself.
 */
internal val StoreJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    encodeDefaults = true
    explicitNulls = false
}

/**
 * Failures of the stores, for structured diagnostics. Implementations receive the store's name, the operation and an
 * error class, never the file content or an exception message (kotlinx.serialization messages quote the input;
 * round 1 correction 2).
 */
interface StoreDiagnostics {
    /** The file could not be decoded and was replaced by its reset value. */
    fun onReset(store: String, errorClass: String)

    /** Reading or writing the file failed with an I/O error; the store answered with its safe default. */
    fun onIoFailure(store: String, operation: String, errorClass: String)

    companion object {
        val NONE: StoreDiagnostics = object : StoreDiagnostics {
            override fun onReset(store: String, errorClass: String) = Unit

            override fun onIoFailure(store: String, operation: String, errorClass: String) = Unit
        }
    }
}

/**
 * [Serializer] for a kotlinx.serialization value. A file that is not valid JSON for [serializer] becomes a
 * [CorruptionException] whose message names only the store and the decoder's error class.
 */
internal class JsonSerializer<T>(private val name: String, private val serializer: KSerializer<T>, override val defaultValue: T) :
    Serializer<T> {
    @Volatile var lastFailureClass: String = CorruptionException::class.java.simpleName
        private set

    override suspend fun readFrom(input: InputStream): T {
        val text = input.readBytes().decodeToString()
        return try {
            StoreJson.decodeFromString(serializer, text)
        } catch (expected: IllegalArgumentException) {
            // SerializationException is an IllegalArgumentException, and so are failed init checks of the models.
            lastFailureClass = expected.javaClass.simpleName
            throw CorruptionException("$name: ${expected.javaClass.simpleName}")
        }
    }

    override suspend fun writeTo(t: T, output: OutputStream) {
        output.write(StoreJson.encodeToString(serializer, t).encodeToByteArray())
    }
}

/** Where the stores live: `noBackupFilesDir/datastore/`, so no backup, restore or device transfer ever carries them. */
class StoreFiles(private val directory: () -> File) {
    val settings: File get() = File(directory(), SETTINGS_FILE)
    val aiConsent: File get() = File(directory(), AI_CONSENT_FILE)

    /** Every store file, for "delete all personal data". */
    val all: List<File> get() = listOf(settings, aiConsent)

    companion object {
        const val SETTINGS_FILE: String = "settings.v1.json"
        const val AI_CONSENT_FILE: String = "ai-consent.v1.json"

        fun inNoBackupDir(noBackupDir: () -> File): StoreFiles = StoreFiles { File(noBackupDir(), "datastore") }
    }
}

/**
 * A typed DataStore over [file]. A file that cannot be decoded is replaced by [onCorruption]'s value and reported to
 * [diagnostics] (store name and error class only).
 */
internal fun <T> jsonDataStore(
    name: String,
    file: () -> File,
    serializer: KSerializer<T>,
    defaultValue: T,
    scope: CoroutineScope,
    diagnostics: StoreDiagnostics,
    onCorruption: () -> T,
): DataStore<T> {
    val json = JsonSerializer(name, serializer, defaultValue)
    return DataStoreFactory.create(
        serializer = json,
        corruptionHandler = ReplaceFileCorruptionHandler { _ ->
            diagnostics.onReset(name, json.lastFailureClass)
            onCorruption()
        },
        scope = scope,
        produceFile = file,
    )
}
