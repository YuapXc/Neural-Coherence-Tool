package io.github.neuralcoherence.probe.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InteractionRateLimitTest {
    @Test
    fun recognizesExplicitRateLimitMessages() {
        assertTrue(InteractionRateLimit.isExplicit("请求过于频繁，请稍后再试"))
        assertTrue(InteractionRateLimit.isExplicit("请求频繁，请稍后重试"))
    }

    @Test
    fun doesNotRetryUnrelatedBusinessFailures() {
        assertFalse(InteractionRateLimit.isExplicit("登录状态已失效"))
        assertFalse(InteractionRateLimit.isExplicit("服务器拒绝请求"))
    }
}
