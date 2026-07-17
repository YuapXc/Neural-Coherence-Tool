package io.github.neuralcoherence.probe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.ref.WeakReference;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLException;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public final class NeuralCoherenceModule extends XposedModule {
    private static final String TARGET_PACKAGE = "com.linktech.arkradar";
    private static final String TAG = "NeuralCoherenceTool";
    private static final String PANEL_TAG = "syncproject_interaction_panel";
    private static final String API_BASE = "https://www.tongdiaojihua.com/zwinport/";
    private static final String SIGNING_SALT = BuildConfig.SYNC_SIGNING_SALT;
    private static final String MODULE_STATE_PREFS = "syncproject_module_state";
    private static final String LAST_BATCH_STARTED_AT = "last_batch_started_at";
    private static final long REPEAT_GUARD_MS = 10 * 60 * 1000L;
    private static final int PAGE_SIZE = 50;
    private static final long MIN_ACTION_DELAY_MS = 500L;
    private static final long MAX_ACTION_DELAY_MS = 1000L;
    private static final long SEMANTIC_POLL_INTERVAL_MS = 1500L;
    private static final long SEMANTIC_POLL_COALESCE_MS = 250L;
    private static final long[] SEMANTIC_TAP_CHECK_DELAYS_MS = {150L, 500L, 1000L};
    private static final int SEMANTIC_ANCHOR_NETWORK = 1;
    private static final int SEMANTIC_ANCHOR_RECORDS = 1 << 1;
    private static final int SEMANTIC_ANCHOR_REQUESTS = 1 << 2;
    private static final int SEMANTIC_BLOCKED = 1 << 3;
    private static final int SEMANTIC_MAIN_ANCHORS = SEMANTIC_ANCHOR_NETWORK
            | SEMANTIC_ANCHOR_RECORDS | SEMANTIC_ANCHOR_REQUESTS;
    private static final ExecutorService NETWORK_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean interactionRunning = new AtomicBoolean(false);
    private static final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private WeakReference<Activity> currentActivity = new WeakReference<>(null);
    private WeakReference<ControlPanel> currentPanel = new WeakReference<>(null);
    private volatile String savedStatus = "待扫描";
    private volatile int savedProgress;
    private volatile int savedMaximum;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private int semanticMonitorGeneration;
    private int semanticTapGeneration;
    private Boolean lastSemanticVisibility;
    private long lastSemanticCheckAt;
    private float semanticTouchDownX;
    private float semanticTouchDownY;
    private long semanticTouchDownAt;
    private boolean semanticTouchMoved;
    private boolean semanticAccessFailureLogged;
    private WeakReference<View> cachedFlutterView = new WeakReference<>(null);
    private WeakReference<Object> cachedAccessibilityBridge = new WeakReference<>(null);
    private Field accessibilityBridgeField;
    private Field semanticsTreeField;
    private Class<?> cachedSemanticsNodeClass;
    private Field semanticsLabelField;
    private Field semanticsValueField;
    private Field semanticsHintField;
    private Field semanticsTooltipField;
    private Field semanticsIdentifierField;

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName()) || !param.isFirstPackage()) {
            return;
        }

        log(Log.INFO, TAG, "Loaded in " + param.getPackageName()
                + ", framework=" + getFrameworkName()
                + ", api=" + getApiVersion());

        ClassLoader loader = param.getClassLoader();
        hookFlutterActivity(loader);
    }

    private void hookFlutterActivity(ClassLoader loader) {
        try {
            Class<?> activityClass = Class.forName(
                    "io.flutter.embedding.android.FlutterActivity", false, loader);
            Method onResume = activityClass.getDeclaredMethod("onResume");
            hook(onResume).setId(TAG + ":FlutterActivity#onResume").intercept(chain -> {
                Object result = chain.proceed();
                log(Log.INFO, TAG, "FlutterActivity resumed: "
                        + chain.getThisObject().getClass().getName());
                Activity activity = (Activity) chain.getThisObject();
                installScanButton(activity);
                startSemanticMonitor(activity);
                return result;
            });
            Method onPause = activityClass.getDeclaredMethod("onPause");
            hook(onPause).setId(TAG + ":FlutterActivity#onPause").intercept(chain -> {
                stopSemanticMonitor();
                return chain.proceed();
            });
            Method dispatchTouchEvent = activityClass.getMethod("dispatchTouchEvent", MotionEvent.class);
            hook(dispatchTouchEvent).setId(TAG + ":FlutterActivity#dispatchTouchEvent")
                    .intercept(chain -> {
                        MotionEvent event = (MotionEvent) chain.getArg(0);
                        Activity activity = (Activity) chain.getThisObject();
                        boolean semanticTap = trackSemanticTouch(activity, event);
                        Object result = chain.proceed();
                        if (semanticTap) {
                            scheduleSemanticChecks(activity);
                        }
                        return result;
                    });
            log(Log.INFO, TAG, "Installed FlutterActivity hooks");
        } catch (Throwable error) {
            log(Log.WARN, TAG, "FlutterActivity hooks unavailable", error);
        }
    }

    private void installScanButton(Activity activity) {
        FrameLayout content = activity.findViewById(android.R.id.content);
        if (content == null || content.findViewWithTag(PANEL_TAG) != null) {
            return;
        }

        ControlPanel panel = new ControlPanel(activity);
        currentActivity = new WeakReference<>(activity);
        currentPanel = new WeakReference<>(panel);
        panel.root.setTag(PANEL_TAG);
        panel.root.setVisibility(View.GONE);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(activity, 128), dp(activity, 44));
        params.gravity = Gravity.TOP | Gravity.END;
        params.topMargin = dp(activity, 126);
        params.rightMargin = dp(activity, 122);
        content.addView(panel.root, params);
        panel.setRunning(interactionRunning.get());
        panel.setStatus(savedStatus, savedProgress, savedMaximum);
        panel.scan.setOnClickListener(view -> startDryRunScan(activity, panel));
        panel.action.setOnClickListener(view -> {
            if (interactionRunning.get()) {
                stopRequested.set(true);
                panel.action.setText("停止中");
            } else {
                confirmBatchInteraction(activity, panel);
            }
        });
    }

    private void startSemanticMonitor(Activity activity) {
        int generation = ++semanticMonitorGeneration;
        semanticTapGeneration++;
        lastSemanticVisibility = null;
        lastSemanticCheckAt = 0L;
        cachedFlutterView = new WeakReference<>(null);
        cachedAccessibilityBridge = new WeakReference<>(null);
        runSemanticMonitor(activity, generation);
    }

    private void stopSemanticMonitor() {
        semanticMonitorGeneration++;
        semanticTapGeneration++;
    }

    private void runSemanticMonitor(Activity activity, int generation) {
        if (generation != semanticMonitorGeneration || activity.isFinishing()
                || activity.isDestroyed()) {
            return;
        }
        long elapsed = SystemClock.uptimeMillis() - lastSemanticCheckAt;
        if (elapsed >= SEMANTIC_POLL_COALESCE_MS) {
            updatePanelVisibilityFromSemantics(activity);
        }
        mainHandler.postDelayed(() -> runSemanticMonitor(activity, generation),
                SEMANTIC_POLL_INTERVAL_MS);
    }

    private void scheduleSemanticChecks(Activity activity) {
        int monitorGeneration = semanticMonitorGeneration;
        int tapGeneration = ++semanticTapGeneration;
        for (long delay : SEMANTIC_TAP_CHECK_DELAYS_MS) {
            mainHandler.postDelayed(() -> {
                if (monitorGeneration == semanticMonitorGeneration
                        && tapGeneration == semanticTapGeneration
                        && !activity.isFinishing()
                        && !activity.isDestroyed()) {
                    updatePanelVisibilityFromSemantics(activity);
                }
            }, delay);
        }
    }

    private boolean trackSemanticTouch(Activity activity, MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            semanticTouchDownX = event.getX();
            semanticTouchDownY = event.getY();
            semanticTouchDownAt = event.getEventTime();
            semanticTouchMoved = false;
            return false;
        }
        if (action == MotionEvent.ACTION_MOVE && !semanticTouchMoved) {
            float dx = event.getX() - semanticTouchDownX;
            float dy = event.getY() - semanticTouchDownY;
            float slop = dp(activity, 12);
            semanticTouchMoved = dx * dx + dy * dy > slop * slop;
            return false;
        }
        if (action == MotionEvent.ACTION_CANCEL) {
            semanticTouchMoved = true;
            return false;
        }
        return action == MotionEvent.ACTION_UP
                && !semanticTouchMoved
                && event.getEventTime() - semanticTouchDownAt <= 800L;
    }

    private void updatePanelVisibilityFromSemantics(Activity activity) {
        lastSemanticCheckAt = SystemClock.uptimeMillis();
        boolean visible = isSyncNetworkMainPage(activity);
        View content = activity.findViewById(android.R.id.content);
        if (content == null) {
            return;
        }
        View panel = content.findViewWithTag(PANEL_TAG);
        int targetVisibility = visible ? View.VISIBLE : View.GONE;
        if (panel != null && panel.getVisibility() != targetVisibility) {
            panel.setVisibility(targetVisibility);
        }
        if (lastSemanticVisibility == null || lastSemanticVisibility != visible) {
            lastSemanticVisibility = visible;
            log(Log.INFO, TAG, "Semantic page match=" + visible);
        }
    }

    private boolean isSyncNetworkMainPage(Activity activity) {
        try {
            View flutterView = getFlutterView(activity);
            if (flutterView == null) {
                return false;
            }

            Object bridge = getAccessibilityBridge(flutterView);
            if (bridge == null) {
                return false;
            }

            Object treeValue = semanticsTreeField.get(bridge);
            if (!(treeValue instanceof Map)) {
                return false;
            }
            Map<?, ?> semanticsTree = (Map<?, ?>) treeValue;
            int state = 0;
            int visited = 0;
            for (Object node : semanticsTree.values()) {
                if (node == null || visited++ >= 2000) {
                    continue;
                }
                ensureSemanticsNodeFields(node.getClass());
                state = inspectSemanticText(state, semanticsLabelField.get(node));
                state = inspectSemanticText(state, semanticsValueField.get(node));
                state = inspectSemanticText(state, semanticsHintField.get(node));
                state = inspectSemanticText(state, semanticsTooltipField.get(node));
                state = inspectSemanticText(state, semanticsIdentifierField.get(node));
                if ((state & SEMANTIC_BLOCKED) != 0) {
                    return false;
                }
            }
            semanticAccessFailureLogged = false;
            return (state & SEMANTIC_MAIN_ANCHORS) == SEMANTIC_MAIN_ANCHORS;
        } catch (Throwable error) {
            if (!semanticAccessFailureLogged) {
                semanticAccessFailureLogged = true;
                log(Log.WARN, TAG, "Flutter semantics unavailable: "
                        + error.getClass().getSimpleName());
            }
            return false;
        }
    }

    private View getFlutterView(Activity activity) {
        View cached = cachedFlutterView.get();
        if (cached != null && cached.isAttachedToWindow()) {
            return cached;
        }
        View flutterView = findFlutterView(activity.getWindow().getDecorView());
        cachedFlutterView = new WeakReference<>(flutterView);
        cachedAccessibilityBridge = new WeakReference<>(null);
        return flutterView;
    }

    private Object getAccessibilityBridge(View flutterView) throws ReflectiveOperationException {
        Object cached = cachedAccessibilityBridge.get();
        if (cached != null) {
            return cached;
        }
        if (accessibilityBridgeField == null
                || !accessibilityBridgeField.getDeclaringClass().isAssignableFrom(
                flutterView.getClass())) {
            accessibilityBridgeField = findField(flutterView.getClass(), "accessibilityBridge");
        }
        Object bridge = accessibilityBridgeField.get(flutterView);
        if (bridge == null) {
            return null;
        }
        if (semanticsTreeField == null
                || !semanticsTreeField.getDeclaringClass().isAssignableFrom(bridge.getClass())) {
            semanticsTreeField = findField(bridge.getClass(), "flutterSemanticsTree");
        }
        cachedAccessibilityBridge = new WeakReference<>(bridge);
        return bridge;
    }

    private void ensureSemanticsNodeFields(Class<?> nodeClass)
            throws ReflectiveOperationException {
        if (nodeClass == cachedSemanticsNodeClass) {
            return;
        }
        semanticsLabelField = findField(nodeClass, "label");
        semanticsValueField = findField(nodeClass, "value");
        semanticsHintField = findField(nodeClass, "hint");
        semanticsTooltipField = findField(nodeClass, "tooltip");
        semanticsIdentifierField = findField(nodeClass, "identifier");
        cachedSemanticsNodeClass = nodeClass;
    }

    private View findFlutterView(View view) {
        if ("io.flutter.embedding.android.FlutterView".equals(view.getClass().getName())) {
            return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View result = findFlutterView(group.getChildAt(i));
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    private static Field findField(Class<?> type, String name)
            throws ReflectiveOperationException {
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static int inspectSemanticText(int state, Object value) {
        if (!(value instanceof CharSequence)) {
            return state;
        }
        String text = value.toString();
        if (text.contains("同调网络")) {
            state |= SEMANTIC_ANCHOR_NETWORK;
        }
        if (text.contains("同调记录")) {
            state |= SEMANTIC_ANCHOR_RECORDS;
        }
        if (text.contains("好友申请")) {
            state |= SEMANTIC_ANCHOR_REQUESTS;
        }
        if (text.contains("设置状态")
                || text.contains("好友状态")
                || text.contains("同调记录归档")
                || text.contains("调取档案")
                || text.contains("INITIALIZING")) {
            state |= SEMANTIC_BLOCKED;
        }
        return state;
    }

    private Button createOverlayButton(Activity activity, String tag, String text,
                                       int fillColor, int strokeColor) {
        Button button = new Button(activity);
        button.setTag(tag);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setMinWidth(dp(activity, 104));
        button.setMinHeight(dp(activity, 44));
        button.setPadding(dp(activity, 14), 0, dp(activity, 14), 0);
        GradientDrawable background = new GradientDrawable();
        background.setColor(fillColor);
        background.setCornerRadius(dp(activity, 6));
        background.setStroke(dp(activity, 1), strokeColor);
        button.setBackground(background);
        return button;
    }

    private void confirmBatchInteraction(Activity activity, ControlPanel panel) {
        long lastCompleted = activity.getSharedPreferences(MODULE_STATE_PREFS, Context.MODE_PRIVATE)
                .getLong(LAST_BATCH_STARTED_AT, 0L);
        long remaining = REPEAT_GUARD_MS - (System.currentTimeMillis() - lastCompleted);
        if (remaining > 0) {
            long minutes = Math.max(1, (remaining + 59999L) / 60000L);
            new AlertDialog.Builder(activity)
                    .setTitle("操作过于频繁")
                    .setMessage("最近一次批量互动刚刚完成。为避免短时间重复请求，请约 "
                            + minutes + " 分钟后再试；扫描功能仍可正常使用。")
                    .setPositiveButton("确定", null)
                    .show();
            return;
        }
        new AlertDialog.Builder(activity)
                .setTitle("一键互动")
                .setMessage("将重新扫描好友，并依次向所有尚未完成今日互动的好友发送请求。\n\n"
                        + "无色发送 ping，绿色发送 pong；蓝色和橙色跳过。"
                        + "每次间隔 0.5 至 1 秒，运行中可手动停止，操作不可撤销。")
                .setNegativeButton("取消", null)
                .setPositiveButton("确认执行", (dialog, which) -> startBatchInteraction(activity, panel))
                .show();
    }

    private void startBatchInteraction(Activity activity, ControlPanel panel) {
        if (!interactionRunning.compareAndSet(false, true)) {
            return;
        }
        stopRequested.set(false);
        activity.getSharedPreferences(MODULE_STATE_PREFS, Context.MODE_PRIVATE).edit()
                .putLong(LAST_BATCH_STARTED_AT, System.currentTimeMillis()).apply();
        updatePanel(true, "扫描中", 0, 0);
        NETWORK_EXECUTOR.execute(() -> {
            InteractionResult interaction = new InteractionResult();
            try {
                ScanResult scan = scanAllFriends(activity, (page, pages) -> {
                    if (stopRequested.get()) {
                        throw new StopRequestedException();
                    }
                    updatePanel(true, "扫描 " + page + "/" + pages, page, pages);
                });
                int count = scan.candidates.size();
                for (int i = 0; i < count; i++) {
                    if (stopRequested.get()) {
                        throw new StopRequestedException();
                    }
                    Candidate candidate = scan.candidates.get(i);
                    int current = i + 1;
                    updatePanel(true, "互动 " + current + "/" + count, current, count);
                    sendInteraction(scan.session, candidate);
                    if (candidate.action.equals("ping")) {
                        interaction.ping++;
                    } else {
                        interaction.pong++;
                    }
                    if (current < count) {
                        long delay = ThreadLocalRandom.current().nextLong(
                                MIN_ACTION_DELAY_MS, MAX_ACTION_DELAY_MS + 1);
                        Thread.sleep(delay);
                    }
                }
                interaction.eligible = scan.candidates.size();
                showInteractionResult(activity, panel, interaction, null);
            } catch (StopRequestedException stopped) {
                interaction.stopped = true;
                showInteractionResult(activity, panel, interaction, null);
            } catch (Throwable error) {
                log(Log.ERROR, TAG, "Interaction stopped: " + error.getClass().getSimpleName());
                showInteractionResult(activity, panel, interaction, error);
            } finally {
                stopRequested.set(false);
                interactionRunning.set(false);
            }
        });
    }

    private void showInteractionResult(Activity activity, ControlPanel panel,
                                       InteractionResult result, Throwable error) {
        updatePanel(false, result.stopped ? "已停止" : "已完成",
                result.ping + result.pong, Math.max(result.eligible, result.ping + result.pong));
        Activity visibleActivity = currentActivity.get();
        if (visibleActivity == null || visibleActivity.isFinishing() || visibleActivity.isDestroyed()) {
            return;
        }
        visibleActivity.runOnUiThread(() -> {
            String message = "成功发送：" + (result.ping + result.pong)
                    + "\n其中 ping：" + result.ping
                    + "\n其中 pong：" + result.pong;
            if (error != null) {
                String detail = userFacingError(error);
                message += "\n\n遇到错误，任务已停止：\n" + detail;
            } else if (result.stopped) {
                message += "\n\n已按你的要求停止。";
            } else if (result.eligible == 0) {
                message += "\n\n今天没有需要互动的好友。";
            } else {
                message += "\n\n所有扫描到的待互动好友均已处理。";
            }
            new AlertDialog.Builder(visibleActivity)
                    .setTitle(error == null && !result.stopped ? "互动完成" : "互动已停止")
                    .setMessage(message)
                    .setPositiveButton("确定", null)
                    .show();
        });
    }

    private void startDryRunScan(Activity activity, ControlPanel panel) {
        panel.scan.setEnabled(false);
        panel.setStatus("准备扫描", 0, 0);
        NETWORK_EXECUTOR.execute(() -> {
            try {
                ScanResult result = scanAllFriends(activity, (page, pages) ->
                        activity.runOnUiThread(() -> panel.setStatus(
                                "扫描 " + page + "/" + pages, page, pages)));
                activity.runOnUiThread(() -> {
                    panel.scan.setEnabled(true);
                    int pending = result.none + result.received;
                    panel.setStatus("待互动 " + pending, 0, pending);
                    new AlertDialog.Builder(activity)
                            .setTitle("扫描完成")
                            .setMessage("好友总数：" + result.total
                                    + "\n无色：" + result.none
                                    + "\n蓝色：" + result.sent
                                    + "\n绿色：" + result.received
                                    + "\n橙色：" + result.mutual
                                    + "\n\n本次仅扫描，没有发送互动。")
                            .setPositiveButton("确定", null)
                            .show();
                });
            } catch (Throwable error) {
                log(Log.ERROR, TAG, "Friend scan failed: " + error.getClass().getSimpleName());
                activity.runOnUiThread(() -> {
                    panel.scan.setEnabled(true);
                    panel.setStatus("扫描失败", 0, 0);
                    new AlertDialog.Builder(activity)
                            .setTitle("扫描失败")
                            .setMessage(userFacingError(error))
                            .setPositiveButton("确定", null)
                            .show();
                });
            }
        });
    }

    private ScanResult scanAllFriends(Activity activity, ProgressListener listener) throws Exception {
        ensureNetworkAvailable(activity);
        SharedPreferences preferences = activity.getSharedPreferences(
                "FlutterSharedPreferences", Context.MODE_PRIVATE);
        String session = preferences.getString("flutter.session", "");
        if (session == null || session.isBlank()) {
            throw new IllegalStateException("未找到登录会话，请重新登录目标应用");
        }

        ScanResult result = new ScanResult();
        result.session = session;
        int page = 1;
        int pages = 1;
        do {
            JSONObject response = getFriendPage(session, page);
            if (!response.optBoolean("succ", false)) {
                throw new IllegalStateException("服务器拒绝读取好友列表");
            }
            JSONObject data = response.optJSONObject("data");
            if (data == null || !data.has("records") || !data.has("pages")) {
                throw new IllegalStateException("好友接口结构已变化，已停止以保护账号");
            }
            pages = Math.max(1, data.optInt("pages", 1));
            result.total = data.optInt("total", result.total);
            JSONArray records = data.optJSONArray("records");
            if (records == null) {
                throw new IllegalStateException("好友记录格式已变化，已停止以保护账号");
            }
            {
                for (int i = 0; i < records.length(); i++) {
                    JSONObject record = records.getJSONObject(i);
                    if (!record.has("todayContactInd")) {
                        throw new IllegalStateException("好友状态字段已变化，已停止以保护账号");
                    }
                    int state = record.optInt("todayContactInd", 0);
                    switch (state) {
                        case 0 -> {
                            result.none++;
                            addCandidate(result, record, "ping");
                        }
                        case 1 -> result.sent++;
                        case 2 -> {
                            result.received++;
                            addCandidate(result, record, "pong");
                        }
                        case 3 -> result.mutual++;
                        default -> throw new IllegalStateException(
                                "发现未知好友状态 " + state + "，已停止以保护账号");
                    }
                }
            }
            listener.onProgress(page, pages);
            page++;
            if (page <= pages) {
                Thread.sleep(700L);
            }
        } while (page <= pages && page <= 100);
        return result;
    }

    private void addCandidate(ScanResult result, JSONObject record, String action) {
        long id = record.optLong("id", 0L);
        if (id <= 0) {
            throw new IllegalStateException("好友 ID 字段已变化，已停止以保护账号");
        }
        result.candidates.add(new Candidate(id, action));
    }

    private void sendInteraction(String session, Candidate candidate) throws Exception {
        String path = "api/friends/" + candidate.action;
        HttpURLConnection connection = (HttpURLConnection) new URL(API_BASE + path).openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(15000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        applyAuthHeaders(connection, session);

        byte[] body = new JSONObject().put("friendUserId", candidate.id)
                .toString().getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(body.length);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(body);
        }

        int httpStatus = connection.getResponseCode();
        InputStream stream = httpStatus >= 200 && httpStatus < 300
                ? connection.getInputStream() : connection.getErrorStream();
        String responseText = stream == null ? "" : readUtf8(stream);
        if (stream != null) {
            stream.close();
        }
        connection.disconnect();
        if (httpStatus != HttpURLConnection.HTTP_OK) {
            throw httpStatusError(httpStatus);
        }
        JSONObject response = new JSONObject(responseText);
        if (!response.optBoolean("succ", false)) {
            String message = response.optString("msg", "服务器拒绝请求");
            throw new IllegalStateException(candidate.action + " 失败：" + message);
        }
    }

    private JSONObject getFriendPage(String session, int page) throws Exception {
        String path = "api/friends/friendList?pageIndex=" + page
                + "&pageSize=" + PAGE_SIZE
                + "&queryValue=&isOrder=true&serverName=&isSameCity=0";
        HttpURLConnection connection = (HttpURLConnection) new URL(API_BASE + path).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(15000);
        connection.setInstanceFollowRedirects(false);

        applyAuthHeaders(connection, session);

        int status = connection.getResponseCode();
        if (status != HttpURLConnection.HTTP_OK) {
            connection.disconnect();
            throw httpStatusError(status);
        }
        try (InputStream input = connection.getInputStream()) {
            return new JSONObject(readUtf8(input));
        } finally {
            connection.disconnect();
        }
    }

    private static String readUtf8(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }

    private void applyAuthHeaders(HttpURLConnection connection, String session) throws Exception {
        if (SIGNING_SALT == null || SIGNING_SALT.isBlank()) {
            throw new IllegalStateException("模块缺少本地签名配置，无法发送请求");
        }
        long timestamp = System.currentTimeMillis();
        connection.setRequestProperty("User-Agent", "zwintech-arkradar-app");
        connection.setRequestProperty("authorization", md5(SIGNING_SALT + timestamp));
        connection.setRequestProperty("timenum", Long.toString(timestamp));
        connection.setRequestProperty("Cookie", "Zwin-ArkRadar=" + session);
    }

    private static String md5(String input) throws Exception {
        byte[] digest = MessageDigest.getInstance("MD5")
                .digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder output = new StringBuilder(32);
        for (byte value : digest) {
            output.append(String.format("%02x", value & 0xff));
        }
        return output.toString();
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private void updatePanel(boolean running, String text, int value, int maximum) {
        savedStatus = text;
        savedProgress = value;
        savedMaximum = maximum;
        Activity activity = currentActivity.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        activity.runOnUiThread(() -> {
            ControlPanel panel = currentPanel.get();
            if (panel != null) {
                panel.setRunning(running);
                panel.setStatus(text, value, maximum);
            }
        });
    }

    private static void ensureNetworkAvailable(Context context) {
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        Network network = manager == null ? null : manager.getActiveNetwork();
        NetworkCapabilities capabilities = network == null ? null
                : manager.getNetworkCapabilities(network);
        if (capabilities == null || !capabilities.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            throw new IllegalStateException("当前没有可用网络，请连接网络后重试");
        }
    }

    private static String userFacingError(Throwable error) {
        if (error instanceof UnknownHostException || error instanceof ConnectException) {
            return "无法连接服务器，请检查网络、代理或 DNS 设置";
        }
        if (error instanceof SocketTimeoutException) {
            return "服务器响应超时，任务已停止，请稍后重试";
        }
        if (error instanceof SSLException) {
            return "安全连接建立失败，请检查系统时间或网络环境";
        }
        String message = error.getMessage();
        return message == null || message.isBlank() ? "发生未知错误，任务已停止" : message;
    }

    private static IllegalStateException httpStatusError(int status) {
        return switch (status) {
            case 401, 403 -> new IllegalStateException("登录状态已失效或请求未获授权，请重新登录");
            case 429 -> new IllegalStateException("服务器限制了请求频率，任务已停止，请稍后重试");
            default -> status >= 500
                    ? new IllegalStateException("服务器暂时不可用（HTTP " + status + "）")
                    : new IllegalStateException("请求失败（HTTP " + status + "）");
        };
    }

    private interface ProgressListener {
        void onProgress(int page, int pages);
    }

    private static final class ScanResult {
        String session;
        int total;
        int none;
        int sent;
        int received;
        int mutual;
        int unknown;
        final List<Candidate> candidates = new ArrayList<>();
    }

    private record Candidate(long id, String action) {
    }

    private static final class InteractionResult {
        int eligible;
        int ping;
        int pong;
        boolean stopped;
    }

    private static final class StopRequestedException extends RuntimeException {
    }

    private final class ControlPanel {
        final LinearLayout root;
        final TextView status;
        final ProgressBar progress;
        final Button scan;
        final Button action;

        ControlPanel(Context context) {
            root = new LinearLayout(context);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setGravity(Gravity.CENTER_VERTICAL);
            root.setPadding(dp(context, 6), dp(context, 3), dp(context, 6), dp(context, 3));
            GradientDrawable panelBackground = new GradientDrawable();
            panelBackground.setColor(Color.argb(225, 20, 23, 24));
            panelBackground.setCornerRadius(dp(context, 4));
            panelBackground.setStroke(dp(context, 1), Color.rgb(63, 72, 74));
            root.setBackground(panelBackground);

            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            status = new TextView(context);
            status.setText("待扫描");
            status.setTextColor(Color.rgb(71, 226, 244));
            status.setTextSize(11);
            status.setGravity(Gravity.CENTER_VERTICAL);
            status.setSingleLine(true);
            row.addView(status, new LinearLayout.LayoutParams(0, dp(context, 24), 1f));

            scan = compactButton(context, "扫描", Color.rgb(0, 105, 125),
                    Color.rgb(46, 218, 239));
            action = compactButton(context, "互动", Color.rgb(151, 79, 0),
                    Color.rgb(255, 170, 40));
            LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                    dp(context, 34), dp(context, 24));
            buttonParams.leftMargin = dp(context, 3);
            row.addView(scan, buttonParams);
            LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(
                    dp(context, 34), dp(context, 24));
            actionParams.leftMargin = dp(context, 3);
            row.addView(action, actionParams);
            root.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 25)));

            progress = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
            progress.setMax(100);
            progress.setProgress(0);
            progress.setProgressTintList(ColorStateList.valueOf(Color.rgb(44, 220, 239)));
            progress.setProgressBackgroundTintList(ColorStateList.valueOf(Color.rgb(49, 56, 58)));
            LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 2));
            progressParams.topMargin = dp(context, 2);
            root.addView(progress, progressParams);
        }

        private Button compactButton(Context context, String text, int fill, int stroke) {
            Button button = createOverlayButton((Activity) context, null, text, fill, stroke);
            button.setTextSize(11);
            button.setMinWidth(0);
            button.setMinHeight(0);
            button.setMinimumWidth(0);
            button.setMinimumHeight(0);
            button.setPadding(0, 0, 0, 0);
            return button;
        }

        void setStatus(String text, int value, int maximum) {
            status.setText(text);
            progress.setMax(Math.max(1, maximum));
            progress.setProgress(Math.max(0, Math.min(value, Math.max(1, maximum))));
        }

        void setRunning(boolean running) {
            scan.setVisibility(running ? View.GONE : View.VISIBLE);
            action.setText(running ? "停止" : "互动");
            action.setTextColor(running ? Color.rgb(255, 210, 210) : Color.WHITE);
        }
    }

}
