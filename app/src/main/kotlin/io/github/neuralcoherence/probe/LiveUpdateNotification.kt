package io.github.neuralcoherence.probe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Bundle
import android.util.Log
import kotlin.math.max
import kotlin.math.min

internal object LiveUpdateNotification {
    private const val TAG = "NeuralCoherenceLive"
    private const val CHANNEL_ID = "interaction_live_updates_v1"
    private const val NOTIFICATION_ID = 1201
    private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
    private val ACCENT_COLOR = Color.rgb(44, 220, 239)

    @Volatile
    private var lastCapabilityLog = ""

    @JvmStatic
    fun postSelfCheckProgress(context: Context, current: Int, total: Int) {
        postProgress(context, "实时通知自检", current, total, current, false)
    }

    @JvmStatic
    fun postProgress(
        sourceContext: Context,
        stage: String?,
        current: Int,
        total: Int,
        success: Int,
        includeStopAction: Boolean,
    ) {
        val context = sourceContext.applicationContext
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        createChannel(manager)

        val safeStage = stage?.takeIf { it.isNotBlank() } ?: "处理好友"
        val indeterminate = total <= 0
        val safeTotal = max(1, total)
        val safeCurrent = max(0, min(current, safeTotal))
        val title = when {
            safeStage.contains("自检") -> "实时通知自检"
            safeStage.contains("扫描") -> "正在扫描好友"
            else -> "同调互动进行中"
        }
        val content = if (indeterminate) {
            "正在获取好友列表"
        } else {
            "已完成 $safeCurrent/$total · 成功 ${max(0, success)}"
        }
        val criticalText = if (indeterminate) "扫描中" else "$safeCurrent/$total"

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_sync)
            .setContentTitle(title)
            .setContentText(content)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setColor(ACCENT_COLOR)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setProgress(safeTotal, safeCurrent, indeterminate)

