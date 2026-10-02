package dev.agentle.core.network

import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** JSON settings shared by every remote API: lenient towards new fields, strict about types. */
public object NetworkJson {
    public val default: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        coerceInputValues = true
    }

    /** A Retrofit instance over [client] that decodes with [json]. Base URLs must end with `/`. */
    public fun retrofit(baseUrl: HttpUrl, client: OkHttpClient, json: Json = default): Retrofit {
        require(baseUrl.encodedPath.endsWith("/")) { "Retrofit base URL must end with '/': $baseUrl" }
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }
}
