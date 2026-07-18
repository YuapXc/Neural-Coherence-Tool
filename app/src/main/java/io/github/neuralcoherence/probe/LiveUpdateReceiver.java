package io.github.neuralcoherence.probe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

public final class LiveUpdateReceiver extends BroadcastReceiver {
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static Runnable pendingTransition;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Build.VERSION.SDK_INT < 34
                || !LiveUpdateContract.TARGET_PACKAGE.equals(getSentFromPackage())) {
            return;
        }

        String action = intent.getAction();
        if (LiveUpdateContract.ACTION_UPDATE.equals(action)) {
            cancelPendingTransition(context.getApplicationContext());
            LiveUpdateNotification.postProgress(
                    context,
                    intent.getStringExtra(LiveUpdateContract.EXTRA_STAGE),
                    intent.getIntExtra(LiveUpdateContract.EXTRA_CURRENT, 0),
                    intent.getIntExtra(LiveUpdateContract.EXTRA_TOTAL, 0),
                    intent.getIntExtra(LiveUpdateContract.EXTRA_SUCCESS, 0),
                    intent.getBooleanExtra(LiveUpdateContract.EXTRA_ALLOW_STOP, true));
        } else if (LiveUpdateContract.ACTION_FINISH.equals(action)) {
            beginCompletionTransition(context.getApplicationContext(), intent);
        }
    }

    private void beginCompletionTransition(Context context, Intent intent) {
        cancelPendingTransition(context);
        String title = intent.getStringExtra(LiveUpdateContract.EXTRA_TITLE);
        String summary = intent.getStringExtra(LiveUpdateContract.EXTRA_SUMMARY);
        String details = intent.getStringExtra(LiveUpdateContract.EXTRA_DETAILS);
        int current = intent.getIntExtra(LiveUpdateContract.EXTRA_CURRENT, 0);
        int total = intent.getIntExtra(LiveUpdateContract.EXTRA_TOTAL, 0);
        String completionLabel = intent.getStringExtra(
                LiveUpdateContract.EXTRA_COMPLETION_LABEL);

        LiveUpdateNotification.postCompletionPreview(
                context, title, summary, current, total, completionLabel);
        long token = LiveUpdateTransition.schedule(
                context, title, summary, details);
        Runnable transition = () -> {
            pendingTransition = null;
            LiveUpdateTransition.complete(
                    context, title, summary, details, token);
        };
        pendingTransition = transition;
        MAIN_HANDLER.postDelayed(
                transition, LiveUpdateContract.COMPLETION_PREVIEW_MILLIS);
    }

    private static void cancelPendingTransition(Context context) {
        if (pendingTransition != null) {
            MAIN_HANDLER.removeCallbacks(pendingTransition);
            pendingTransition = null;
        }
        LiveUpdateTransition.cancel(context);
    }
}
