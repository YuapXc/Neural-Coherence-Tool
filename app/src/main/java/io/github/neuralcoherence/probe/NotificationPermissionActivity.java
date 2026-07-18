package io.github.neuralcoherence.probe;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class NotificationPermissionActivity extends Activity {
    private static final int REQUEST_NOTIFICATIONS = 1201;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private Button permissionButton;
    private Button promotionButton;
    private Button selfCheckButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(9, 11, 12));
        getWindow().setNavigationBarColor(Color.BLACK);
        setContentView(createContent());
        if (!hasNotificationPermission()) {
            requestNotificationPermission();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NOTIFICATIONS) {
            refreshStatus();
        }
    }

    private LinearLayout createContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24), dp(48), dp(24), dp(24));
        root.setBackgroundColor(Color.rgb(9, 11, 12));
        root.setFitsSystemWindows(true);

        TextView title = new TextView(this);
        title.setText("同调互动 · 实时通知");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap(dp(48)));

        TextView description = new TextView(this);
        description.setText("批量互动运行时显示聚合进度，不展示好友姓名、ID 或账号数据。");
        description.setTextColor(Color.rgb(174, 184, 186));
        description.setTextSize(14);
        description.setGravity(Gravity.CENTER);
        description.setPadding(0, dp(8), 0, dp(20));
        root.addView(description, matchWrap(ViewGroup.LayoutParams.WRAP_CONTENT));

        status = new TextView(this);
        status.setTextColor(Color.rgb(71, 226, 244));
        status.setTextSize(15);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(12), dp(16), dp(12), dp(16));
        root.addView(status, matchWrap(ViewGroup.LayoutParams.WRAP_CONTENT));

        permissionButton = actionButton("允许普通通知", Color.rgb(0, 105, 125));
        permissionButton.setOnClickListener(view -> requestNotificationPermission());
        root.addView(permissionButton, buttonParams());

        promotionButton = actionButton("允许实时更新提升", Color.rgb(0, 105, 125));
        promotionButton.setOnClickListener(view -> openPromotionSettings());
        root.addView(promotionButton, buttonParams());

        selfCheckButton = actionButton("运行 10 秒自检", Color.rgb(151, 79, 0));
        selfCheckButton.setOnClickListener(view -> runNotificationSelfCheck());
        root.addView(selfCheckButton, buttonParams());

        Button closeButton = actionButton("完成", Color.rgb(48, 54, 56));
        closeButton.setOnClickListener(view -> finish());
        root.addView(closeButton, buttonParams());
        return root;
    }

    private void refreshStatus() {
        if (status == null) {
            return;
        }
        boolean notificationAllowed = hasNotificationPermission();
        boolean apiAvailable = LiveUpdateNotification.isLiveUpdateApiAvailable();
        boolean promotionAllowed = apiAvailable
                && LiveUpdateNotification.canPostPromoted(this);
        String promotionState = !apiAvailable ? "系统未提供"
                : promotionAllowed ? "已允许" : "尚未允许";
        status.setText("普通通知：" + (notificationAllowed ? "已允许" : "尚未允许")
                + "\n实时更新提升：" + promotionState
                + "\n\n若系统未提升展示，将自动使用普通进度通知。");
        permissionButton.setEnabled(!notificationAllowed);
        permissionButton.setAlpha(notificationAllowed ? 0.45f : 1f);
        promotionButton.setEnabled(notificationAllowed && apiAvailable && !promotionAllowed);
        promotionButton.setAlpha(promotionButton.isEnabled() ? 1f : 0.45f);
        selfCheckButton.setEnabled(notificationAllowed);
        selfCheckButton.setAlpha(notificationAllowed ? 1f : 0.45f);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && !hasNotificationPermission()) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATIONS);
        } else {
            refreshStatus();
        }
    }

    private boolean hasNotificationPermission() {
        return Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void openPromotionSettings() {
        Intent intent = new Intent("android.settings.APP_NOTIFICATION_PROMOTION_SETTINGS")
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        try {
            startActivity(intent);
        } catch (Throwable unavailable) {
            Intent fallback = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(fallback);
        }
    }

    private void runNotificationSelfCheck() {
        android.content.Context context = getApplicationContext();
        for (int step = 0; step <= 10; step++) {
            int current = step;
            handler.postDelayed(() -> {
                LiveUpdateNotification.postSelfCheckProgress(context, current, 10);
                if (current == 10) {
                    handler.postDelayed(() -> LiveUpdateNotification.postFinished(
                            context, "实时通知自检完成", "Android 通知进度链路工作正常"),
                            800L);
                }
            }, step * 800L);
        }
    }

    private Button actionButton(String text, int fillColor) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(15);
        button.setAllCaps(false);
        GradientDrawable background = new GradientDrawable();
        background.setColor(fillColor);
        background.setCornerRadius(dp(6));
        background.setStroke(dp(1), Color.rgb(73, 226, 242));
        button.setBackground(background);
        return button;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        params.topMargin = dp(10);
        return params;
    }

    private LinearLayout.LayoutParams matchWrap(int height) {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

}
