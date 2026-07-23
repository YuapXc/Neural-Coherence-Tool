package io.github.neuralcoherence.probe.core

internal object InteractionRateLimit {
    fun isExplicit(message: String): Boolean =
        "请求过于频繁" in message || "请求频繁" in message
}

internal class RateLimitException(
    action: String,
    val serverMessage: String,
) : IllegalStateException("$action 失败：$serverMessage")
