package com.example.blessed3

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ServerMessage(
    val messageId: String,
    val from: String,
    val to: String = "",
    val content: String
)

object ServerClient {

    private const val TAG = "ServerClient"

    /**
     * Last-known health of the chat API (updated by every HTTP call).
     * Used only by UI/transport selection; pollers keep running regardless.
     */
    private val _serverReachable = MutableStateFlow(false)
    val serverReachable: StateFlow<Boolean> = _serverReachable.asStateFlow()

    private fun recordSuccess() { _serverReachable.value = true }
    private fun recordFailure() { _serverReachable.value = false }

    private fun isLikelyJsonArray(body: String): Boolean =
        body.trimStart().startsWith("[")

    /**
     * Live probe for transport selection ([ChatTransportCoordinator.startSession]).
     * Requires validated internet AND a 2xx JSON-array response on /messages.
     */
    suspend fun isChatServerReachable(context: Context): Boolean {
        if (!NetworkUtils.hasInternet(context)) {
            recordFailure()
            return false
        }
        val id = DeviceIdentity.appId
        if (id.isBlank()) return false
        return probeMessagesEndpointOk(id)
    }

    private suspend fun probeMessagesEndpointOk(myAppId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("/messages/$myAppId".withBaseUrl())
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                val ok = response.isSuccessful && isLikelyJsonArray(bodyStr)
                if (!ok) {
                    Log.w(TAG, "server probe GET /messages/$myAppId → ${response.code} (json=${isLikelyJsonArray(bodyStr)})")
                    recordFailure()
                } else {
                    recordSuccess()
                }
                ok
            }
        } catch (e: Exception) {
            Log.w(TAG, "server probe failed: ${e.message}")
            recordFailure()
            false
        }
    }

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("X-API-Key", BuildConfig.SERVER_API_KEY)
                .build()
            chain.proceed(req)
        }
        .build()

    private fun String.withBaseUrl(): String = BuildConfig.SERVER_BASE_URL.trimEnd('/') + this

    suspend fun postMessage(messageId: String, from: String, to: String, content: String) {
        withContext(Dispatchers.IO) {
            try {
                val json = JSONObject().apply {
                    put("messageId", messageId)
                    put("from", from)
                    put("to", to)
                    put("content", content)
                }
                val body = json.toString().toRequestBody(JSON_MEDIA)
                val request = Request.Builder()
                    .url("/message".withBaseUrl())
                    .post(body)
                    .build()
                client.newCall(request).execute().use { response ->
                    Log.d(TAG, "POST /message → ${response.code} (msgId=$messageId)")
                    if (response.isSuccessful) recordSuccess() else recordFailure()
                }
            } catch (e: Exception) {
                Log.w(TAG, "POST /message failed: ${e.message}")
                recordFailure()
            }
        }
    }

    /** Always attempts the call; callers still decide when to invoke it. */
    suspend fun pollRelayPending(): List<ServerMessage> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("/relay-pending".withBaseUrl())
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                Log.d(TAG, "GET /relay-pending → ${response.code} (${bodyStr.length} chars)")
                if (!response.isSuccessful || !isLikelyJsonArray(bodyStr)) {
                    Log.w(TAG, "GET /relay-pending: non-JSON or HTTP ${response.code}")
                    recordFailure()
                    return@withContext emptyList()
                }
                val arr = JSONArray(bodyStr)
                val out = (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    ServerMessage(
                        messageId = obj.getString("messageId"),
                        from = obj.getString("from"),
                        to = obj.optString("to", ""),
                        content = obj.getString("content")
                    )
                }
                recordSuccess()
                out
            }
        } catch (e: Exception) {
            Log.w(TAG, "GET /relay-pending failed: ${e.message}")
            recordFailure()
            emptyList()
        }
    }

    suspend fun confirmRelayDelivery(messageId: String) {
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("/relay-confirm/$messageId".withBaseUrl())
                    .post("".toRequestBody(null))
                    .build()
                client.newCall(request).execute().use { response ->
                    Log.d(TAG, "POST /relay-confirm → ${response.code} (msgId=$messageId)")
                    if (response.isSuccessful) recordSuccess() else recordFailure()
                }
            } catch (e: Exception) {
                Log.w(TAG, "POST /relay-confirm failed: ${e.message}")
                recordFailure()
            }
        }
    }

    /** Always attempts the call; [ForegroundServerPoller] drives the cadence. */
    suspend fun pollMessages(myAppId: String): List<ServerMessage> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("/messages/$myAppId".withBaseUrl())
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                Log.d(TAG, "GET /messages/$myAppId → ${response.code} (${bodyStr.length} chars)")
                if (!response.isSuccessful || !isLikelyJsonArray(bodyStr)) {
                    Log.w(TAG, "GET /messages: non-JSON or HTTP ${response.code}")
                    recordFailure()
                    return@withContext emptyList()
                }
                val arr = JSONArray(bodyStr)
                val out = (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    ServerMessage(
                        messageId = obj.getString("messageId"),
                        from = obj.getString("from"),
                        to = obj.optString("to", ""),
                        content = obj.getString("content")
                    )
                }
                recordSuccess()
                out
            }
        } catch (e: Exception) {
            Log.w(TAG, "GET /messages failed: ${e.message}")
            recordFailure()
            emptyList()
        }
    }
}