        createContentIntent(context)?.let(builder::setContentIntent)
        if (includeStopAction) {
            builder.addAction(
                Notification.Action.Builder(null as Icon?, "停止", createStopIntent(context)).build(),
            )
        }
        builder.extras.putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true)
        val progressStyle = applyLiveUpdateApis(
            builder,
            safeCurrent,
            safeTotal,
            indeterminate,
            criticalText,
        )

        try {
            val notification = builder.build()
            logCapabilities(
                canPostPromoted(manager),
                hasPromotableCharacteristics(notification),
                progressStyle,
            )
            manager.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            Log.w(TAG, "Notification permission is not granted")
        } catch (error: Throwable) {
            Log.e(TAG, "Unable to post live update", error)
        }
    }

    @JvmStatic
    fun postCompletionPreview(
        sourceContext: Context,
        title: String?,
        summary: String?,
        current: Int,
        total: Int,
        completionLabel: String?,
    ) {
        val context = sourceContext.applicationContext
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        createChannel(manager)

        val safeTitle = title?.takeIf { it.isNotBlank() } ?: "任务已完成"
        val safeSummary = summary?.takeIf { it.isNotBlank() } ?: "任务已完成"
        val safeLabel = completionLabel?.takeIf { it.isNotBlank() } ?: "已完成"
        val safeTotal = max(1, total)
        val safeCurrent = if (total > 0) {
            max(0, min(current, safeTotal))
        } else if (safeLabel.contains("完成")) {
            1
        } else {
            0
        }

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_sync)
            .setContentTitle(safeTitle)
            .setContentText(safeSummary)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setColor(ACCENT_COLOR)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setTimeoutAfter(LiveUpdateContract.COMPLETION_PREVIEW_MILLIS)
            .setProgress(safeTotal, safeCurrent, false)
        createContentIntent(context)?.let(builder::setContentIntent)
        builder.extras.putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true)
        val progressStyle = applyLiveUpdateApis(builder, safeCurrent, safeTotal, false, safeLabel)
        try {
            val notification = builder.build()
            logCapabilities(
                canPostPromoted(manager),
                hasPromotableCharacteristics(notification),
                progressStyle,
            )
            manager.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            Log.w(TAG, "Notification permission is not granted")
        } catch (error: Throwable) {
            Log.e(TAG, "Unable to post completion preview", error)
        }
    }

    @JvmStatic
    fun postFinished(sourceContext: Context, title: String?, summary: String?) {
        postFinished(sourceContext, title, summary, summary)
    }

    @JvmStatic
    fun postFinished(
        sourceContext: Context,
        title: String?,
        summary: String?,
        details: String?,
    ) {
        val context = sourceContext.applicationContext
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        createChannel(manager)
        val safeTitle = title?.takeIf { it.isNotBlank() } ?: "互动任务已结束"
        val safeSummary = summary?.takeIf { it.isNotBlank() } ?: "任务已结束"
        val safeDetails = details?.takeIf { it.isNotBlank() } ?: safeSummary
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_sync)
            .setContentTitle(safeTitle)
            .setContentText(safeSummary)
            .setStyle(Notification.BigTextStyle().bigText(safeDetails))
            .setCategory(Notification.CATEGORY_STATUS)
            .setColor(ACCENT_COLOR)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOngoing(false)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
        createContentIntent(context)?.let(builder::setContentIntent)
        try {
            manager.notify(NOTIFICATION_ID, builder.build())
        } catch (_: SecurityException) {
            Log.w(TAG, "Notification permission is not granted")
        }
    }

    @JvmStatic
    fun isLiveUpdateApiAvailable(): Boolean = try {
        Class.forName("android.app.Notification\$ProgressStyle")
        Notification.Builder::class.java.getMethod("setRequestPromotedOngoing", Boolean::class.javaPrimitiveType)
        NotificationManager::class.java.getMethod("canPostPromotedNotifications")
        true
    } catch (_: Throwable) {
        false
    }

    @JvmStatic
    fun canPostPromoted(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return canPostPromoted(manager)
    }

    private fun applyLiveUpdateApis(
        builder: Notification.Builder,
        current: Int,
        total: Int,
        indeterminate: Boolean,
        criticalText: String,
    ): Boolean = try {
        Notification.Builder::class.java
            .getMethod("setRequestPromotedOngoing", Boolean::class.javaPrimitiveType)
            .invoke(builder, true)
        Notification.Builder::class.java
            .getMethod("setShortCriticalText", String::class.java)
            .invoke(builder, criticalText)

        val progressStyleClass = Class.forName("android.app.Notification\$ProgressStyle")
        val segmentClass = Class.forName("android.app.Notification\$ProgressStyle\$Segment")
        val progressStyle = progressStyleClass.getConstructor().newInstance()
        val segment = segmentClass.getConstructor(Int::class.javaPrimitiveType).newInstance(max(1, total))
        segmentClass.getMethod("setColor", Int::class.javaPrimitiveType).invoke(segment, ACCENT_COLOR)
        progressStyleClass.getMethod("addProgressSegment", segmentClass).invoke(progressStyle, segment)
        progressStyleClass.getMethod("setProgress", Int::class.javaPrimitiveType)
            .invoke(progressStyle, max(0, min(current, total)))
        progressStyleClass.getMethod("setProgressIndeterminate", Boolean::class.javaPrimitiveType)
            .invoke(progressStyle, indeterminate)
        builder.setStyle(progressStyle as Notification.Style)
        true
    } catch (_: Throwable) {
        Log.d(TAG, "ProgressStyle unavailable; using standard progress notification")
        false
    }

    private fun canPostPromoted(manager: NotificationManager): Boolean = try {
        NotificationManager::class.java.getMethod("canPostPromotedNotifications")
            .invoke(manager) == true
    } catch (_: Throwable) {
        false
    }

    private fun hasPromotableCharacteristics(notification: Notification): Boolean = try {
        Notification::class.java.getMethod("hasPromotableCharacteristics")
            .invoke(notification) == true
    } catch (_: Throwable) {
        false
    }

    @Synchronized
    private fun logCapabilities(allowed: Boolean, promotable: Boolean, progressStyle: Boolean) {
        val state = "allowed=$allowed, promotable=$promotable, progressStyle=$progressStyle"
        if (state != lastCapabilityLog) {
            lastCapabilityLog = state
            Log.i(TAG, state)
        }
    }

    private fun createChannel(manager: NotificationManager) {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "同调互动实时进度",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "显示扫描和批量互动的实时进度"
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannel(channel)
    }

    private fun createContentIntent(context: Context): PendingIntent? {
        val launchIntent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(LiveUpdateContract.TARGET_PACKAGE, LiveUpdateContract.TARGET_ACTIVITY))
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            1201,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createStopIntent(context: Context): PendingIntent {
        val intent = Intent(context, LiveUpdateStopReceiver::class.java)
            .setAction(LiveUpdateContract.ACTION_STOP_CLICKED)
        return PendingIntent.getBroadcast(
            context,
            1202,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
