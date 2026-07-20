package io.github.neuralcoherence.probe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper

class LiveUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (
            Build.VERSION.SDK_INT < 34 ||
            LiveUpdateContract.TARGET_PACKAGE != sentFromPackage
        ) {
            return
        }

        when (intent.action) {
            LiveUpdateContract.ACTION_UPDATE -> {
                cancelPendingTransition(context.applicationContext)
                LiveUpdateNotification.postProgress(
                    context,
                    intent.getStringExtra(LiveUpdateContract.EXTRA_STAGE),
                    intent.getIntExtra(LiveUpdateContract.EXTRA_CURRENT, 0),
                    intent.getIntExtra(LiveUpdateContract.EXTRA_TOTAL, 0),
                    intent.getIntExtra(LiveUpdateContract.EXTRA_SUCCESS, 0),
                    intent.getBooleanExtra(LiveUpdateContract.EXTRA_ALLOW_STOP, true),
                )
            }

            LiveUpdateContract.ACTION_FINISH ->
                beginCompletionTransition(context.applicationContext, intent)
        }
    }

    private fun beginCompletionTransition(context: Context, intent: Intent) {
        cancelPendingTransition(context)
        val title = intent.getStringExtra(LiveUpdateContract.EXTRA_TITLE)
        val summary = intent.getStringExtra(LiveUpdateContract.EXTRA_SUMMARY)
        val details = intent.getStringExtra(LiveUpdateContract.EXTRA_DETAILS)
        val current = intent.getIntExtra(LiveUpdateContract.EXTRA_CURRENT, 0)
        val total = intent.getIntExtra(LiveUpdateContract.EXTRA_TOTAL, 0)
        val completionLabel = intent.getStringExtra(LiveUpdateContract.EXTRA_COMPLETION_LABEL)

        LiveUpdateNotification.postCompletionPreview(
            context,
            title,
            summary,
            current,
            total,
            completionLabel,
        )
        val token = LiveUpdateTransition.schedule(context, title, summary, details)
        val transition = Runnable {
            pendingTransition = null
            LiveUpdateTransition.complete(context, title, summary, details, token)
        }
        pendingTransition = transition
        MAIN_HANDLER.postDelayed(transition, LiveUpdateContract.COMPLETION_PREVIEW_MILLIS)
    }

    companion object {
        private val MAIN_HANDLER = Handler(Looper.getMainLooper())
        private var pendingTransition: Runnable? = null

        private fun cancelPendingTransition(context: Context) {
            pendingTransition?.let(MAIN_HANDLER::removeCallbacks)
            pendingTransition = null
            LiveUpdateTransition.cancel(context)
        }
    }
}
