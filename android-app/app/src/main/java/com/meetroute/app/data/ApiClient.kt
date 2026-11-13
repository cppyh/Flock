package com.meetroute.app.data

import com.meetroute.app.domain.MeetRouteException
import com.meetroute.app.domain.RecommendationSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

private val JSON_MEDIA_TYPE = "application/json".toMediaType()

val ApiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

@Serializable
private data class ErrorBody(val error: ErrorDetail? = null)

@Serializable
private data class ErrorDetail(
    val code: String,
    val message: String,
    @SerialName("last_good") val lastGood: RecommendationSet? = null,
)

/** REST 호출. 실패는 모두 MeetRouteException으로 바꿔 화면이 한 방식으로 처리하게 한다. */
class ApiClient(
    private val baseUrl: String,
    private val http: OkHttpClient,
) {
    @Volatile
    var token: String? = null

    suspend fun get(path: String): String = send("GET", path, null)

    suspend fun post(path: String, body: String = "{}"): String = send("POST", path, body)

    suspend fun patch(path: String, body: String): String = send("PATCH", path, body)

    private suspend fun send(method: String, path: String, body: String?): String =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().method(method, body?.toRequestBody(JSON_MEDIA_TYPE))
            try {
                builder.url(baseUrl.trimEnd('/') + path)
            } catch (error: IllegalArgumentException) {
                throw MeetRouteException("INVALID_SERVER_URL", "서버 주소가 올바르지 않습니다.")
            }
            token?.let { builder.header("Authorization", "Bearer $it") }
            try {
                http.newCall(builder.build()).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) throw toException(response.code, text)
                    text
                }
            } catch (error: IOException) {
                throw MeetRouteException("NETWORK", "서버에 연결할 수 없습니다. 네트워크를 확인해 주세요.")
            }
        }

    private fun toException(status: Int, text: String): MeetRouteException {
        val detail = runCatching { ApiJson.decodeFromString<ErrorBody>(text).error }.getOrNull()
        return if (detail != null) {
            MeetRouteException(detail.code, detail.message, detail.lastGood)
        } else {
            MeetRouteException("HTTP_$status", "요청을 처리하지 못했습니다. ($status)")
        }
    }
}
