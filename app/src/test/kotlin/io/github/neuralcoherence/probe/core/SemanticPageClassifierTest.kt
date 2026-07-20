package io.github.neuralcoherence.probe.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticPageClassifierTest {
    @Test
    fun `requires all main-page anchors`() {
        var state = 0
        state = SemanticPageClassifier.inspectText(state, "同调网络")
        state = SemanticPageClassifier.inspectText(state, "同调记录")
        assertFalse(SemanticPageClassifier.isMainPage(state))

        state = SemanticPageClassifier.inspectText(state, "好友申请")
        assertTrue(SemanticPageClassifier.isMainPage(state))
    }

    @Test
    fun `blocked child page wins over main anchors`() {
        var state = 0
        listOf("同调网络", "同调记录", "好友申请", "调取档案").forEach {
            state = SemanticPageClassifier.inspectText(state, it)
        }

        assertFalse(SemanticPageClassifier.isMainPage(state))
    }

    @Test
    fun `ignores null and non-text values`() {
        var state = SemanticPageClassifier.inspectText(0, null)
        state = SemanticPageClassifier.inspectText(state, 42)

        assertFalse(SemanticPageClassifier.isMainPage(state))
    }
}
