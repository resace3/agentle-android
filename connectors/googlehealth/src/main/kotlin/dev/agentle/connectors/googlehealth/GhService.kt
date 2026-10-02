package dev.agentle.connectors.googlehealth

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

/**
 * The Google Health API v4 read methods Agentle uses (docs/research/05 §3.1, §7.3). Paths are relative to the base
 * URL (`https://health.googleapis.com/`); `{user}` is always `me`. Every call returns the raw response so the client
 * checks status and `Content-Type` before parsing (HTML 404/502, captive portals). Bodies stream, so a connection lost
 * mid-body is told apart from one that never connected. No write method exists here (hygiene H11).
 */
internal interface GhService {
    @Streaming
    @Headers(ACCEPT)
    @GET("v4/users/me/dataTypes/{type}/dataPoints")
    suspend fun list(
        @Header("Authorization") authorization: String,
        @Path("type") type: String,
        @Query("filter") filter: String?,
        @Query("pageSize") pageSize: Int?,
        @Query("pageToken") pageToken: String?,
    ): Response<ResponseBody>

    @Streaming
    @Headers(ACCEPT)
    @GET("v4/users/me/dataTypes/{type}/dataPoints:reconcile")
    suspend fun reconcile(
        @Header("Authorization") authorization: String,
        @Path("type") type: String,
        @Query("filter") filter: String?,
        @Query("pageSize") pageSize: Int?,
        @Query("pageToken") pageToken: String?,
    ): Response<ResponseBody>

    @Streaming
    @Headers(ACCEPT)
    @POST("v4/users/me/dataTypes/{type}/dataPoints:rollUp")
    suspend fun rollUp(
        @Header("Authorization") authorization: String,
        @Path("type") type: String,
        @Body body: RequestBody,
    ): Response<ResponseBody>

    @Streaming
    @Headers(ACCEPT)
    @POST("v4/users/me/dataTypes/{type}/dataPoints:dailyRollUp")
    suspend fun dailyRollUp(
        @Header("Authorization") authorization: String,
        @Path("type") type: String,
        @Body body: RequestBody,
    ): Response<ResponseBody>

    @Streaming
    @Headers(ACCEPT)
    @GET("v4/users/me/identity")
    suspend fun identity(@Header("Authorization") authorization: String): Response<ResponseBody>

    @Streaming
    @Headers(ACCEPT)
    @GET("v4/users/me/settings")
    suspend fun settings(@Header("Authorization") authorization: String): Response<ResponseBody>

    @Streaming
    @Headers(ACCEPT)
    @GET("v4/users/me/pairedDevices")
    suspend fun pairedDevices(
        @Header("Authorization") authorization: String,
        @Query("pageSize") pageSize: Int?,
        @Query("pageToken") pageToken: String?,
    ): Response<ResponseBody>

    companion object {
        const val ACCEPT: String = "Accept: application/json"
    }
}
