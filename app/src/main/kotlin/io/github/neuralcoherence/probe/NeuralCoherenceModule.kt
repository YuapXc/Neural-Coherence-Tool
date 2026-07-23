package io.github.neuralcoherence.probe

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.neuralcoherence.probe.core.Candidate
import io.github.neuralcoherence.probe.core.InteractionEngine
import io.github.neuralcoherence.probe.core.ModuleTask
import io.github.neuralcoherence.probe.core.ModuleTaskCoordinator
import io.github.neuralcoherence.probe.core.PanelLayoutCalculator
import io.github.neuralcoherence.probe.core.RateLimitException
import io.github.neuralcoherence.probe.core.SemanticPageClassifier
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.Executors
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class NeuralCoherenceModule : XposedModule() {
    private var currentActivity = WeakReference<Activity?>(null)
    private var currentPanel = WeakReference<ControlPanel?>(null)
    @Volatile private var savedStatus = "待扫描"
    @Volatile private var savedProgress = 0
    @Volatile private var savedMaximum = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private val interactionEngine = InteractionEngine()
    private var semanticMonitorGeneration = 0
    private var semanticTapGeneration = 0
    private var lastSemanticVisibility: Boolean? = null
    private var lastSemanticCheckAt = 0L
    private var semanticTouchDownX = 0f
    private var semanticTouchDownY = 0f
    private var semanticTouchDownAt = 0L
    private var semanticTouchMoved = false
    private var semanticAccessFailureLogged = false
    private var semanticAnchorFailureLogged = false
    private var lastPanelAnchor: PanelAnchor? = null
    private var lastPanelAnchorAt = 0L
    private var cachedFlutterView = WeakReference<View?>(null)
    private var cachedAccessibilityBridge = WeakReference<Any?>(null)
    private var forcedAccessibilityChannel = WeakReference<Any?>(null)
    private var accessibilityBridgeField: Field? = null
    private var accessibilityChannelField: Field? = null
    private var semanticsTreeField: Field? = null
    private var enableFlutterSemanticsMethod: Method? = null
    private var disableFlutterSemanticsMethod: Method? = null
    private var flutterSemanticsForced = false
    private var flutterSemanticsEnableAttempts = 0
    private var cachedSemanticsNodeClass: Class<*>? = null
    private var semanticsLabelField: Field? = null
    private var semanticsValueField: Field? = null
    private var semanticsHintField: Field? = null
    private var semanticsTooltipField: Field? = null
    private var semanticsIdentifierField: Field? = null
    private var semanticsParentField: Field? = null
    private var semanticsGlobalRectMethod: Method? = null
    private var liveUpdateStopReceiverRegistered = false

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (param.packageName != TARGET_PACKAGE || !param.isFirstPackage) return
        log(Log.INFO, TAG, "Loaded in ${param.packageName}, module=${BuildConfig.VERSION_NAME}, framework=$frameworkName, api=$apiVersion")
        hookFlutterActivity(param.classLoader)
    }

    private fun hookFlutterActivity(loader: ClassLoader) = try {
        val activityClass = Class.forName("io.flutter.embedding.android.FlutterActivity", false, loader)
        hook(activityClass.getDeclaredMethod("onResume")).setId("$TAG:FlutterActivity#onResume").intercept { chain ->
            val result = chain.proceed()
            val activity = chain.thisObject as Activity
            log(Log.INFO, TAG, "FlutterActivity resumed: ${activity.javaClass.name}")
            installScanButton(activity)
            startSemanticMonitor(activity)
            result
        }
        hook(activityClass.getDeclaredMethod("onPause")).setId("$TAG:FlutterActivity#onPause").intercept { chain ->
            stopSemanticMonitor()
            restoreFlutterSemantics(chain.thisObject as Activity)
            chain.proceed()
        }
        hook(activityClass.getMethod("dispatchTouchEvent", MotionEvent::class.java))
            .setId("$TAG:FlutterActivity#dispatchTouchEvent").intercept { chain ->
                val event = chain.getArg(0) as MotionEvent
                val activity = chain.thisObject as Activity
                val semanticTap = trackSemanticTouch(activity, event)
                val result = chain.proceed()
                if (semanticTap) scheduleSemanticChecks(activity)
                result
            }
        log(Log.INFO, TAG, "Installed FlutterActivity hooks")
    } catch (error: Throwable) {
        log(Log.WARN, TAG, "FlutterActivity hooks unavailable", error)
    }

    private fun installScanButton(activity: Activity) {
        registerLiveUpdateStopReceiver(activity.applicationContext)
        val content = activity.findViewById<FrameLayout>(android.R.id.content) ?: return
        if (content.findViewWithTag<View>(PANEL_TAG) != null) return
        val panel = ControlPanel(activity)
        currentActivity = WeakReference(activity)
        currentPanel = WeakReference(panel)
        panel.root.tag = PANEL_TAG
        panel.root.visibility = View.GONE
        content.addView(panel.root, FrameLayout.LayoutParams(dp(activity, PANEL_DESIRED_WIDTH_DP), dp(activity, PANEL_HEIGHT_DP)).apply {
            gravity = Gravity.TOP or Gravity.START
        })
        panel.setTaskState(taskCoordinator.state)
        panel.setStatus(savedStatus, savedProgress, savedMaximum)
        panel.status.setOnLongClickListener { LiveUpdateClient.openSettings(activity) }
        panel.scan.setOnClickListener { startDryRunScan(activity, panel) }
        panel.action.setOnClickListener {
            if (taskCoordinator.state == ModuleTask.INTERACTING) {
                stopRequested.set(true)
                panel.action.text = "停止中"
            } else confirmBatchInteraction(activity, panel)
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Synchronized
    private fun registerLiveUpdateStopReceiver(context: Context) {
        if (liveUpdateStopReceiverRegistered) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                if (intent?.action != LiveUpdateContract.ACTION_STOP_TARGET || !LiveUpdateClient.isTrustedModuleSender(this)) return
                if (taskCoordinator.state == ModuleTask.INTERACTING) {
                    stopRequested.set(true)
                    updatePanel(true, "停止中", savedProgress, savedMaximum)
                }
            }
        }
        try {
            val filter = IntentFilter(LiveUpdateContract.ACTION_STOP_TARGET)
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            else context.registerReceiver(receiver, filter)
            liveUpdateStopReceiverRegistered = true
        } catch (error: Throwable) {
            log(Log.WARN, TAG, "Unable to register live update stop receiver", error)
        }
    }

    private fun startSemanticMonitor(activity: Activity) {
        val generation = ++semanticMonitorGeneration
        semanticTapGeneration++
        lastSemanticVisibility = null
        lastSemanticCheckAt = 0
        lastPanelAnchor = null
        lastPanelAnchorAt = 0
        semanticAnchorFailureLogged = false
        flutterSemanticsEnableAttempts = 0
        cachedFlutterView = WeakReference(null)
        cachedAccessibilityBridge = WeakReference(null)
        try {
            getFlutterView(activity)?.let { view -> getAccessibilityBridge(view)?.let { enableFlutterSemanticsIfNeeded(activity, it) } }
        } catch (error: Throwable) {
            log(Log.WARN, TAG, "Unable to enable Flutter semantics on resume", error)
        }
        runSemanticMonitor(activity, generation)
    }

    private fun stopSemanticMonitor() { semanticMonitorGeneration++; semanticTapGeneration++ }

    private fun runSemanticMonitor(activity: Activity, generation: Int) {
        if (generation != semanticMonitorGeneration || activity.isFinishing || activity.isDestroyed) return
        if (SystemClock.uptimeMillis() - lastSemanticCheckAt >= SEMANTIC_POLL_COALESCE_MS) updatePanelVisibilityFromSemantics(activity)
        mainHandler.postDelayed({ runSemanticMonitor(activity, generation) }, SEMANTIC_POLL_INTERVAL_MS)
    }

    private fun scheduleSemanticChecks(activity: Activity) {
        val monitorGeneration = semanticMonitorGeneration
        val tapGeneration = ++semanticTapGeneration
        SEMANTIC_TAP_CHECK_DELAYS_MS.forEach { delay -> mainHandler.postDelayed({
            if (monitorGeneration == semanticMonitorGeneration && tapGeneration == semanticTapGeneration && !activity.isFinishing && !activity.isDestroyed) {
                updatePanelVisibilityFromSemantics(activity)
            }
        }, delay) }
    }

    private fun trackSemanticTouch(activity: Activity, event: MotionEvent): Boolean = when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> { semanticTouchDownX = event.x; semanticTouchDownY = event.y; semanticTouchDownAt = event.eventTime; semanticTouchMoved = false; false }
        MotionEvent.ACTION_MOVE -> { if (!semanticTouchMoved) { val dx=event.x-semanticTouchDownX; val dy=event.y-semanticTouchDownY; val slop=dp(activity,12); semanticTouchMoved=dx*dx+dy*dy>slop*slop }; false }
        MotionEvent.ACTION_CANCEL -> { semanticTouchMoved = true; false }
        MotionEvent.ACTION_UP -> !semanticTouchMoved && event.eventTime - semanticTouchDownAt <= 800
        else -> false
    }

    private fun updatePanelVisibilityFromSemantics(activity: Activity) {
        lastSemanticCheckAt = SystemClock.uptimeMillis()
        val result = inspectSyncNetworkMainPage(activity)
        var visible = result.mainPage
        val content = activity.findViewById<FrameLayout>(android.R.id.content) ?: return
        val panel = content.findViewWithTag<View>(PANEL_TAG)
        if (visible && panel != null) {
            val anchor = result.anchor ?: lastPanelAnchor?.takeIf { SystemClock.uptimeMillis() - lastPanelAnchorAt <= PANEL_ANCHOR_GRACE_MS }
            if (anchor == null || !layoutPanelBetweenAnchors(activity, content, panel, result.flutterView, anchor)) {
                visible = false
                if (!semanticAnchorFailureLogged) { semanticAnchorFailureLogged = true; log(Log.WARN, TAG, "Semantic title geometry unavailable") }
            } else {
                result.anchor?.let { lastPanelAnchor = it; lastPanelAnchorAt = SystemClock.uptimeMillis() }
                semanticAnchorFailureLogged = false
            }
        } else if (!visible) { lastPanelAnchor = null; lastPanelAnchorAt = 0 }
        panel?.visibility = if (visible) View.VISIBLE else View.GONE
        if (lastSemanticVisibility != visible) { lastSemanticVisibility = visible; log(Log.INFO, TAG, "Semantic page match=$visible") }
    }

    private fun inspectSyncNetworkMainPage(activity: Activity): SemanticPageResult {
        return try {
        val flutterView = getFlutterView(activity) ?: return SemanticPageResult.hidden()
        val bridge = getAccessibilityBridge(flutterView) ?: return SemanticPageResult.hidden()
        val tree = semanticsTreeField?.get(bridge) as? Map<*, *> ?: return SemanticPageResult.hidden()
        if (tree.isEmpty()) { enableFlutterSemanticsIfNeeded(activity, bridge); return SemanticPageResult.hidden() }
        var state = 0
        var visited = 0
        val networkTitles = mutableListOf<SemanticAnchor>()
        val settingsEntries = mutableListOf<SemanticAnchor>()
        for (node in tree.values) {
            if (node == null || visited++ >= 2000) continue
            ensureSemanticsNodeFields(node.javaClass)
            val values = listOf(semanticsLabelField, semanticsValueField, semanticsHintField, semanticsTooltipField, semanticsIdentifierField).map { it?.get(node) }
            values.forEach { state = SemanticPageClassifier.inspectText(state, it) }
            val network = values.any { semanticTextContains(it, "同调网络") }
            val settings = values.any { semanticTextContains(it, "设置特别通讯") }
            if (network || settings) getSemanticBounds(node)?.takeUnless(Rect::isEmpty)?.let { bounds ->
                val candidate = SemanticAnchor(node, bounds)
                if (network) networkTitles += candidate
                if (settings) settingsEntries += candidate
            }
        }
        semanticAccessFailureLogged = false
        if (!SemanticPageClassifier.isMainPage(state)) SemanticPageResult.hidden()
        else SemanticPageResult(true, flutterView, selectPanelAnchor(networkTitles, settingsEntries))
    } catch (error: Throwable) {
        if (!semanticAccessFailureLogged) { semanticAccessFailureLogged = true; log(Log.WARN, TAG, "Flutter semantics unavailable: ${error.javaClass.simpleName}") }
            SemanticPageResult.hidden()
        }
    }

    private fun enableFlutterSemanticsIfNeeded(activity: Activity, bridge: Any) {
        val manager = activity.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        if (manager == null || manager.isEnabled ||
            flutterSemanticsEnableAttempts >= MAX_FLUTTER_SEMANTICS_ENABLE_ATTEMPTS
        ) return
        val channel = getAccessibilityChannel(bridge) ?: return
        enableFlutterSemanticsMethod?.invoke(channel)
        forcedAccessibilityChannel = WeakReference(channel)
        flutterSemanticsForced = true
        flutterSemanticsEnableAttempts++
        log(Log.INFO, TAG, "Enabled Flutter semantics while system accessibility is off " +
            "(attempt $flutterSemanticsEnableAttempts/$MAX_FLUTTER_SEMANTICS_ENABLE_ATTEMPTS)")
    }

    private fun restoreFlutterSemantics(activity: Activity) {
        if (!flutterSemanticsForced) return
        val channel = forcedAccessibilityChannel.get()
        val manager = activity.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        if (manager?.isEnabled == true) { flutterSemanticsForced = false; flutterSemanticsEnableAttempts = 0; forcedAccessibilityChannel = WeakReference(null); return }
        if (channel == null || disableFlutterSemanticsMethod == null) return
        try { disableFlutterSemanticsMethod?.invoke(channel); flutterSemanticsForced = false; flutterSemanticsEnableAttempts = 0; forcedAccessibilityChannel = WeakReference(null); log(Log.INFO,TAG,"Restored Flutter semantics after leaving foreground") }
        catch (error: Throwable) { log(Log.WARN,TAG,"Unable to restore Flutter semantics",error) }
    }

    private fun getAccessibilityChannel(bridge: Any): Any? {
        if (accessibilityChannelField?.declaringClass?.isAssignableFrom(bridge.javaClass) != true) accessibilityChannelField = findField(bridge.javaClass, "accessibilityChannel")
        val channel = accessibilityChannelField?.get(bridge) ?: return null
        if (enableFlutterSemanticsMethod?.declaringClass?.isAssignableFrom(channel.javaClass) != true) {
            enableFlutterSemanticsMethod = findMethod(channel.javaClass, "onAndroidAccessibilityEnabled")
            disableFlutterSemanticsMethod = findMethod(channel.javaClass, "onAndroidAccessibilityDisabled")
        }
        return channel
    }

    private fun selectPanelAnchor(networks: List<SemanticAnchor>, settings: List<SemanticAnchor>): PanelAnchor? {
        var pair: Pair<SemanticAnchor, SemanticAnchor>? = null; var bestOverlap=Int.MIN_VALUE; var bestDistance=Int.MAX_VALUE
        for (network in networks) for (setting in settings) {
            if (setting.bounds.left <= network.bounds.right) continue
            val overlap=min(network.bounds.bottom,setting.bounds.bottom)-max(network.bounds.top,setting.bounds.top)
            val distance=abs(network.bounds.centerY()-setting.bounds.centerY())
            if (overlap<=0 && distance>max(network.bounds.height(),setting.bounds.height())) continue
            if (overlap>bestOverlap || overlap==bestOverlap && distance<bestDistance) { pair=network to setting; bestOverlap=overlap; bestDistance=distance }
        }
        return pair?.let { PanelAnchor(it.first.bounds, expandToSettingsEntryBounds(it.second,it.first.bounds)) }
    }

    private fun expandToSettingsEntryBounds(setting: SemanticAnchor, network: Rect): Rect {
        var best=Rect(setting.bounds); var parent=semanticsParentField?.get(setting.node); var depth=0
        while (parent!=null && depth++<4) {
            val bounds=getSemanticBounds(parent) ?: break
            if (bounds.isEmpty || !bounds.contains(setting.bounds) || bounds.left<=network.right || bounds.width()>max(1,setting.bounds.width())*3 || bounds.height()>max(1,setting.bounds.height())*3) break
            best=bounds; parent=semanticsParentField?.get(parent)
        }
        return best
    }

    private fun getSemanticBounds(node: Any): Rect? = (semanticsGlobalRectMethod?.invoke(node) as? Rect)?.let(::Rect)

    private fun layoutPanelBetweenAnchors(activity: Activity, content: FrameLayout, panel: View, flutterView: View?, anchor: PanelAnchor): Boolean {
        if (flutterView == null || content.width <= 0 || content.height <= 0) return false
        val fl=IntArray(2); val cl=IntArray(2); flutterView.getLocationOnScreen(fl); content.getLocationOnScreen(cl)
        val placement=PanelLayoutCalculator.calculate(anchor.networkTitle.right,anchor.networkTitle.centerY(),anchor.settingsEntry.left,anchor.settingsEntry.centerY(),dp(activity,PANEL_ANCHOR_PADDING_DP),dp(activity,PANEL_MIN_WIDTH_DP),dp(activity,PANEL_DESIRED_WIDTH_DP),dp(activity,PANEL_HEIGHT_DP),fl[0],fl[1],cl[0],cl[1],content.width,content.height) ?: return false
        val p=panel.layoutParams as FrameLayout.LayoutParams
        if (p.width!=placement.width || p.height!=placement.height || p.leftMargin!=placement.left || p.topMargin!=placement.top || p.gravity!=(Gravity.TOP or Gravity.START)) {
            p.width=placement.width; p.height=placement.height; p.gravity=Gravity.TOP or Gravity.START; p.leftMargin=placement.left; p.topMargin=placement.top; p.rightMargin=0; panel.layoutParams=p
        }
        return true
    }

    private fun getFlutterView(activity: Activity): View? {
        cachedFlutterView.get()?.takeIf(View::isAttachedToWindow)?.let { return it }
        val view=findFlutterView(activity.window.decorView); cachedFlutterView=WeakReference(view); cachedAccessibilityBridge=WeakReference(null); return view
    }

    private fun getAccessibilityBridge(view: View): Any? {
        cachedAccessibilityBridge.get()?.let { return it }
        if (accessibilityBridgeField?.declaringClass?.isAssignableFrom(view.javaClass) != true) accessibilityBridgeField=findField(view.javaClass,"accessibilityBridge")
        val bridge=accessibilityBridgeField?.get(view) ?: return null
        if (semanticsTreeField?.declaringClass?.isAssignableFrom(bridge.javaClass) != true) semanticsTreeField=findField(bridge.javaClass,"flutterSemanticsTree")
        cachedAccessibilityBridge=WeakReference(bridge); return bridge
    }

    private fun ensureSemanticsNodeFields(type: Class<*>) {
        if (type==cachedSemanticsNodeClass) return
        semanticsLabelField=findField(type,"label"); semanticsValueField=findField(type,"value"); semanticsHintField=findField(type,"hint"); semanticsTooltipField=findField(type,"tooltip"); semanticsIdentifierField=findField(type,"identifier"); semanticsParentField=findField(type,"parent"); semanticsGlobalRectMethod=findMethod(type,"getGlobalRect"); cachedSemanticsNodeClass=type
    }

    private fun findFlutterView(view: View): View? {
        if (view.javaClass.name=="io.flutter.embedding.android.FlutterView") return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findFlutterView(view.getChildAt(i))?.let { return it }
        return null
    }

    private fun confirmBatchInteraction(activity: Activity, panel: ControlPanel) {
        val state=activity.getSharedPreferences(MODULE_STATE_PREFS,Context.MODE_PRIVATE)
        val remaining=REPEAT_GUARD_MS-(System.currentTimeMillis()-state.getLong(LAST_BATCH_STARTED_AT,0))
        if (remaining>0) { AlertDialog.Builder(activity).setTitle("操作过于频繁").setMessage("最近一次批量互动刚刚完成。为避免短时间重复请求，请约 ${max(1,(remaining+59999)/60000)} 分钟后再试；扫描功能仍可正常使用。").setPositiveButton("确定",null).show(); return }
        if (!state.getBoolean(LIVE_UPDATE_SETUP_SEEN,false) && LiveUpdateClient.openSettings(activity)) { state.edit().putBoolean(LIVE_UPDATE_SETUP_SEEN,true).apply(); return }
        AlertDialog.Builder(activity).setTitle("一键互动").setMessage("将重新扫描好友，并依次向所有尚未完成今日互动的好友发送请求。\n\n无色发送 ping，绿色发送 pong；蓝色和橙色跳过。每次间隔 1.5 至 3 秒，遇到服务器限流时等待 20 秒并重试一次。运行中可手动停止，操作不可撤销。")
            .setNegativeButton("取消",null).setNeutralButton("实时通知") { _,_->LiveUpdateClient.openSettings(activity) }.setPositiveButton("确认执行") { _,_->startBatchInteraction(activity,panel) }.show()
    }

    private fun startBatchInteraction(activity: Activity, panel: ControlPanel) {
        if (!taskCoordinator.tryStart(ModuleTask.INTERACTING)) return
        stopRequested.set(false)
        updatePanel(true, "扫描中", 0, 0)
        LiveUpdateClient.update(activity, "扫描好友", 0, 0, 0, true)
        networkExecutor.execute {
            val result = InteractionResult()
            try {
                val scan = interactionEngine.scanAllFriends(activity) { page, pages ->
                    ensureInteractionContinues()
                    updatePanel(true, "扫描 $page/$pages", page, pages)
                    LiveUpdateClient.update(activity, "扫描好友", page, pages, 0, false)
                }
                result.eligible = scan.candidates.size
                LiveUpdateClient.update(activity, "准备互动", 0, result.eligible, 0, true)
                if (result.eligible > 0) {
                    updatePanel(true, "准备互动", 0, result.eligible)
                    waitCancellable(INITIAL_ACTION_DELAY_MS)
                }
                scan.candidates.forEachIndexed { index, candidate ->
                    ensureInteractionContinues()
                    val current = index + 1
                    updatePanel(true, "互动 $current/${result.eligible}", current, result.eligible)
                    sendInteractionWithSingleRateLimitRetry(
                        activity,
                        scan.session,
                        candidate,
                        current,
                        result,
                    )
                    if (candidate.action == "ping") result.ping++ else result.pong++
                    LiveUpdateClient.update(
                        activity,
                        "互动好友",
                        current,
                        result.eligible,
                        result.ping + result.pong,
                        false,
                    )
                    if (current < result.eligible) {
                        waitCancellable(
                            ThreadLocalRandom.current().nextLong(
                                MIN_ACTION_DELAY_MS,
                                MAX_ACTION_DELAY_MS + 1,
                            ),
                        )
                    }
                }
                activity.getSharedPreferences(MODULE_STATE_PREFS,Context.MODE_PRIVATE).edit().putLong(LAST_BATCH_STARTED_AT,System.currentTimeMillis()).apply()
                showInteractionResult(result,null)
            } catch (_: StopRequestedException) { result.stopped=true; showInteractionResult(result,null) }
            catch (error: Throwable) { log(Log.ERROR,TAG,"Interaction stopped: ${error.javaClass.simpleName}"); showInteractionResult(result,error) }
            finally { stopRequested.set(false); taskCoordinator.finish(ModuleTask.INTERACTING) }
        }
    }

    private fun sendInteractionWithSingleRateLimitRetry(
        activity: Activity,
        session: String,
        candidate: Candidate,
        current: Int,
        result: InteractionResult,
    ) {
        try {
            interactionEngine.sendInteraction(session, candidate)
        } catch (_: RateLimitException) {
            log(Log.WARN, TAG, "Rate limited during ${candidate.action}; retrying once")
            LiveUpdateClient.update(
                activity,
                "限流等待",
                current,
                result.eligible,
                result.ping + result.pong,
                true,
            )
            waitForRateLimitRetry(current, result.eligible)
            ensureInteractionContinues()
            updatePanel(true, "重试 $current/${result.eligible}", current, result.eligible)
            interactionEngine.sendInteraction(session, candidate)
        }
    }

    private fun waitForRateLimitRetry(current: Int, total: Int) {
        var remainingSeconds = RATE_LIMIT_RETRY_DELAY_MS / 1000L
        while (remainingSeconds > 0) {
            ensureInteractionContinues()
            updatePanel(true, "限流等待 ${remainingSeconds}s", current, total)
            Thread.sleep(1000L)
            remainingSeconds--
        }
    }

    private fun waitCancellable(durationMillis: Long) {
        var remaining = durationMillis
        while (remaining > 0L) {
            ensureInteractionContinues()
            val slice = min(remaining, STOP_CHECK_INTERVAL_MS)
            Thread.sleep(slice)
            remaining -= slice
        }
    }

    private fun ensureInteractionContinues() {
        if (stopRequested.get()) throw StopRequestedException()
    }

    private fun showInteractionResult(result: InteractionResult, error: Throwable?) {
        val sent=result.ping+result.pong; val total=max(result.eligible,sent); updatePanel(false,if(result.stopped)"已停止" else "已完成",sent,total)
        val title=when { error!=null->"互动因错误停止"; result.stopped->"互动已手动停止"; else->"同调互动已完成" }
        val summary=if(error==null && !result.stopped && total==0) "今天无需互动 · ping 0 · pong 0" else "成功 $sent/$total · ping ${result.ping} · pong ${result.pong}"
        val label=if(error!=null || result.stopped) "已停止" else "已完成"
        var details="成功发送：$sent\nping：${result.ping}\npong：${result.pong}"
        details += when { error!=null->"\n\n任务因错误停止，请返回应用查看详情。"; result.stopped->"\n\n任务已按你的要求停止。"; total==0->"\n\n今天没有需要互动的好友。"; else->"\n\n所有待互动好友均已处理。" }
        currentActivity.get()?.let { LiveUpdateClient.finish(it,title,summary,details,sent,total,label) }
        val activity=currentActivity.get()?.takeUnless { it.isFinishing || it.isDestroyed } ?: return
        activity.runOnUiThread { var message="成功发送：$sent\n其中 ping：${result.ping}\n其中 pong：${result.pong}"; message += when { error!=null->"\n\n遇到错误，任务已停止：\n${InteractionEngine.userFacingError(error)}"; result.stopped->"\n\n已按你的要求停止。"; result.eligible==0->"\n\n今天没有需要互动的好友。"; else->"\n\n所有扫描到的待互动好友均已处理。" }; AlertDialog.Builder(activity).setTitle(if(error==null&&!result.stopped)"互动完成" else "互动已停止").setMessage(message).setPositiveButton("确定",null).show() }
    }

    private fun startDryRunScan(activity: Activity, panel: ControlPanel) {
        if (!taskCoordinator.tryStart(ModuleTask.SCANNING)) return
        panel.setTaskState(ModuleTask.SCANNING); panel.setStatus("准备扫描",0,0); LiveUpdateClient.update(activity,"扫描好友",0,0,0,true,false)
        networkExecutor.execute { try {
            val result=interactionEngine.scanAllFriends(activity) { page,pages -> LiveUpdateClient.update(activity,"扫描好友",page,pages,0,false,false); activity.runOnUiThread { panel.setStatus("扫描 $page/$pages",page,pages) } }
            val pending=result.none+result.received; val summary="共 ${result.total} · 无 ${result.none} · 蓝 ${result.sent} · 绿 ${result.received} · 橙 ${result.mutual}"; val details="好友总数：${result.total}\n待互动：$pending\n无色：${result.none}\n蓝色：${result.sent}\n绿色：${result.received}\n橙色：${result.mutual}\n\n本次仅扫描，没有发送互动。"
            LiveUpdateClient.finish(activity,"好友扫描已完成",summary,details,result.total,result.total,"已完成")
            activity.runOnUiThread { panel.setStatus("待互动 $pending",0,pending); AlertDialog.Builder(activity).setTitle("扫描完成").setMessage(details).setPositiveButton("确定",null).show() }
        } catch(error:Throwable) { log(Log.ERROR,TAG,"Friend scan failed: ${error.javaClass.simpleName}"); LiveUpdateClient.finish(activity,"好友扫描因错误停止","扫描未完成，请返回应用查看详情","好友扫描因错误停止。\n\n请返回应用查看错误详情。",0,0,"已停止"); activity.runOnUiThread { panel.setStatus("扫描失败",0,0); AlertDialog.Builder(activity).setTitle("扫描失败").setMessage(InteractionEngine.userFacingError(error)).setPositiveButton("确定",null).show() } }
          finally { taskCoordinator.finish(ModuleTask.SCANNING); activity.runOnUiThread { currentPanel.get()?.setTaskState(ModuleTask.IDLE) } } }
    }

    private fun updatePanel(running:Boolean,text:String,value:Int,maximum:Int) { savedStatus=text;savedProgress=value;savedMaximum=maximum; val activity=currentActivity.get()?.takeUnless { it.isFinishing||it.isDestroyed }?:return; activity.runOnUiThread { currentPanel.get()?.apply { setRunning(running);setStatus(text,value,maximum) } } }

    private fun createOverlayButton(activity:Activity,text:String,fill:Int,stroke:Int)=Button(activity).apply { this.text=text;setTextColor(Color.WHITE);textSize=14f;isAllCaps=false;minWidth=dp(activity,104);minHeight=dp(activity,44);setPadding(dp(activity,14),0,dp(activity,14),0);background=GradientDrawable().apply { setColor(fill);cornerRadius=dp(activity,6).toFloat();setStroke(dp(activity,1),stroke) } }

    private inner class ControlPanel(context:Context) {
        val root=LinearLayout(context); val status=TextView(context); val progress=ProgressBar(context,null,android.R.attr.progressBarStyleHorizontal); val scan:Button; val action:Button
        init { root.orientation=LinearLayout.VERTICAL;root.gravity=Gravity.CENTER_VERTICAL;root.setPadding(dp(context,6),dp(context,3),dp(context,6),dp(context,3));root.background=GradientDrawable().apply { setColor(Color.argb(225,20,23,24));cornerRadius=dp(context,4).toFloat();setStroke(dp(context,1),Color.rgb(63,72,74)) }
            val row=LinearLayout(context).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL }; status.text="待扫描";status.setTextColor(Color.rgb(71,226,244));status.textSize=11f;status.gravity=Gravity.CENTER_VERTICAL;status.isSingleLine=true;row.addView(status,LinearLayout.LayoutParams(0,dp(context,24),1f));scan=compactButton(context,"扫描",Color.rgb(0,105,125),Color.rgb(46,218,239));action=compactButton(context,"互动",Color.rgb(151,79,0),Color.rgb(255,170,40));row.addView(scan,LinearLayout.LayoutParams(dp(context,34),dp(context,24)).apply { leftMargin=dp(context,3) });row.addView(action,LinearLayout.LayoutParams(dp(context,34),dp(context,24)).apply { leftMargin=dp(context,3) });root.addView(row,LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(context,25)));progress.max=100;progress.progress=0;progress.progressTintList=ColorStateList.valueOf(Color.rgb(44,220,239));progress.progressBackgroundTintList=ColorStateList.valueOf(Color.rgb(49,56,58));root.addView(progress,LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(context,2)).apply { topMargin=dp(context,2) }) }
        private fun compactButton(context:Context,text:String,fill:Int,stroke:Int)=createOverlayButton(context as Activity,text,fill,stroke).apply { textSize=11f;minWidth=0;minHeight=0;minimumWidth=0;minimumHeight=0;setPadding(0,0,0,0) }
        fun setStatus(text:String,value:Int,maximum:Int) { status.text=text;progress.max=max(1,maximum);progress.progress=max(0,min(value,max(1,maximum))) }
        fun setRunning(running:Boolean) = setTaskState(if(running) ModuleTask.INTERACTING else ModuleTask.IDLE)
        fun setTaskState(task:ModuleTask) {
            scan.visibility=if(task==ModuleTask.INTERACTING)View.GONE else View.VISIBLE
            scan.isEnabled=task==ModuleTask.IDLE
            action.isEnabled=task!=ModuleTask.SCANNING
            action.text=if(task==ModuleTask.INTERACTING)"停止" else "互动"
            action.setTextColor(if(task==ModuleTask.INTERACTING)Color.rgb(255,210,210) else Color.WHITE)
            action.alpha=if(task==ModuleTask.SCANNING)0.45f else 1f
        }
    }

    private class SemanticAnchor(val node:Any,bounds:Rect) { val bounds=Rect(bounds) }
    private data class PanelAnchor(val networkTitle:Rect,val settingsEntry:Rect)
    private data class SemanticPageResult(val mainPage:Boolean,val flutterView:View?,val anchor:PanelAnchor?) { companion object { fun hidden()=SemanticPageResult(false,null,null) } }
    private data class InteractionResult(var eligible:Int=0,var ping:Int=0,var pong:Int=0,var stopped:Boolean=false)
    private class StopRequestedException:RuntimeException()

    companion object {
        private const val TARGET_PACKAGE="com.linktech.arkradar"; private const val TAG="NeuralCoherenceTool"; private const val PANEL_TAG="syncproject_interaction_panel"; private const val MODULE_STATE_PREFS="syncproject_module_state"; private const val LAST_BATCH_STARTED_AT="last_batch_started_at"; private const val LIVE_UPDATE_SETUP_SEEN="live_update_setup_seen"
        private const val REPEAT_GUARD_MS=600_000L; private const val MIN_ACTION_DELAY_MS=1500L; private const val MAX_ACTION_DELAY_MS=3000L; private const val INITIAL_ACTION_DELAY_MS=3000L; private const val RATE_LIMIT_RETRY_DELAY_MS=20_000L; private const val STOP_CHECK_INTERVAL_MS=250L; private const val SEMANTIC_POLL_INTERVAL_MS=1500L; private const val SEMANTIC_POLL_COALESCE_MS=250L; private val SEMANTIC_TAP_CHECK_DELAYS_MS=longArrayOf(150,500,1000); private const val PANEL_DESIRED_WIDTH_DP=128; private const val PANEL_MIN_WIDTH_DP=112; private const val PANEL_HEIGHT_DP=44; private const val PANEL_ANCHOR_PADDING_DP=4; private const val PANEL_ANCHOR_GRACE_MS=3000L
        private val networkExecutor=Executors.newSingleThreadExecutor(); private val taskCoordinator=ModuleTaskCoordinator(); private val stopRequested=AtomicBoolean(false)
        private const val MAX_FLUTTER_SEMANTICS_ENABLE_ATTEMPTS=3
        private fun dp(context:Context,value:Int)=kotlin.math.round(value*context.resources.displayMetrics.density).toInt()
        private fun semanticTextContains(value:Any?,expected:String)=value is CharSequence && value.contains(expected)
        private fun findField(initial:Class<*>,name:String):Field { var type:Class<*>?=initial; while(type!=null){try{return type.getDeclaredField(name).apply { isAccessible=true }}catch(_:NoSuchFieldException){type=type.superclass}};throw NoSuchFieldException(name) }
        private fun findMethod(initial:Class<*>,name:String):Method { var type:Class<*>?=initial; while(type!=null){try{return type.getDeclaredMethod(name).apply { isAccessible=true }}catch(_:NoSuchMethodException){type=type.superclass}};throw NoSuchMethodException(name) }
    }
}
