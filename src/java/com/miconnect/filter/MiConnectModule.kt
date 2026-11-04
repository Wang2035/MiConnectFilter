package com.miconnect.filter

import android.app.Activity
import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import java.io.File
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * 妙享快连过滤（LSPosed / libxposed API 101-102，Zygote 注入）。
 *
 * 作用域只有 com.miui.connectionshare 一个进程（scope.list 已限定，
 * onPackageReady 里再做一次硬校验，勾错也不会影响任何其它应用）。
 *
 * 拦截分三层，全部只在弹窗「呈现入口」动手，不拦扫描、不拦连接、不碰 MiLink：
 *  1) Context.startActivity / Activity.startActivity(ForResult)
 *     —— 快连弹窗 Activity 被拉起前判定，BLOCK 时根本不启动。
 *  2) NotificationManager.notify / notifyAsUser
 *     —— 只检查带设备信息的通知（extras 里的设备/MAC/名称），
 *        判定 BLOCK 才拦这一条；不是全局通知拦截，其它通知原样放行。
 *  3) Activity.onCreate 兜底
 *     —— 弹窗若绕过前两层（PendingIntent 直达等），创建后按其 Intent 再判一次，
 *        BLOCK 立即 finish()。已配对设备的开盖动画/降噪卡片走同一层，
 *        但判定里 bonded 永远优先放行，不会误伤。
 *
 * 配置：/data/local/tmp/miconnectfilter.conf（设置页用 KernelSU/root 写入，
 * 实测可跨进程），本进程每秒最多重读一次，白名单/临时放行改完即时生效，
 * 不用重启 connectionshare。
 *
 * 不确定就放行：任何异常、提取不到设备信息、判定为 PASS 的，一律 proceed 原流程。
 */
class MiConnectModule : XposedModule() {

    companion object {
        private const val TAG = "MiConnectFilter"
        private val TARGETS = Targets.PACKAGES
        private val CONF_FILE = File("/data/local/tmp/miconnectfilter.conf")
        private val LOG_FILE = File("/data/local/tmp/miconnectfilter.log")
    }

    @Volatile private var config: FilterConfig = FilterConfig()

    /** 广播通道收到的配置（进程内内存，不依赖文件/SELinux）；收到过就优先于文件。 */
    @Volatile private var busConfig: FilterConfig? = null
    @Volatile private var busRegistered = false

    /** 目标进程的 Application 上下文与身份，供私有缓存与日志标注。 */
    @Volatile private var appContext: Context? = null
    @Volatile private var targetPackage: String = ""
    @Volatile private var configSource: String = "default"
    @Volatile private var pullSent = false
    @Volatile private var lastPullMs = 0L

    /** 事件采集去重：同 site+设备+判定 5 秒内只记一条，防扫描回调刷屏。 */
    private val lastEvent = ConcurrentHashMap<String, Long>()
    @Volatile private var lastRead = 0L
    @Volatile private var hooked = false

    /** 最近 2 分钟内在本进程出现过的设备（来自广播/Intent/通知），供悬浮窗层按名字找回 MAC。 */
    private data class Recent(val mac: String?, val name: String?, val bonded: Boolean?, val at: Long)
    private val recent = ConcurrentHashMap<String, Recent>()

    /** 已被隐藏的悬浮窗（弱引用集合），updateViewLayout 时保持隐藏，防止应用改回尺寸又显示出来。 */
    private val blockedViews: MutableSet<View> =
        Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    private fun remember(probe: Probe) {
        try {
            val key = probe.mac ?: probe.deviceName?.lowercase() ?: return
            recent[key] = Recent(probe.mac, probe.deviceName, probe.bonded, System.currentTimeMillis())
            val now = System.currentTimeMillis()
            recent.entries.removeIf { now - it.value.at > 120_000 }
        } catch (_: Throwable) {}
    }

