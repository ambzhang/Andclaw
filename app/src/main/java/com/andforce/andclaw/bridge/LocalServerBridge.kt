package com.andforce.andclaw.bridge

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.base.services.BridgeStatus
import com.base.services.RemoteChannel
import com.base.services.RemoteIncomingMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 本地服务器桥接：
 * - 启动时向电脑服务器 POST /register 上报设备信息
 * - 循环 GET /poll 拉取指令，收到指令后回调 [onInbound]
 * - POST /result 上报指令执行结果
 *
 * 服务器协议（无鉴权）：
 *
 * 注册：POST http://<host>:<port>/register
 *   Body: { "device_id": "...", "model": "...", "brand": "...", "android_version": "...", "ip": "..." }
 *   Response: { "ok": true }
 *
 * 拉取指令：GET http://<host>:<port>/poll?device_id=<id>
 *   Response（有指令）: { "ok": true, "command_id": "xxx", "text": "指令内容" }
 *   Response（无指令）: { "ok": true, "text": "" }
 *
 * 上报结果：POST http://<host>:<port>/result
 *   Body: { "device_id": "...", "command_id": "xxx", "result": "执行结果" }
 */
class LocalServerBridge(
    private val context: Context,
    private val scope: CoroutineScope,
    private val getHost: () -> String,
    private val getPort: () -> Int,
    private val onInbound: suspend (RemoteIncomingMessage) -> Unit,
    private val onConnectionStatus: (BridgeStatus) -> Unit
) {
    companion object {
        private const val TAG = "LocalServerBridge"
        private const val POLL_INTERVAL_MS = 2000L
        private const val CONNECT_TIMEOUT_MS = 5000
        private const val READ_TIMEOUT_MS = 8000
    }

    private var pollJob: Job? = null
    private val deviceId: String by lazy { getDeviceId() }

    fun start() {
        if (pollJob?.isActive == true) return
        Log.d(TAG, "start: host=${getHost()}, port=${getPort()}")
        pollJob = scope.launch(Dispatchers.IO) {
            // 先上报设备信息
            registerDevice()
            // 开始轮询
            pollLoop()
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
        onConnectionStatus(BridgeStatus.STOPPED)
        Log.d(TAG, "stop")
    }

    private suspend fun registerDevice() {
        val host = getHost()
        val port = getPort()
        if (host.isBlank() || port <= 0) {
            onConnectionStatus(BridgeStatus.NOT_CONFIGURED)
            return
        }
        try {
            val body = JSONObject().apply {
                put("device_id", deviceId)
                put("model", Build.MODEL)
                put("brand", Build.BRAND)
                put("android_version", Build.VERSION.RELEASE)
                put("sdk_int", Build.VERSION.SDK_INT)
                put("ip", getLocalIp())
                put("timestamp", System.currentTimeMillis())
            }
            val url = "http://$host:$port/register"
            val resp = httpPost(url, body.toString())
            Log.d(TAG, "registerDevice: resp=$resp")
            onConnectionStatus(BridgeStatus.CONNECTED)
        } catch (e: Exception) {
            Log.w(TAG, "registerDevice failed: ${e.message}")
            onConnectionStatus(BridgeStatus.DISCONNECTED)
        }
    }

    private suspend fun pollLoop() {
        val host = getHost()
        val port = getPort()
        if (host.isBlank() || port <= 0) {
            onConnectionStatus(BridgeStatus.NOT_CONFIGURED)
            return
        }

        var consecutiveFailures = 0
        while (isActive) {
            try {
                val url = "http://$host:$port/poll?device_id=${deviceId}"
                val resp = httpGet(url)
                consecutiveFailures = 0
                onConnectionStatus(BridgeStatus.CONNECTED)

                val json = JSONObject(resp)
                val text = json.optString("text", "").trim()
                val commandId = json.optString("command_id", "")

                if (text.isNotEmpty()) {
                    Log.d(TAG, "pollLoop: received command: $text")
                    val msg = RemoteIncomingMessage(
                        channel = RemoteChannel.LOCAL_SERVER,
                        sessionKey = deviceId,
                        messageId = commandId.ifEmpty { System.currentTimeMillis().toString() },
                        text = text,
                        receivedAtMs = System.currentTimeMillis()
                    )
                    withContext(Dispatchers.Main) {
                        onInbound(msg)
                    }
                }
            } catch (e: Exception) {
                consecutiveFailures++
                Log.w(TAG, "pollLoop error ($consecutiveFailures): ${e.message}")
                if (consecutiveFailures >= 3) {
                    onConnectionStatus(BridgeStatus.DISCONNECTED)
                }
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    /** 向服务器上报指令执行结果 */
    suspend fun sendResult(commandId: String, result: String) {
        val host = getHost()
        val port = getPort()
        if (host.isBlank() || port <= 0) return
        try {
            val body = JSONObject().apply {
                put("device_id", deviceId)
                put("command_id", commandId)
                put("result", result)
                put("timestamp", System.currentTimeMillis())
            }
            httpPost("http://$host:$port/result", body.toString())
        } catch (e: Exception) {
            Log.w(TAG, "sendResult failed: ${e.message}")
        }
    }

    /** 向服务器发送文本消息（用于回传 Agent 执行结果） */
    suspend fun sendText(commandId: String, text: String) {
        sendResult(commandId, text)
    }

    private fun httpGet(urlStr: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            stream?.bufferedReader()?.readText() ?: ""
        } finally {
            conn.disconnect()
        }
    }

    private fun httpPost(urlStr: String, body: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
        }
        return try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            stream?.bufferedReader()?.readText() ?: ""
        } finally {
            conn.disconnect()
        }
    }

    private fun getDeviceId(): String {
        return try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    private fun getLocalIp(): String {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val ip = wifiManager?.connectionInfo?.ipAddress ?: 0
            if (ip == 0) return "unknown"
            "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}.${ip shr 24 and 0xff}"
        } catch (e: Exception) {
            "unknown"
        }
    }
}
