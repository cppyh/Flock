package com.meetroute.app.data

import com.meetroute.app.domain.ConnectionState
import com.meetroute.app.domain.GroupState
import com.meetroute.app.domain.Participant
import com.meetroute.app.domain.RecommendationSet
import com.meetroute.app.domain.SessionEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

private const val MAX_BACKOFF_MILLIS = 15_000L
private const val HEARTBEAT_MILLIS = 15_000L
private const val NORMAL_CLOSURE = 1000

// 재접속해도 소용없는 종료 코드: 인증 실패, 탈퇴, 세션 종료, 다른 연결로 교체
private val TERMINAL_CLOSE_CODES = setOf(4401, 4403, 4409, 4410)

@Serializable
private data class ServerEnvelope(val type: String, val payload: JsonElement)

@Serializable
private data class GroupPayload(val group: GroupState)

/** 서버 이벤트 파싱. 모르는 이벤트는 건너뛰어 서버가 먼저 배포되어도 앱이 멈추지 않게 한다. */
fun parseServerEvent(text: String): SessionEvent? {
    val envelope = runCatching { ApiJson.decodeFromString<ServerEnvelope>(text) }.getOrNull() ?: return null
    val payload = envelope.payload
    return runCatching {
        when (envelope.type) {
            "participant.updated" ->
                SessionEvent.ParticipantUpdated(ApiJson.decodeFromJsonElement<Participant>(payload))
            "group.updated", "meeting_point.selected" ->
                SessionEvent.GroupUpdated(ApiJson.decodeFromJsonElement<GroupPayload>(payload).group)
            "recommendations.ready" ->
                SessionEvent.RecommendationsReady(ApiJson.decodeFromJsonElement<RecommendationSet>(payload))
            "session.closed" ->
                SessionEvent.SessionClosed(payload.jsonObject.getValue("reason").jsonPrimitive.content)
            "error" -> SessionEvent.ServerError(
                payload.jsonObject.getValue("code").jsonPrimitive.content,
                payload.jsonObject.getValue("message").jsonPrimitive.content,
            )
            else -> null
        }
    }.getOrNull()
}

/**
 * 그룹 실시간 채널. 끊기면 지수 백오프로 재접속한다.
 *
 * 연결될 때마다 [onConnected]로 최신 상태를 다시 맞춘다. 첫 연결도 같다. 그룹을 받은 뒤 소켓이 열리기
 * 전까지의 이벤트는 받을 수 없기 때문이다.
 */
class RealtimeClient(
    private val http: OkHttpClient,
    private val url: String,
    private val token: String,
    private val scope: CoroutineScope,
    private val onConnected: suspend () -> Unit,
) {
    private val _events = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<SessionEvent> = _events

    private val _state = MutableStateFlow(ConnectionState.OFFLINE)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var closedByUser = false
    private val retryCount = AtomicInteger()

    @Volatile
    private var retryJob: Job? = null

    @Volatile
    private var heartbeatJob: Job? = null

    fun connect() {
        closedByUser = false
        _state.value = if (retryCount.get() == 0) ConnectionState.CONNECTING else ConnectionState.RECONNECTING
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .build()
        socket = http.newWebSocket(request, listener)
    }

    // 연결 전에는 보내지 않는다. OkHttp가 큐에 넣어 REST 백업과 함께 두 번 전송되기 때문이다.
    fun send(text: String): Boolean = _state.value == ConnectionState.CONNECTED && socket?.send(text) == true

    fun close() {
        closedByUser = true
        retryJob?.cancel()
        heartbeatJob?.cancel()
        socket?.close(NORMAL_CLOSURE, null)
        socket = null
        _state.value = ConnectionState.CLOSED
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (webSocket !== socket) {
                webSocket.close(NORMAL_CLOSURE, null)
                return
            }
            retryCount.set(0)
            _state.value = ConnectionState.CONNECTED
            startHeartbeat()
            scope.launch { runCatching { onConnected() } }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            parseServerEvent(text)?.let(_events::tryEmit)
        }

        // 서버가 닫으면 응답해야 onClosed가 오고 연결도 정리된다. 상태는 여기서 바로 맞춘다.
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(NORMAL_CLOSURE, null)
            handleClosed(webSocket, code)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = handleClosed(webSocket, code)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (webSocket === socket) {
                socket = null
                scheduleReconnect()
            }
        }
    }

    private fun handleClosed(webSocket: WebSocket, code: Int) {
        if (webSocket !== socket) return
        socket = null
        if (code in TERMINAL_CLOSE_CODES) {
            closedByUser = true
            retryJob?.cancel()
            heartbeatJob?.cancel()
            _state.value = ConnectionState.CLOSED
        } else {
            scheduleReconnect()
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_MILLIS)
                socket?.send("""{"type":"presence.heartbeat"}""")
            }
        }
    }

    private fun scheduleReconnect() {
        heartbeatJob?.cancel()
        if (closedByUser) return
        _state.value = ConnectionState.RECONNECTING
        val attempt = retryCount.incrementAndGet()
        val backoff = min(MAX_BACKOFF_MILLIS, 1_000L shl min(attempt, 4))
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(backoff)
            if (!closedByUser) connect()
        }
    }
}