    private fun enrichWithRecent(probe: Probe): Probe {
        try {
            if (probe.mac != null) return probe
            val name = probe.deviceName?.lowercase() ?: return probe
            val hit = recent.values.firstOrNull {
                it.mac != null && it.name?.lowercase() == name &&
                    System.currentTimeMillis() - it.at < 120_000
            } ?: return probe
            return probe.copy(mac = hit.mac, bonded = probe.bonded ?: hit.bonded)
        } catch (_: Throwable) {
            return probe
        }
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        try {
            if (param.packageName !in TARGETS) return
            if (!param.isFirstPackage) return
            if (hooked) return
            hooked = true
            targetPackage = param.packageName
            refreshConfig(force = true)
            ensureConfigBus()
            val cl = param.classLoader
            hookStartActivity(cl)
            hookNotifications(cl)
            hookActivityOnCreate(cl)
            hookWindowManager(cl)
            hookBroadcastDispatch(cl)
            hookDiscoveredClasses(cl, param.packageName)
            hookApplicationOnCreate(cl)
            lg("hooks installed in ${param.packageName} enabled=${config.enabled} whitelist=${config.whitelist.size}")
            log("init", null, "hooks installed enabled=${config.enabled} wl=${config.whitelist.size}")
        } catch (t: Throwable) {
            lge("init failed", t)
        }
    }

    // ------------------------------------------------------------ config

