package io.github.neuralcoherence.probe.core

/** Pure classifier for the Flutter semantics text exposed by the host app. */
object SemanticPageClassifier {
    private val FRIEND_COUNT_PATTERN = Regex("""^\s*[（(]?\s*\d{1,7}\s*[）)]?\s*$""")
    private val HEADER_ACTION_TEXTS = arrayOf("设置特别通讯", "添加好友")
    private const val ANCHOR_NETWORK = 1
    private const val ANCHOR_RECORDS = 1 shl 1
    private const val ANCHOR_REQUESTS = 1 shl 2
    private const val BLOCKED = 1 shl 3
    private const val MAIN_ANCHORS = ANCHOR_NETWORK or ANCHOR_RECORDS or ANCHOR_REQUESTS

    @JvmStatic
    fun inspectText(state: Int, value: Any?): Int {
        if (value !is CharSequence) return state

        val text = value.toString()
        var result = state
        if ("同调网络" in text) result = result or ANCHOR_NETWORK
        if ("同调记录" in text) result = result or ANCHOR_RECORDS
        if ("好友申请" in text) result = result or ANCHOR_REQUESTS
        if (
            "设置状态" in text ||
            "好友状态" in text ||
            "同调记录归档" in text ||
            "调取档案" in text ||
            "INITIALIZING" in text
        ) {
            result = result or BLOCKED
        }
        return result
    }

    @JvmStatic
    fun isMainPage(state: Int): Boolean =
        state and BLOCKED == 0 && state and MAIN_ANCHORS == MAIN_ANCHORS

    @JvmStatic
    fun isFriendCountText(value: Any?): Boolean =
        value is CharSequence && FRIEND_COUNT_PATTERN.matches(value)

    @JvmStatic
    fun isHeaderActionText(value: Any?): Boolean =
        value is CharSequence && HEADER_ACTION_TEXTS.any(value::contains)
}
