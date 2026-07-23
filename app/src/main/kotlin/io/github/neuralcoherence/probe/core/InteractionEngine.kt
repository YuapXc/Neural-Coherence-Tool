package io.github.neuralcoherence.probe.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import io.github.neuralcoherence.probe.BuildConfig
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.net.ssl.SSLException

internal class InteractionEngine {
    fun scanAllFriends(context: Context, onProgress: (page: Int, pages: Int) -> Unit): ScanResult {
        ensureNetworkAvailable(context)
        val session = context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
            .getString("flutter.session", "")
            ?.takeIf { it.isNotBlank() }
            ?: error("未找到登录会话，请重新登录目标应用")

        val result = ScanResult(session = session)
        var page = 1
        var pages = 1
        do {
            val response = getFriendPage(session, page)
            check(response.optBoolean("succ", false)) { "服务器拒绝读取好友列表" }
            val data = response.optJSONObject("data")
                ?: error("好友接口结构已变化，已停止以保护账号")
            check(data.has("records") && data.has("pages")) {
                "好友接口结构已变化，已停止以保护账号"
            }
            pages = data.optInt("pages", 1).coerceAtLeast(1)
            result.total = data.optInt("total", result.total)
            val records = data.optJSONArray("records")
                ?: error("好友记录格式已变化，已停止以保护账号")
            for (index in 0 until records.length()) {
                val record = records.getJSONObject(index)
                check(record.has("todayContactInd")) {
                    "好友状态字段已变化，已停止以保护账号"
                }
                when (val state = record.optInt("todayContactInd", 0)) {
                    0 -> {
                        result.none++
                        result.candidates += candidate(record, "ping")
                    }
                    1 -> result.sent++
                    2 -> {
                        result.received++
                        result.candidates += candidate(record, "pong")
                    }
                    3 -> result.mutual++
                    else -> error("发现未知好友状态 $state，已停止以保护账号")
                }
            }
            onProgress(page, pages)
            page++
            if (page <= pages) Thread.sleep(700L)
        } while (page <= pages && page <= 100)
        return result
    }

    fun sendInteraction(session: String, candidate: Candidate) {
        val connection = URL("${API_BASE}api/friends/${candidate.action}")
            .openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        applyAuthHeaders(connection, session)
        val body = JSONObject().put("friendUserId", candidate.id)
            .toString().toByteArray(StandardCharsets.UTF_8)
        connection.setFixedLengthStreamingMode(body.size)
        connection.outputStream.use { it.write(body) }

        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val responseText = stream?.use(::readUtf8).orEmpty()
        connection.disconnect()
        if (status == 429) {
            throw RateLimitException(candidate.action, "服务器限制了请求频率，请稍后再试")
        }
        if (status != HttpURLConnection.HTTP_OK) throw httpStatusError(status)
        val response = JSONObject(responseText)
        if (!response.optBoolean("succ", false)) {
            val message = response.optString("msg", "服务器拒绝请求")
            if (InteractionRateLimit.isExplicit(message)) {
                throw RateLimitException(candidate.action, message)
            }
            throw IllegalStateException("${candidate.action} 失败：$message")
        }
    }

    private fun candidate(record: JSONObject, action: String): Candidate {
        val id = record.optLong("id", 0L)
        check(id > 0) { "好友 ID 字段已变化，已停止以保护账号" }
        return Candidate(id, action)
    }

    private fun getFriendPage(session: String, page: Int): JSONObject {
        val path = "api/friends/friendList?pageIndex=$page&pageSize=$PAGE_SIZE" +
            "&queryValue=&isOrder=true&serverName=&isSameCity=0"
        val connection = URL(API_BASE + path).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        connection.instanceFollowRedirects = false
        applyAuthHeaders(connection, session)
        val status = connection.responseCode
        if (status != HttpURLConnection.HTTP_OK) {
            connection.disconnect()
            throw httpStatusError(status)
        }
        return try {
            connection.inputStream.use { JSONObject(readUtf8(it)) }
        } finally {
            connection.disconnect()
        }
    }

    private fun applyAuthHeaders(connection: HttpURLConnection, session: String) {
        val salt = BuildConfig.SYNC_SIGNING_SALT
        check(salt.isNotBlank()) { "模块缺少本地签名配置，无法发送请求" }
        val timestamp = System.currentTimeMillis()
        connection.setRequestProperty("User-Agent", "zwintech-arkradar-app")
        connection.setRequestProperty("authorization", md5(salt + timestamp))
        connection.setRequestProperty("timenum", timestamp.toString())
        connection.setRequestProperty("Cookie", "Zwin-ArkRadar=$session")
    }

    private fun readUtf8(input: java.io.InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count == -1) break
            output.write(buffer, 0, count)
        }
        return output.toString(StandardCharsets.UTF_8.name())
    }

    private fun md5(input: String): String = MessageDigest.getInstance("MD5")
        .digest(input.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        private const val API_BASE = "https://www.tongdiaojihua.com/zwinport/"
        private const val PAGE_SIZE = 50

        fun userFacingError(error: Throwable): String = when (error) {
            is UnknownHostException, is ConnectException ->
                "无法连接服务器，请检查网络、代理或 DNS 设置"
            is SocketTimeoutException -> "服务器响应超时，任务已停止，请稍后重试"
            is SSLException -> "安全连接建立失败，请检查系统时间或网络环境"
            else -> error.message?.takeIf { it.isNotBlank() } ?: "发生未知错误，任务已停止"
        }

        private fun ensureNetworkAvailable(context: Context) {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            val capabilities = manager?.activeNetwork?.let(manager::getNetworkCapabilities)
            check(capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) {
                "当前没有可用网络，请连接网络后重试"
            }
        }

        private fun httpStatusError(status: Int): IllegalStateException = when (status) {
            401, 403 -> IllegalStateException("登录状态已失效或请求未获授权，请重新登录")
            429 -> IllegalStateException("服务器限制了请求频率，任务已停止，请稍后重试")
            else -> if (status >= 500) {
                IllegalStateException("服务器暂时不可用（HTTP $status）")
            } else {
                IllegalStateException("请求失败（HTTP $status）")
            }
        }
    }
}

internal data class Candidate(val id: Long, val action: String)

internal data class ScanResult(
    val session: String,
    var total: Int = 0,
    var none: Int = 0,
    var sent: Int = 0,
    var received: Int = 0,
    var mutual: Int = 0,
    val candidates: MutableList<Candidate> = mutableListOf(),
)
