package io.github.neuralcoherence.probe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class LiveUpdateStopReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!LiveUpdateContract.ACTION_STOP_CLICKED.equals(intent.getAction())) {
            return;
        }
        LiveUpdateClient.sendWithIdentity(context, new Intent(
                LiveUpdateContract.ACTION_STOP_TARGET)
                .setPackage(LiveUpdateContract.TARGET_PACKAGE));
    }
}
