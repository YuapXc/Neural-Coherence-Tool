package io.github.neuralcoherence.probe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class LiveUpdateTransitionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != LiveUpdateContract.ACTION_COMPLETE_TRANSITION) return
        LiveUpdateTransition.complete(
            context,
            intent.getStringExtra(LiveUpdateContract.EXTRA_TITLE),
            intent.getStringExtra(LiveUpdateContract.EXTRA_SUMMARY),
            intent.getStringExtra(LiveUpdateContract.EXTRA_DETAILS),
            intent.getLongExtra(LiveUpdateContract.EXTRA_TRANSITION_TOKEN, 0L),
        )
    }
}
