package io.github.neuralcoherence.probe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

final class LiveUpdateNotification {
    private static final String TAG = "NeuralCoherenceLive";
    private static final String CHANNEL_ID = "interaction_live_updates_v1";
    private static final int NOTIFICATION_ID = 1201;
    private static final String EXTRA_REQUEST_PROMOTED_ONGOING =
            "android.requestPromotedOngoing";
    private static final int ACCENT_COLOR = Color.rgb(44, 220, 239);
    private static String lastCapabilityLog = "";

    private LiveUpdateNotification() {
    }

    static void postSelfCheckProgress(Context context, int current, int total) {
        postProgress(context, "实时通知自检", current, total, current, false);
    }

    static void postProgress(Context sourceContext, String stage, int current,
                             int total, int success, boolean includeStopAction) {
        Context context = sourceContext.getApplicationContext();
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        createChannel(manager);

        String safeStage = stage == null || stage.isBlank() ? "处理好友" : stage;
        boolean indeterminate = total <= 0;
        int safeTotal = Math.max(1, total);
        int safeCurrent = Math.max(0, Math.min(current, safeTotal));
        String title = safeStage.contains("扫描") ? "正在扫描好友" : "同调互动进行中";
        if (safeStage.contains("自检")) {
            title = "实时通知自检";
        }
        String content = indeterminate
                ? "正在获取好友列表"
                : "已完成 " + safeCurrent + "/" + total + " · 成功 " + Math.max(0, success);
        String criticalText = indeterminate ? "扫描中" : safeCurrent + "/" + total;

        Notification.Builder builder = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_sync)
                .setContentTitle(title)
                .setContentText(content)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setColor(ACCENT_COLOR)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setProgress(safeTotal, safeCurrent, indeterminate);

        PendingIntent contentIntent = createContentIntent(context);
        if (contentIntent != null) {
            builder.setContentIntent(contentIntent);
        }
        if (includeStopAction) {
            builder.addAction(new Notification.Action.Builder(
                    (Icon) null, "停止", createStopIntent(context)).build());
        }

