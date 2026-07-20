package io.github.neuralcoherence.probe

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.SystemClock

internal object LiveUpdateTransition {
    private const val PREFS_NAME = "live_update_transition"
    private const val PREF_TOKEN = "token"
    private const val REQUEST_CODE = 1203

    fun schedule(context: Context, title: String?, summary: String?, details: String?): Long {
        val appContext = context.applicationContext
        val token = SystemClock.elapsedRealtimeNanos()
        preferences(appContext).edit().putLong(PREF_TOKEN, token).commit()
        val intent = transitionIntent(appContext)
            .putExtra(LiveUpdateContract.EXTRA_TRANSITION_TOKEN, token)
            .putExtra(LiveUpdateContract.EXTRA_TITLE, title)
            .putExtra(LiveUpdateContract.EXTRA_SUMMARY, summary)
            .putExtra(LiveUpdateContract.EXTRA_DETAILS, details)
        val pendingIntent = PendingIntent.getBroadcast(
            appContext,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        appContext.getSystemService(AlarmManager::class.java)?.set(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + LiveUpdateContract.COMPLETION_PREVIEW_MILLIS,
            pendingIntent,
        )
        return token
    }

    fun complete(
        context: Context,
        title: String?,
        summary: String?,
        details: String?,
        token: Long,
    ) {
        val appContext = context.applicationContext
        if (token == 0L || preferences(appContext).getLong(PREF_TOKEN, 0L) != token) return
        cancelAlarm(appContext)
        preferences(appContext).edit().remove(PREF_TOKEN).apply()
        LiveUpdateNotification.postFinished(appContext, title, summary, details)
    }

    fun cancel(context: Context) {
        val appContext = context.applicationContext
        cancelAlarm(appContext)
        preferences(appContext).edit().remove(PREF_TOKEN).apply()
    }

    private fun cancelAlarm(context: Context) {
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            transitionIntent(context),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    private fun transitionIntent(context: Context): Intent =
        Intent(context, LiveUpdateTransitionReceiver::class.java)
            .setAction(LiveUpdateContract.ACTION_COMPLETE_TRANSITION)

    private fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
