package io.github.neuralcoherence.probe

internal object LiveUpdateContract {
    const val MODULE_PACKAGE = "io.github.neuralcoherence.probe"
    const val TARGET_PACKAGE = "com.linktech.arkradar"
    const val TARGET_ACTIVITY = "$TARGET_PACKAGE.MainActivity"

    const val ACTION_UPDATE = "$MODULE_PACKAGE.action.UPDATE_LIVE_STATUS"
    const val ACTION_FINISH = "$MODULE_PACKAGE.action.FINISH_LIVE_STATUS"
    const val ACTION_COMPLETE_TRANSITION = "$MODULE_PACKAGE.action.COMPLETE_LIVE_TRANSITION"
    const val ACTION_STOP_CLICKED = "$MODULE_PACKAGE.action.STOP_CLICKED"
    const val ACTION_STOP_TARGET = "$MODULE_PACKAGE.action.STOP_TARGET_INTERACTION"

    const val EXTRA_STAGE = "stage"
    const val EXTRA_CURRENT = "current"
    const val EXTRA_TOTAL = "total"
    const val EXTRA_SUCCESS = "success"
    const val EXTRA_ALLOW_STOP = "allow_stop"
    const val EXTRA_TITLE = "title"
    const val EXTRA_SUMMARY = "summary"
    const val EXTRA_DETAILS = "details"
    const val EXTRA_COMPLETION_LABEL = "completion_label"
    const val EXTRA_TRANSITION_TOKEN = "transition_token"

    const val COMPLETION_PREVIEW_MILLIS = 60_000L
}
