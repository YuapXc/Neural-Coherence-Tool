package io.github.neuralcoherence.probe

import android.app.Activity
import android.app.BroadcastOptions
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock

internal object LiveUpdateClient {
    private const val MIN_UPDATE_INTERVAL_MS = 900L
    private var lastUpdateAt = 0L
    private var lastStage = ""
    private var lastCurrent = -1

    @JvmStatic
    fun openSettings(activity: Activity): Boolean {
        val intent = Intent().apply {
            component = ComponentName(
                LiveUpdateContract.MODULE_PACKAGE,
                "${LiveUpdateContract.MODULE_PACKAGE}.NotificationPermissionActivity",
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            activity.startActivity(intent)
            true
        } catch (_: Throwable) {
            false
        }
    }

    @JvmStatic
    fun update(
        context: Context,
        stage: String,
        current: Int,
        total: Int,
        success: Int,
        force: Boolean,
    ) = update(context, stage, current, total, success, force, true)

    @JvmStatic
    fun update(
        context: Context,
        stage: String,
        current: Int,
        total: Int,
        success: Int,
        force: Boolean,
        allowStop: Boolean,
    ) {
        if (Build.VERSION.SDK_INT < 34) return
        val now = SystemClock.elapsedRealtime()
        val changedStage = stage != lastStage
        if (!force && !changedStage && current == lastCurrent) return
        if (!force && !changedStage && now - lastUpdateAt < MIN_UPDATE_INTERVAL_MS) return

        lastUpdateAt = now
        lastStage = stage
        lastCurrent = current
        val intent = Intent(LiveUpdateContract.ACTION_UPDATE)
            .setPackage(LiveUpdateContract.MODULE_PACKAGE)
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            .putExtra(LiveUpdateContract.EXTRA_STAGE, stage)
            .putExtra(LiveUpdateContract.EXTRA_CURRENT, current.coerceAtLeast(0))
            .putExtra(LiveUpdateContract.EXTRA_TOTAL, total.coerceAtLeast(0))
            .putExtra(LiveUpdateContract.EXTRA_SUCCESS, success.coerceAtLeast(0))
            .putExtra(LiveUpdateContract.EXTRA_ALLOW_STOP, allowStop)
        sendWithIdentity(context.applicationContext, intent)
    }

    @JvmStatic
    fun finish(
        context: Context,
        title: String,
        summary: String,
        details: String,
        current: Int,
        total: Int,
        completionLabel: String,
    ) {
        if (Build.VERSION.SDK_INT < 34) return
        lastStage = ""
        lastCurrent = -1
        val intent = Intent(LiveUpdateContract.ACTION_FINISH)
            .setPackage(LiveUpdateContract.MODULE_PACKAGE)
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            .putExtra(LiveUpdateContract.EXTRA_TITLE, title)
            .putExtra(LiveUpdateContract.EXTRA_SUMMARY, summary)
            .putExtra(LiveUpdateContract.EXTRA_DETAILS, details)
            .putExtra(LiveUpdateContract.EXTRA_CURRENT, current.coerceAtLeast(0))
            .putExtra(LiveUpdateContract.EXTRA_TOTAL, total.coerceAtLeast(0))
            .putExtra(LiveUpdateContract.EXTRA_COMPLETION_LABEL, completionLabel)
        sendWithIdentity(context.applicationContext, intent)
    }

    @JvmStatic
    fun isTrustedModuleSender(receiver: BroadcastReceiver): Boolean =
        Build.VERSION.SDK_INT >= 34 &&
            LiveUpdateContract.MODULE_PACKAGE == receiver.sentFromPackage

    @JvmStatic
    fun sendWithIdentity(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT < 34) return
        val options = BroadcastOptions.makeBasic().setShareIdentityEnabled(true)
        context.sendBroadcast(intent, null, options.toBundle())
    }
}
