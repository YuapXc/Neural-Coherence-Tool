package io.github.neuralcoherence.probe;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;

final class LiveUpdateTransition {
    private static final String PREFS_NAME = "live_update_transition";
    private static final String PREF_TOKEN = "token";
    private static final int REQUEST_CODE = 1203;

    private LiveUpdateTransition() {
    }

    static long schedule(Context sourceContext, String title, String summary,
                         String details) {
        Context context = sourceContext.getApplicationContext();
        long token = SystemClock.elapsedRealtimeNanos();
        preferences(context).edit().putLong(PREF_TOKEN, token).commit();

        Intent intent = transitionIntent(context)
                .putExtra(LiveUpdateContract.EXTRA_TRANSITION_TOKEN, token)
                .putExtra(LiveUpdateContract.EXTRA_TITLE, title)
                .putExtra(LiveUpdateContract.EXTRA_SUMMARY, summary)
                .putExtra(LiveUpdateContract.EXTRA_DETAILS, details);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager manager = context.getSystemService(AlarmManager.class);
        if (manager != null) {
            manager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime()
                            + LiveUpdateContract.COMPLETION_PREVIEW_MILLIS,
                    pendingIntent);
        }
        return token;
    }

    static void complete(Context sourceContext, String title, String summary,
                         String details, long token) {
        Context context = sourceContext.getApplicationContext();
        if (token == 0L || preferences(context).getLong(PREF_TOKEN, 0L) != token) {
            return;
        }
        cancelAlarm(context);
        preferences(context).edit().remove(PREF_TOKEN).apply();
        LiveUpdateNotification.postFinished(context, title, summary, details);
    }

    static void cancel(Context sourceContext) {
        Context context = sourceContext.getApplicationContext();
        cancelAlarm(context);
        preferences(context).edit().remove(PREF_TOKEN).apply();
    }

    private static void cancelAlarm(Context context) {
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context, REQUEST_CODE, transitionIntent(context),
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (pendingIntent == null) {
            return;
        }
        AlarmManager manager = context.getSystemService(AlarmManager.class);
        if (manager != null) {
            manager.cancel(pendingIntent);
        }
        pendingIntent.cancel();
    }

    private static Intent transitionIntent(Context context) {
        return new Intent(context, LiveUpdateTransitionReceiver.class)
                .setAction(LiveUpdateContract.ACTION_COMPLETE_TRANSITION);
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
