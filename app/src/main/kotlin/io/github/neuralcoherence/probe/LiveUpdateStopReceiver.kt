package io.github.neuralcoherence.probe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class LiveUpdateStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != LiveUpdateContract.ACTION_STOP_CLICKED) return
        LiveUpdateClient.sendWithIdentity(
            context,
            Intent(LiveUpdateContract.ACTION_STOP_TARGET)
                .setPackage(LiveUpdateContract.TARGET_PACKAGE),
        )
    }
}