        Bundle extras = builder.getExtras();
        extras.putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true);
        boolean progressStyle = applyLiveUpdateApis(
                builder, safeCurrent, safeTotal, indeterminate, criticalText);

        try {
            Notification notification = builder.build();
            boolean allowed = canPostPromoted(manager);
            boolean promotable = hasPromotableCharacteristics(notification);
            logCapabilities(allowed, promotable, progressStyle);
            manager.notify(NOTIFICATION_ID, notification);
        } catch (SecurityException error) {
            Log.w(TAG, "Notification permission is not granted");
        } catch (Throwable error) {
            Log.e(TAG, "Unable to post live update", error);
        }
    }

    static void postCompletionPreview(Context sourceContext, String title, String summary,
                                      int current, int total, String completionLabel) {
        Context context = sourceContext.getApplicationContext();
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        createChannel(manager);

        String safeTitle = title == null || title.isBlank() ? "任务已完成" : title;
        String safeSummary = summary == null || summary.isBlank() ? "任务已完成" : summary;
        String safeLabel = completionLabel == null || completionLabel.isBlank()
                ? "已完成" : completionLabel;
        int safeTotal = Math.max(1, total);
        int safeCurrent = total > 0
                ? Math.max(0, Math.min(current, safeTotal))
                : safeLabel.contains("完成") ? 1 : 0;

        Notification.Builder builder = new Notification.Builder(context, CHANNEL_ID)
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
                .setProgress(safeTotal, safeCurrent, false);
        PendingIntent contentIntent = createContentIntent(context);
        if (contentIntent != null) {
            builder.setContentIntent(contentIntent);
        }

        builder.getExtras().putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true);
        boolean progressStyle = applyLiveUpdateApis(
                builder, safeCurrent, safeTotal, false, safeLabel);
        try {
            Notification notification = builder.build();
            logCapabilities(canPostPromoted(manager),
                    hasPromotableCharacteristics(notification), progressStyle);
            manager.notify(NOTIFICATION_ID, notification);
        } catch (SecurityException error) {
            Log.w(TAG, "Notification permission is not granted");
        } catch (Throwable error) {
            Log.e(TAG, "Unable to post completion preview", error);
        }
    }

    static void postFinished(Context sourceContext, String title, String summary) {
        postFinished(sourceContext, title, summary, summary);
    }

    static void postFinished(Context sourceContext, String title, String summary,
                             String details) {
        Context context = sourceContext.getApplicationContext();
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        createChannel(manager);
        String safeTitle = title == null || title.isBlank() ? "互动任务已结束" : title;
        String safeSummary = summary == null || summary.isBlank() ? "任务已结束" : summary;
        String safeDetails = details == null || details.isBlank() ? safeSummary : details;
        Notification.Builder builder = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_sync)
                .setContentTitle(safeTitle)
                .setContentText(safeSummary)
                .setStyle(new Notification.BigTextStyle().bigText(safeDetails))
                .setCategory(Notification.CATEGORY_STATUS)
                .setColor(ACCENT_COLOR)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setOngoing(false)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(true);
        PendingIntent contentIntent = createContentIntent(context);
        if (contentIntent != null) {
            builder.setContentIntent(contentIntent);
        }
        try {
            manager.notify(NOTIFICATION_ID, builder.build());
        } catch (SecurityException error) {
            Log.w(TAG, "Notification permission is not granted");
        }
    }

    static boolean isLiveUpdateApiAvailable() {
        try {
            Class.forName("android.app.Notification$ProgressStyle");
            Notification.Builder.class.getMethod("setRequestPromotedOngoing", boolean.class);
            NotificationManager.class.getMethod("canPostPromotedNotifications");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean canPostPromoted(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        return manager != null && canPostPromoted(manager);
    }

    private static boolean applyLiveUpdateApis(Notification.Builder builder, int current,
                                               int total, boolean indeterminate,
                                               String criticalText) {
        try {
            Method requestPromotion = Notification.Builder.class.getMethod(
                    "setRequestPromotedOngoing", boolean.class);
            requestPromotion.invoke(builder, true);
            Method setCriticalText = Notification.Builder.class.getMethod(
                    "setShortCriticalText", String.class);
            setCriticalText.invoke(builder, criticalText);

            Class<?> progressStyleClass = Class.forName(
                    "android.app.Notification$ProgressStyle");
            Class<?> segmentClass = Class.forName(
                    "android.app.Notification$ProgressStyle$Segment");
            Object progressStyle = progressStyleClass.getConstructor().newInstance();
            Constructor<?> segmentConstructor = segmentClass.getConstructor(int.class);
            Object segment = segmentConstructor.newInstance(Math.max(1, total));
            segmentClass.getMethod("setColor", int.class).invoke(segment, ACCENT_COLOR);
            progressStyleClass.getMethod("addProgressSegment", segmentClass)
                    .invoke(progressStyle, segment);
            progressStyleClass.getMethod("setProgress", int.class)
                    .invoke(progressStyle, Math.max(0, Math.min(current, total)));
            progressStyleClass.getMethod("setProgressIndeterminate", boolean.class)
                    .invoke(progressStyle, indeterminate);
            builder.setStyle((Notification.Style) progressStyle);
            return true;
        } catch (Throwable error) {
            Log.d(TAG, "ProgressStyle unavailable; using standard progress notification");
            return false;
        }
    }

    private static boolean canPostPromoted(NotificationManager manager) {
        try {
            Method method = NotificationManager.class.getMethod("canPostPromotedNotifications");
            return Boolean.TRUE.equals(method.invoke(manager));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean hasPromotableCharacteristics(Notification notification) {
        try {
            Method method = Notification.class.getMethod("hasPromotableCharacteristics");
            return Boolean.TRUE.equals(method.invoke(notification));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void logCapabilities(boolean allowed, boolean promotable,
                                        boolean progressStyle) {
        String state = "allowed=" + allowed + ", promotable=" + promotable
                + ", progressStyle=" + progressStyle;
        if (!state.equals(lastCapabilityLog)) {
            lastCapabilityLog = state;
            Log.i(TAG, state);
        }
    }

    private static void createChannel(NotificationManager manager) {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "同调互动实时进度", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("显示扫描和批量互动的实时进度");
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        manager.createNotificationChannel(channel);
    }

    private static PendingIntent createContentIntent(Context context) {
        Intent launchIntent = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(new ComponentName(
                        LiveUpdateContract.TARGET_PACKAGE,
                        LiveUpdateContract.TARGET_ACTIVITY))
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(context, 1201, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent createStopIntent(Context context) {
        Intent intent = new Intent(context, LiveUpdateStopReceiver.class)
                .setAction(LiveUpdateContract.ACTION_STOP_CLICKED);
        return PendingIntent.getBroadcast(context, 1202, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
