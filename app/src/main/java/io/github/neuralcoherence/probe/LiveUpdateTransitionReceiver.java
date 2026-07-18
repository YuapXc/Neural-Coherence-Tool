package io.github.neuralcoherence.probe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class LiveUpdateTransitionReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!LiveUpdateContract.ACTION_COMPLETE_TRANSITION.equals(intent.getAction())) {
            return;
        }
        LiveUpdateTransition.complete(
                context,
                intent.getStringExtra(LiveUpdateContract.EXTRA_TITLE),
                intent.getStringExtra(LiveUpdateContract.EXTRA_SUMMARY),
                intent.getStringExtra(LiveUpdateContract.EXTRA_DETAILS),
                intent.getLongExtra(LiveUpdateContract.EXTRA_TRANSITION_TOKEN, 0L));
    }
}
