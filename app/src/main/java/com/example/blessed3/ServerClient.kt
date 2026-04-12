package com.example.blessed3

import android.util.Log
import kotlinx.coroutines.Dispatchers
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
    const val SERVER_URL = "https://50dc-109-79-50-114.ngrok-free.app"

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

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
                    .url("$SERVER_URL/message")
                    .post(body)
                    .build()
                val response = client.newCall(request).execute()
                Log.d(TAG, "POST /message → ${response.code} (msgId=$messageId)")
                response.close()
            } catch (e: Exception) {
                Log.e(TAG, "POST /message failed: ${e.message}")
            }
        }
    }

    suspend fun pollRelayPending(): List<ServerMessage> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$SERVER_URL/relay-pending")
                .get()
                .build()
            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string() ?: "[]"
            response.close()
            Log.d(TAG, "GET /relay-pending → ${response.code} (${bodyStr.length} chars)")

            val arr = JSONArray(bodyStr)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                ServerMessage(
                    messageId = obj.getString("messageId"),
                    from = obj.getString("from"),
                    to = obj.optString("to", ""),
                    content = obj.getString("content")
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "GET /relay-pending failed: ${e.message}")
            emptyList()
        }
    }

    suspend fun confirmRelayDelivery(messageId: String) {
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$SERVER_URL/relay-confirm/$messageId")
                    .post("".toRequestBody(null))
                    .build()
                val response = client.newCall(request).execute()
                Log.d(TAG, "POST /relay-confirm → ${response.code} (msgId=$messageId)")
                response.close()
            } catch (e: Exception) {
                Log.e(TAG, "POST /relay-confirm failed: ${e.message}")
            }
        }
    }

    suspend fun pollMessages(myAppId: String): List<ServerMessage> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$SERVER_URL/messages/$myAppId")
                .get()
                .build()
            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string() ?: "[]"
            response.close()
            Log.d(TAG, "GET /messages/$myAppId → ${response.code} (${bodyStr.length} chars)")

            val arr = JSONArray(bodyStr)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                ServerMessage(
                    messageId = obj.getString("messageId"),
                    from = obj.getString("from"),
                    to = obj.optString("to", ""),
                    content = obj.getString("content")
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "GET /messages failed: ${e.message}")
            emptyList()
        }
    }
}
