package io.github.neuralcoherence.probe

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class NotificationPermissionActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var permissionButton: Button
    private lateinit var promotionButton: Button
    private lateinit var selfCheckButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(9, 11, 12)
        window.navigationBarColor = Color.BLACK
        setContentView(createContent())
        if (!hasNotificationPermission()) requestNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATIONS) refreshStatus()
    }

    private fun createContent() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(24), dp(48), dp(24), dp(24))
        setBackgroundColor(Color.rgb(9, 11, 12))
        fitsSystemWindows = true

        addView(TextView(this@NotificationPermissionActivity).apply {
            text = "同调互动 · 实时通知"
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
        }, matchWrap(dp(48)))

        addView(TextView(this@NotificationPermissionActivity).apply {
            text = "批量互动运行时显示聚合进度，不展示好友姓名、ID 或账号数据。"
            setTextColor(Color.rgb(174, 184, 186))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(20))
        }, matchWrap(ViewGroup.LayoutParams.WRAP_CONTENT))

        status = TextView(this@NotificationPermissionActivity).apply {
            setTextColor(Color.rgb(71, 226, 244))
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(16), dp(12), dp(16))
        }
        addView(status, matchWrap(ViewGroup.LayoutParams.WRAP_CONTENT))

        permissionButton = actionButton("允许普通通知", Color.rgb(0, 105, 125)).apply {
            setOnClickListener { requestNotificationPermission() }
        }
        addView(permissionButton, buttonParams())

        promotionButton = actionButton("允许实时更新提升", Color.rgb(0, 105, 125)).apply {
            setOnClickListener { openPromotionSettings() }
        }
        addView(promotionButton, buttonParams())

        selfCheckButton = actionButton("运行 10 秒自检", Color.rgb(151, 79, 0)).apply {
            setOnClickListener { runNotificationSelfCheck() }
        }
        addView(selfCheckButton, buttonParams())

        addView(actionButton("完成", Color.rgb(48, 54, 56)).apply {
            setOnClickListener { finish() }
        }, buttonParams())
    }

    private fun refreshStatus() {
        if (!::status.isInitialized) return
        val notificationAllowed = hasNotificationPermission()
        val apiAvailable = LiveUpdateNotification.isLiveUpdateApiAvailable()
        val promotionAllowed = apiAvailable && LiveUpdateNotification.canPostPromoted(this)
        val promotionState = when {
            !apiAvailable -> "系统未提供"
            promotionAllowed -> "已允许"
            else -> "尚未允许"
        }
        status.text = "普通通知：${if (notificationAllowed) "已允许" else "尚未允许"}" +
            "\n实时更新提升：$promotionState" +
            "\n\n若系统未提升展示，将自动使用普通进度通知。"
        permissionButton.isEnabled = !notificationAllowed
        permissionButton.alpha = if (notificationAllowed) 0.45f else 1f
        promotionButton.isEnabled = notificationAllowed && apiAvailable && !promotionAllowed
        promotionButton.alpha = if (promotionButton.isEnabled) 1f else 0.45f
        selfCheckButton.isEnabled = notificationAllowed
        selfCheckButton.alpha = if (notificationAllowed) 1f else 0.45f
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && !hasNotificationPermission()) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        } else {
            refreshStatus()
        }
    }

    private fun hasNotificationPermission(): Boolean = Build.VERSION.SDK_INT < 33 ||
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openPromotionSettings() {
        val intent = Intent("android.settings.APP_NOTIFICATION_PROMOTION_SETTINGS")
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        try {
            startActivity(intent)
        } catch (_: Throwable) {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
            )
        }
    }

    private fun runNotificationSelfCheck() {
        val context = applicationContext
        for (step in 0..10) {
            handler.postDelayed({
                LiveUpdateNotification.postSelfCheckProgress(context, step, 10)
                if (step == 10) {
                    handler.postDelayed({
                        LiveUpdateNotification.postFinished(
                            context,
                            "实时通知自检完成",
                            "Android 通知进度链路工作正常",
                        )
                    }, 800L)
                }
            }, step * 800L)
        }
    }

    private fun actionButton(label: String, fillColor: Int) = Button(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 15f
        isAllCaps = false
        background = GradientDrawable().apply {
            setColor(fillColor)
            cornerRadius = dp(6).toFloat()
            setStroke(dp(1), Color.rgb(73, 226, 242))
        }
    }

    private fun buttonParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        dp(48),
    ).apply { topMargin = dp(10) }

    private fun matchWrap(height: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        height,
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val REQUEST_NOTIFICATIONS = 1201
    }
}
