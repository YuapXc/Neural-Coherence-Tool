package io.github.neuralcoherence.probe;

import android.app.Activity;
import android.app.BroadcastOptions;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;

final class LiveUpdateClient {
    private static final long MIN_UPDATE_INTERVAL_MS = 900L;
    private static long lastUpdateAt;
    private static String lastStage = "";
    private static int lastCurrent = -1;

    private LiveUpdateClient() {
    }

    static boolean openSettings(Activity activity) {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(
                LiveUpdateContract.MODULE_PACKAGE,
                LiveUpdateContract.MODULE_PACKAGE + ".NotificationPermissionActivity"));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            activity.startActivity(intent);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static void update(Context context, String stage, int current, int total,
                       int success, boolean force) {
        update(context, stage, current, total, success, force, true);
    }

    static void update(Context context, String stage, int current, int total,
                       int success, boolean force, boolean allowStop) {
        if (Build.VERSION.SDK_INT < 34) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        boolean changedStage = !stage.equals(lastStage);
        if (!force && !changedStage && current == lastCurrent) {
            return;
        }
        if (!force && !changedStage && now - lastUpdateAt < MIN_UPDATE_INTERVAL_MS) {
            return;
        }
        lastUpdateAt = now;
        lastStage = stage;
        lastCurrent = current;

        Intent intent = new Intent(LiveUpdateContract.ACTION_UPDATE)
                .setPackage(LiveUpdateContract.MODULE_PACKAGE)
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                .putExtra(LiveUpdateContract.EXTRA_STAGE, stage)
                .putExtra(LiveUpdateContract.EXTRA_CURRENT, Math.max(0, current))
                .putExtra(LiveUpdateContract.EXTRA_TOTAL, Math.max(0, total))
                .putExtra(LiveUpdateContract.EXTRA_SUCCESS, Math.max(0, success))
                .putExtra(LiveUpdateContract.EXTRA_ALLOW_STOP, allowStop);
        sendWithIdentity(context.getApplicationContext(), intent);
    }

    static void finish(Context context, String title, String summary, String details,
                       int current, int total, String completionLabel) {
        if (Build.VERSION.SDK_INT < 34) {
            return;
        }
        lastStage = "";
        lastCurrent = -1;
        Intent intent = new Intent(LiveUpdateContract.ACTION_FINISH)
                .setPackage(LiveUpdateContract.MODULE_PACKAGE)
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                .putExtra(LiveUpdateContract.EXTRA_TITLE, title)
                .putExtra(LiveUpdateContract.EXTRA_SUMMARY, summary)
                .putExtra(LiveUpdateContract.EXTRA_DETAILS, details)
                .putExtra(LiveUpdateContract.EXTRA_CURRENT, Math.max(0, current))
                .putExtra(LiveUpdateContract.EXTRA_TOTAL, Math.max(0, total))
                .putExtra(LiveUpdateContract.EXTRA_COMPLETION_LABEL, completionLabel);
        sendWithIdentity(context.getApplicationContext(), intent);
    }

    static boolean isTrustedModuleSender(BroadcastReceiver receiver) {
        if (Build.VERSION.SDK_INT < 34) {
            return false;
        }
        return LiveUpdateContract.MODULE_PACKAGE.equals(receiver.getSentFromPackage());
    }

    static void sendWithIdentity(Context context, Intent intent) {
        if (Build.VERSION.SDK_INT < 34) {
            return;
        }
        BroadcastOptions options = BroadcastOptions.makeBasic()
                .setShareIdentityEnabled(true);
        context.sendBroadcast(intent, null, options.toBundle());
    }
}