    private fun refreshConfig(force: Boolean = false) {
        ensureConfigBus()
        maybeRePull()
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastRead < 1000) return
        lastRead = now
        val bus = busConfig
        if (bus != null) {
            config = bus
            return
        }
        val f = FilterConfig.load(CONF_FILE)
        // 文件能读到（普通进程）就用文件并标注来源；读不到时 load 返回默认配置，
        // 来源保持当前值（可能是 cache/default），日志里能看出这个进程没拿到真实配置。
        if (CONF_FILE.canRead()) {
            config = f
            configSource = "file"
        }
    }

    /**
     * 在目标进程里注册配置广播接收器（进程的 Application 就绪后才注册得上，没就绪下次判定再试）。
     * 系统进程读不到 /data/local/tmp 时靠这条通道拿白名单与临时放行。
     */
    private fun ensureConfigBus() {
        if (busRegistered) return
        try {
            val app = appContext ?: Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication").invoke(null) as? Context ?: return
            registerBusWithContext(app)
        } catch (_: Throwable) {}
    }

    private fun registerBusWithContext(app: Context) {
        if (busRegistered) return
        try {
            appContext = app
            // 先读本应用私有目录里的上次配置缓存（该域自己可读写，不受 /data/local/tmp 的 SELinux 限制）
            try {
                val cacheFile = File(app.filesDir, "miconnectfilter.conf")
                if (cacheFile.canRead()) {
                    busConfig = FilterConfig.load(cacheFile)
                    config = busConfig!!
                    configSource = "cache"
                    lg("config from private cache: enabled=${config.enabled} wl=${config.whitelist.size} bypass_until=${config.bypassUntilMs}")
                }
            } catch (_: Throwable) {}
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    try {
                        if (intent == null) return
                        if (intent.getStringExtra("token") != ConfigBus.TOKEN) return
                        val cfg = FilterConfig.of(
                            enabled = intent.getBooleanExtra("enabled", true),
                            whitelistCsv = intent.getStringExtra("whitelist") ?: "",
                            bypassUntilMs = intent.getLongExtra("bypass_until", 0L),
                            debug = intent.getBooleanExtra("debug", false),
                        )
                        busConfig = cfg
                        config = cfg
                        configSource = "broadcast"
                        lg("config via broadcast: enabled=${cfg.enabled} wl=${cfg.whitelist.size} bypass_until=${cfg.bypassUntilMs}")
                        log("CONFIG", null, "via broadcast bypass_until=${cfg.bypassUntilMs} wl=${cfg.whitelist.size}")
                        // 落盘到目标应用私有目录，下次该进程重启直接读缓存，不依赖谁再发广播
                        try {
                            val ctx = appContext
                            if (ctx != null) {
                                File(ctx.filesDir, "miconnectfilter.conf").writeText(
                                    FilterConfig.render(cfg.enabled, cfg.whitelist, cfg.bypassUntilMs, cfg.debug),
                                )
                            }
                        } catch (_: Throwable) {}
                    } catch (_: Throwable) {}
                }
            }
            val filter = IntentFilter(ConfigBus.ACTION)
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                app.registerReceiver(receiver, filter)
            }
            busRegistered = true
            lg("config bus registered pkg=$targetPackage source=$configSource")
            log("INIT", null, "pkg=$targetPackage bus=registered source=$configSource")
            // 主动向设置页拉取一次当前配置（覆盖进程晚启动、错过历史广播的情况）
            if (!pullSent) {
                pullSent = true
                sendPullRequest(app)
            }
        } catch (_: Throwable) {}
    }

    private fun sendPullRequest(app: Context) {
        lastPullMs = SystemClock.elapsedRealtime()
        try {
            val req = Intent(ConfigBus.REQUEST_ACTION).apply {
                setPackage(ConfigBus.MODULE_PACKAGE)
                putExtra("token", ConfigBus.TOKEN)
            }
            app.sendBroadcast(req)
            lg("config pull requested")
        } catch (_: Throwable) {}
    }

    /**
     * 周期性重新拉取（60 秒节流，随判定调用顺带触发，不另起线程）：
     * 进程若错过某次配置广播、或缓存里还是旧开关（如 enabled=false），
     * 最多一分钟就会被设置页的最新配置纠正，不必重启进程。
     */
    private fun maybeRePull() {
        if (!busRegistered) return
        val app = appContext ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastPullMs > 60_000) sendPullRequest(app)
    }

    /** Application.onCreate 是进程里最早能拿到可靠 Context 的点，在这里注册广播并拉配置。 */
    private fun hookApplicationOnCreate(cl: ClassLoader) {
        hookAll(cl, "android.app.Application",
            { m -> m.name == "onCreate" && m.parameterTypes.isEmpty() },
            {
                object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val r = chain.proceed(chain.args.toTypedArray())
                        try {
                            (chain.thisObject as? Context)?.let { registerBusWithContext(it) }
                        } catch (_: Throwable) {}
                        return r
                    }
                }
            })
    }

    private fun decide(probe: Probe, site: String): Decision {
        refreshConfig()
        remember(probe)
        val (d, reason) = FilterCore.decideWithReason(config, probe, System.currentTimeMillis())
        // 弹窗事件全量采集（带设备信息的才记）：进程/入口/设备/判定/原因/当时配置，
        // 同一条 5 秒去重。排查时看导出的 logcat 即可，不用猜。
        val hasDevice = probe.mac != null || probe.deviceName != null || probe.xiaomiEarbud
        if (hasDevice) {
            val key = "$site|${probe.mac}|${probe.deviceName}|$d"
            val now = System.currentTimeMillis()
            val last = lastEvent[key] ?: 0L
            if (now - last > 5000) {
                lastEvent[key] = now
                lg(
                    "EVT pkg=$targetPackage src=$configSource site=$site decision=$d reason=$reason " +
                        "mac=${probe.mac} name=${probe.deviceName} bonded=${probe.bonded} xiaomi=${probe.xiaomiEarbud} " +
                        "popup=${probe.popupContext} cfg(enabled=${config.enabled}," +
                        "wl=${config.whitelist.size},bypass_until=${config.bypassUntilMs})",
                )
                log("EVT-$d", probe, "site=$site reason=$reason src=$configSource")
            }
        }
        return d
    }

    /** 诊断日志总闸：关着时模块零输出——logcat 与日志文件都不写（用户要求）。 */
    private fun lg(msg: String) {
        if (config.debug) Log.i(TAG, msg)
    }

    private fun lge(msg: String, t: Throwable?) {
        if (config.debug) Log.e(TAG, msg, t)
    }

    /** 尽力写一份决策日志供设置页查看；写不了就只走 Logcat/LSPosed 日志，不影响判定。 */
    private fun log(decision: String, probe: Probe?, extra: String) {
        if (!config.debug) return
        try {
            val line = "${System.currentTimeMillis()} $decision mac=${probe?.mac ?: "-"} " +
                "name=${probe?.deviceName ?: "-"} $extra\n"
            LOG_FILE.appendText(line)
            // 简单防膨胀：超过 128KB 截断重写尾部
            if (LOG_FILE.length() > 128 * 1024) {
                val tail = LOG_FILE.readText().takeLast(64 * 1024)
                LOG_FILE.writeText(tail)
            }
        } catch (_: Throwable) {}
    }

    // ------------------------------------------------------------ helpers

    private fun hookAll(cl: ClassLoader, className: String, methodFilter: (Method) -> Boolean,
                        hooker: (Method) -> XposedInterface.Hooker) {
        val cls = try { Class.forName(className, false, cl) } catch (_: Throwable) { return }
        for (m in cls.declaredMethods) {
            try {
                if (methodFilter(m)) hook(m).intercept(hooker(m))
            } catch (_: Throwable) {}
        }
    }

    private fun intentArg(args: List<Any?>): Intent? =
        args.firstOrNull { it is Intent } as? Intent

    // ------------------------------------------------------------ layer 1: startActivity

    private fun hookStartActivity(cl: ClassLoader) {
        val filter: (Method) -> Boolean = { m ->
            (m.name == "startActivity" || m.name == "startActivityForResult") &&
                m.parameterTypes.any { it == Intent::class.java }
        }
        val mk: (Method) -> XposedInterface.Hooker = {
            object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    try {
                        val intent = intentArg(chain.args)
                        if (intent != null) {
                            val raw = DeviceProbe.fromIntent(intent, sitePopup = false)
                            // 带设备信息在 connectionshare 里启动 Activity，本身就是弹窗/详情类入口：
                            // 即使该机型弹窗类名不含 fastconnect/popup 等词也按弹窗场景判定；
                            // 已配对与白名单在判定里优先放行，不会误伤正常流程。
                            val probe = if (raw.mac != null || raw.deviceName != null)
                                raw.copy(popupContext = true) else raw
                            if (probe.popupContext || probe.mac != null || probe.deviceName != null) {
                                if (decide(probe, "startActivity:${intent.component?.className ?: intent.action}") == Decision.BLOCK) {
                                    return null // 不启动弹窗 Activity
                                }
                            } else if (config.debug) {
                                // 无设备信息的普通页面跳转不干预；debug 时记录类名供后续精确适配
                                lg("startActivity pass (no device info): ${intent.component?.className ?: intent.action}")
                            }
                        }
                    } catch (_: Throwable) {}
                    return chain.proceed(chain.args.toTypedArray())
                }
            }
        }
        hookAll(cl, "android.content.ContextWrapper", filter, mk)
        hookAll(cl, "android.app.ContextImpl", filter, mk)
        hookAll(cl, "android.app.Activity", filter, mk)
    }

    // ------------------------------------------------------------ layer 2: notifications (per-device, not global)

    private fun hookNotifications(cl: ClassLoader) {
        hookAll(cl, "android.app.NotificationManager",
            { m -> m.name == "notify" || m.name == "notifyAsUser" || m.name == "notifyAsPackage" },
            {
                object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        try {
                            val notification = chain.args.firstOrNull { it is Notification } as? Notification
                            val tag = chain.args.firstOrNull { it is String } as? String
                            if (notification != null) {
                                val raw = DeviceProbe.fromNotification(notification, tag)
                                val probe = if (raw.mac != null || raw.deviceName != null || raw.xiaomiEarbud)
                                    raw.copy(popupContext = true) else raw
                                // 没有设备信息 -> 与本模块无关，原样放行（不是全局通知拦截）
                                if (probe.mac != null || probe.deviceName != null || probe.xiaomiEarbud) {
                                    if (decide(probe, "notify:${notification.channelId}") == Decision.BLOCK) {
                                        return null // 只拦这一条快连发现通知
                                    }
                                }
                            }
                        } catch (_: Throwable) {}
                        return chain.proceed(chain.args.toTypedArray())
                    }
                }
            })
    }

    // ------------------------------------------------------------ layer 3: Activity.onCreate fallback

    private fun hookActivityOnCreate(cl: ClassLoader) {
        hookAll(cl, "android.app.Activity",
            { m -> m.name == "onCreate" && m.parameterTypes.size == 1 },
            {
                object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val result = chain.proceed(chain.args.toTypedArray())
                        try {
                            val activity = chain.thisObject as? Activity ?: return result
                            val intent = try { activity.intent } catch (_: Throwable) { null }
                            if (intent != null) {
                                val raw = DeviceProbe.fromIntent(intent, sitePopup = false)
                                val probe = if (raw.mac != null || raw.deviceName != null)
                                    raw.copy(popupContext = true) else raw
                                if (probe.popupContext && (probe.mac != null || probe.deviceName != null || probe.xiaomiEarbud)) {
                                    if (decide(probe, "onCreate:${activity.localClassName}") == Decision.BLOCK) {
                                        lg("finish popup activity ${activity.localClassName}")
                                        try { activity.finish() } catch (_: Throwable) {}
                                    }
                                } else if (config.debug && (probe.mac != null || probe.deviceName != null)) {
                                    lg("activity pass ${activity.localClassName} mac=${probe.mac} name=${probe.deviceName}")
                                }
                            }
                        } catch (_: Throwable) {}
                        return result
                    }
                }
            })
    }

    // ------------------------------------------------------------ layer 4: overlay windows (WindowManager)
    //
    // 小米的耳机弹窗在不少版本里不是 Activity，而是蓝牙扩展进程直接用
    // WindowManager 加的悬浮窗（带 SYSTEM_ALERT_WINDOW），前三层够不到。
    // 这里检查窗口内容文字（设备名/MAC），判定 BLOCK 时不拒绝 addView
    // （拒绝会让应用后续 removeView/updateViewLayout 对不上账、可能崩），
    // 而是把窗口参数改成 1x1、alpha=0 再放行：账目完整，用户看不见。

    private fun collectTexts(view: View?, out: MutableList<String>, depth: Int) {
        if (view == null || depth > 8 || out.size > 64) return
        try {
            if (view is TextView) view.text?.toString()?.let { if (it.isNotBlank()) out.add(it) }
            view.contentDescription?.toString()?.let { if (it.isNotBlank()) out.add(it) }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) collectTexts(view.getChildAt(i), out, depth + 1)
            }
        } catch (_: Throwable) {}
    }

    private fun shrinkParams(args: Array<Any?>) {
        try {
            val lp = args.getOrNull(1) as? WindowManager.LayoutParams ?: return
            lp.width = 1
            lp.height = 1
            lp.alpha = 0f
        } catch (_: Throwable) {}
    }

    private fun hookWindowManager(cl: ClassLoader) {
        val addFilter: (Method) -> Boolean = { m ->
            m.name == "addView" && m.parameterTypes.isNotEmpty() &&
                m.parameterTypes[0] == View::class.java
        }
        val addHooker: (Method) -> XposedInterface.Hooker = {
            object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    try {
                        val view = chain.args.getOrNull(0) as? View
                        if (view != null) {
                            val texts = ArrayList<String>()
                            collectTexts(view, texts, 0)
                            val joined = texts.joinToString(" ").lowercase()
                            val earbudish = joined.contains("buds") || joined.contains("earbud") ||
                                joined.contains("airdots") || joined.contains("flipbuds") ||
                                joined.contains("xiaomi") || joined.contains("redmi") ||
                                joined.contains("poco") || joined.contains("耳机") ||
                                MacUtil.extractMacs(joined).isNotEmpty()
                            if (earbudish) {
                                val probe = enrichWithRecent(DeviceProbe.fromTexts(texts))
                                if (decide(probe, "window:addView") == Decision.BLOCK) {
                                    val args = chain.args.toTypedArray()
                                    shrinkParams(args)
                                    synchronized(blockedViews) { blockedViews.add(view) }
                                    lg("window hidden mac=${probe.mac} name=${probe.deviceName}")
                                    return chain.proceed(args)
                                }
                            }
                        }
                    } catch (_: Throwable) {}
                    return chain.proceed(chain.args.toTypedArray())
                }
            }
        }
        hookAll(cl, "android.view.WindowManagerImpl", addFilter, addHooker)
        hookAll(cl, "android.view.WindowManagerGlobal", addFilter, addHooker)

        val updFilter: (Method) -> Boolean = { m ->
            m.name == "updateViewLayout" && m.parameterTypes.isNotEmpty() &&
                m.parameterTypes[0] == View::class.java
        }
        val updHooker: (Method) -> XposedInterface.Hooker = {
            object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    try {
                        val view = chain.args.getOrNull(0) as? View
                        val blocked = view != null && synchronized(blockedViews) { blockedViews.contains(view) }
                        if (blocked) {
                            val args = chain.args.toTypedArray()
                            shrinkParams(args)
                            return chain.proceed(args)
                        }
                    } catch (_: Throwable) {}
                    return chain.proceed(chain.args.toTypedArray())
                }
            }
        }
        hookAll(cl, "android.view.WindowManagerImpl", updFilter, updHooker)
        hookAll(cl, "android.view.WindowManagerGlobal", updFilter, updHooker)
    }

    // ------------------------------------------------------------ layer 5: broadcast dispatch (source)
    //
    // 发现广播（ACTION_FOUND / 厂商快连广播）经 LoadedApk.ReceiverDispatcher$Args.run()
    // 分发到应用的 BroadcastReceiver。带未配对小米耳机的广播在这里判 BLOCK 就不执行
    // run()，弹窗在源头不会被触发，同时把设备记进 recent 缓存供悬浮窗层关联。
    // 有序广播（mCurOrdered）不拦，避免广播队列等超时，交给悬浮窗层处理。

    private fun findFieldValue(obj: Any, name: String): Any? {
        var cls: Class<*>? = obj.javaClass
        while (cls != null) {
            try {
                val f = cls.getDeclaredField(name)
                f.isAccessible = true
                return f.get(obj)
            } catch (_: Throwable) {
                cls = cls.superclass
            }
        }
        return null
    }

    private fun hookBroadcastDispatch(cl: ClassLoader) {
        hookAll(cl, "android.app.LoadedApk\$ReceiverDispatcher\$Args",
            { m -> m.name == "run" && m.parameterTypes.isEmpty() },
            {
                object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        try {
                            val self = chain.thisObject
                            if (self != null) {
                                val ordered = findFieldValue(self, "mCurOrdered") as? Boolean ?: false
                                val intent = findFieldValue(self, "mCurIntent") as? Intent
                                if (!ordered && intent != null) {
                                    val raw = DeviceProbe.fromIntent(intent, sitePopup = false)
                                    if ((raw.mac != null || raw.deviceName != null) && raw.xiaomiEarbud) {
                                        val probe = raw.copy(
                                            popupContext = true,
                                            strongDiscovery = raw.strongDiscovery || raw.bonded == false,
                                        )
                                        if (decide(probe, "broadcast:${intent.action}") == Decision.BLOCK) {
                                            lg("broadcast blocked action=${intent.action} mac=${probe.mac}")
                                            return null
                                        }
                                    } else {
                                        remember(raw)
                                    }
                                }
                            }
                        } catch (_: Throwable) {}
                        return chain.proceed(chain.args.toTypedArray())
                    }
                }
            })
    }

    // ------------------------------------------------------------ layer 6: runtime class discovery
    //
    // 不再猜类名：枚举目标应用 dex 里的全部类，找两类真实入口——
    //  1) BluetoothLeScanner 的 ScanCallback 子类（快连扫描的源头）：
    //     onScanResult/onBatchScanResults 里是小米耳机广播就按判定吞掉，
    //     下游弹窗根本触发不了。
    //  2) 类名带 fastconnect 的类：hook 其 show/popup/dialog/notify 类方法，
    //     参数带设备且判定 BLOCK 就不执行。
    // 枚举在后台线程做，不拖进程启动；命中的类名会记进日志，可核对。

    private fun enumerateClassNames(cl: ClassLoader): List<String> {
        val names = HashSet<String>()
        try {
            val pathList = findFieldValue(cl, "pathList") ?: return emptyList()
            val elements = findFieldValue(pathList, "dexElements") as? Array<*> ?: return emptyList()
            for (el in elements) {
                if (el == null) continue
                val dexFile = findFieldValue(el, "dexFile") ?: continue
                val m = dexFile.javaClass.getMethod("entries")
                val en = m.invoke(dexFile) as? java.util.Enumeration<*> ?: continue
                while (en.hasMoreElements()) {
                    (en.nextElement() as? String)?.let { names.add(it) }
                }
            }
        } catch (_: Throwable) {}
        return names.toList()
    }

    private fun isSubclassOf(cls: Class<*>, superName: String): Boolean {
        var c: Class<*>? = cls
        var depth = 0
        while (c != null && depth < 8) {
            if (c.name == superName) return true
            for (i in c.interfaces) if (i.name == superName) return true
            c = c.superclass
            depth++
        }
        return false
    }

    private fun defaultFor(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        else -> null
    }

    private fun hookDiscoveredClasses(cl: ClassLoader, pkg: String) {
        Thread {
            try {
                val names = enumerateClassNames(cl)
                var hooked = 0
                for (name in names) {
                    if (hooked >= 48) break
                    val lower = name.lowercase()
                    val scanish = lower.contains("scan")
                    val fastish = lower.contains("fastconnect") || lower.contains("fast_connect")
                    if (!scanish && !fastish) continue
                    val cls = try { Class.forName(name, false, cl) } catch (_: Throwable) { continue }
                    try {
                        if (isSubclassOf(cls, "android.bluetooth.le.ScanCallback")) {
                            var any = false
                            for (m in cls.declaredMethods) {
                                try {
                                    when (m.name) {
                                        "onScanResult" -> {
                                            hook(m).intercept(object : XposedInterface.Hooker {
                                                override fun intercept(chain: XposedInterface.Chain): Any? {
                                                    try {
                                                        val r = chain.args.getOrNull(1) as? android.bluetooth.le.ScanResult
                                                        val probe = DeviceProbe.fromScanResult(r)
                                                        if ((probe.mac != null || probe.deviceName != null) &&
                                                            decide(probe, "scanResult") == Decision.BLOCK
                                                        ) {
                                                            return null
                                                        }
                                                    } catch (_: Throwable) {}
                                                    return chain.proceed(chain.args.toTypedArray())
                                                }
                                            })
                                            any = true
                                        }
                                        "onBatchScanResults" -> {
                                            hook(m).intercept(object : XposedInterface.Hooker {
                                                override fun intercept(chain: XposedInterface.Chain): Any? {
                                                    try {
                                                        @Suppress("UNCHECKED_CAST")
                                                        val list = chain.args.getOrNull(0) as? List<android.bluetooth.le.ScanResult>
                                                        if (list != null) {
                                                            val kept = list.filter { r ->
                                                                val probe = DeviceProbe.fromScanResult(r)
                                                                (probe.mac == null && probe.deviceName == null) ||
                                                                    decide(probe, "scanBatch") != Decision.BLOCK
                                                            }
                                                            if (kept.size != list.size) {
                                                                val args = chain.args.toTypedArray()
                                                                args[0] = kept
                                                                return chain.proceed(args)
                                                            }
                                                        }
                                                    } catch (_: Throwable) {}
                                                    return chain.proceed(chain.args.toTypedArray())
                                                }
                                            })
                                            any = true
                                        }
                                    }
                                } catch (_: Throwable) {}
                            }
                            if (any) {
                                hooked++
                                lg("hooked ScanCallback: $name")
                                log("HOOK", null, "scanCallback=$name")
                            }
                        } else if (fastish) {
                            var any = false
                            for (m in cls.declaredMethods) {
                                val mn = m.name.lowercase()
                                if (!(mn.contains("show") || mn.contains("popup") || mn.contains("dialog") ||
                                        mn.contains("toast") || mn.contains("notify") || mn.contains("display"))
                                ) continue
                                try {
                                    hook(m).intercept(object : XposedInterface.Hooker {
                                        override fun intercept(chain: XposedInterface.Chain): Any? {
                                            try {
                                                val probe = DeviceProbe.fromValues(true, chain.args)
                                                if ((probe.mac != null || probe.deviceName != null || probe.xiaomiEarbud) &&
                                                    decide(probe, "fastconnect:$name.${m.name}") == Decision.BLOCK
                                                ) {
                                                    return defaultFor(m.returnType)
                                                }
                                            } catch (_: Throwable) {}
                                            return chain.proceed(chain.args.toTypedArray())
                                        }
                                    })
                                    any = true
                                } catch (_: Throwable) {}
                            }
                            if (any) {
                                hooked++
                                lg("hooked fastconnect class: $name")
                                log("HOOK", null, "fastconnect=$name")
                            }
                        }
                    } catch (_: Throwable) {}
                }
                lg("class scan done pkg=$pkg classes=${names.size} hooked=$hooked")
                log("SCAN", null, "pkg=$pkg classes=${names.size} hooked=$hooked")
            } catch (_: Throwable) {}
        }.start()
    }
}
