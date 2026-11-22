package io.github.wang2035.miconnectfilter

import android.app.Activity
import android.app.Notification
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
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
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.ImageDecoder
import android.graphics.Outline
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * 妙享快连（LSPosed / libxposed API 101-102，Zygote 注入）。
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

    // ------------------------------------------------------------ 弹窗背景图
    // 设置页可给「全部设备」或某台设备单独存一张图，经 BgProvider 暴露为
    // content://.../bg/<key>；hook 按弹窗设备 MAC 从 config.bgMap 挑 key、
    // 按版本号拉取字节并缓存。贴图方式是把弹窗卡片的 background 换成按原
    // 圆角裁剪的图片（OPPO 个性化弹窗同款，图填满卡片内部、内容照常在上层）。
    // 弹窗有两种载体：Activity（onCreate 层）和独立窗口/Dialog（WindowManager
    // addView 层）——v1.23 只够到 Activity 的空壳，图全垫在了窗外；两层都接。
    // 拉不到/解码失败一律静默放弃，绝不影响弹窗本身。

    private val bgLock = Any()
    private val bgCache = HashMap<String, ByteArray>()
    private val bgCacheVer = HashMap<String, Long>()
    private val bgMissing = HashSet<String>()
    private val bgMissingAt = HashMap<String, Long>()

    private fun markBgMissing(key: String) {
        bgMissing.add(key)
        bgMissingAt[key] = SystemClock.elapsedRealtime()
    }

    private fun clearBgMissing(key: String) {
        bgMissing.remove(key)
        bgMissingAt.remove(key)
    }
    private val bgInFlight = HashSet<String>()
    /** GIF 帧缓存："key:ver" -> 帧位图/帧时长（拉取线程里解好，贴图时直接用）。 */
    private val bgGifFrames = HashMap<String, List<android.graphics.Bitmap>>()
    private val bgVideoPoster = HashMap<String, android.graphics.Bitmap>()
    private val bgGifDelays = HashMap<String, IntArray>()
    /** 视频帧包缓存："key:ver" -> 懒解码帧源（只存 JPEG 字节，画到哪帧解哪帧，省 CPU/内存）。 */
    private val bgLazyFrames = HashMap<String, LazyFrames>()
    private val bgBundleVer = HashMap<String, Long>()
    @Volatile private var pendingBgRoot: WeakReference<View>? = null
    @Volatile private var pendingBgMac: String? = null
    @Volatile private var pendingBgCtx: Context? = null
    @Volatile private var lastBgRoot: WeakReference<View>? = null
    @Volatile private var lastBgMac: String? = null
    /** 已贴好背景的窗口根 -> "key:ver"，防重复贴、版本变了重贴。 */
    private val bgRootTokens: MutableMap<View, String> =
        Collections.synchronizedMap(WeakHashMap())
    private val cardBgTokens: MutableMap<View, String> =
        Collections.synchronizedMap(WeakHashMap())

    private fun applyPopupBackground(activity: Activity, mac: String?) {
        try {
            val root = activity.window?.decorView ?: return
            decorateRoot(root, mac, activity.applicationContext)
            applyFontColorForRoot(root)
        } catch (_: Throwable) {}
    }

    // ------------------------------------------------------------ 弹窗字体颜色
    // 字体颜色跟着背景走（BgOpt.fontColor，每个背景一份）：0=系统（不改）1=白字 2=黑字。
    // 只对真正贴了自定义背景的弹窗生效——没贴背景的弹窗一个字都不碰。
    // 只改 TextView 的文字/提示色，不动图标与背景；弹窗内容常在窗口加上后才
    // 填充，每个根再补三次延时重刷兜底。系统模式不进这里，零干预。

    private val fontAppliedRoots: MutableMap<View, Int> =
        Collections.synchronizedMap(WeakHashMap())

    /** 已贴背景的窗口根 -> 该背景的字体颜色档。 */
    private val bgFontByRoot: MutableMap<View, Int> =
        Collections.synchronizedMap(WeakHashMap())

    private fun applyFontColorForRoot(root: View) {
        val mode = synchronized(bgFontByRoot) { bgFontByRoot[root] } ?: return
        applyFontColor(root, mode)
    }

    private fun applyFontColor(root: View, mode: Int) {
        if (mode != FilterConfig.FONT_WHITE && mode != FilterConfig.FONT_BLACK) return
        val color = if (mode == FilterConfig.FONT_WHITE) {
            android.graphics.Color.WHITE
        } else {
            android.graphics.Color.BLACK
        }
        try {
            setTextColors(root, color)
            val prev = synchronized(fontAppliedRoots) { fontAppliedRoots.put(root, mode) }
            if (prev != mode) {
                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                for (delay in longArrayOf(250L, 700L, 1500L)) {
                    handler.postDelayed(
                        { try { setTextColors(root, color) } catch (_: Throwable) {} },
                        delay,
                    )
                }
            }
        } catch (_: Throwable) {}
    }

    private fun setTextColors(view: View, color: Int) {
        if (view is android.widget.TextView) {
            try { view.setTextColor(color) } catch (_: Throwable) {}
            try { view.setHintTextColor(color) } catch (_: Throwable) {}
        }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                try { setTextColors(view.getChildAt(i), color) } catch (_: Throwable) {}
            }
        }
    }

    private fun decorateRoot(root: View, mac: String?, appCtx: Context) {
        try {
            lastBgRoot = WeakReference(root)
            lastBgMac = mac
            // 候选按序尝试：专属图拉不到（已删/拉取失败）就回退「全部设备」图，
            // 不再像旧版那样卡在失效的专属 key 上、把 all 也挡死。
            for (key in config.bgKeysFor(mac)) {
                val ver = config.bgMap[key] ?: continue
                if (ver <= 0L) continue
                val bundleReady = synchronized(bgLock) {
                    val lf = bgLazyFrames["$key:$ver"]
                    if (bgBundleVer[key] == ver && lf != null) {
                        val f = lf.file
                        if (f != null && f.exists()) {
                            true
                        } else {
                            // 缓存文件被系统清掉：丢掉失效帧源，回落到重新拉取
                            lf.close()
                            bgLazyFrames.remove("$key:$ver")
                            bgBundleVer.remove(key)
                            false
                        }
                    } else {
                        false
                    }
                }
                if (bundleReady) {
                    // 帧包走文件懒帧源：buildBgContent 优先取懒帧，bytes 只是占位
                    decorateWithBytes(root, ByteArray(0), key, ver, 0)
                    return
                }
                val ready = synchronized(bgLock) {
                    if (bgCacheVer[key] == ver && key !in bgMissing) bgCache[key] else null
                }
                if (ready != null) {
                    decorateWithBytes(root, ready, key, ver, 0)
                    return
                }
                val knownMissing = synchronized(bgLock) {
                    if (key in bgMissing && bgCacheVer[key] == ver) {
                        val at = bgMissingAt[key]
                        if (at != null && SystemClock.elapsedRealtime() - at < 15_000L) {
                            true
                        } else {
                            // 失败已超 15 秒：清掉闩锁，这次弹窗重新拉一次
                            bgMissing.remove(key)
                            bgMissingAt.remove(key)
                            false
                        }
                    } else {
                        false
                    }
                }
                if (knownMissing) continue
                log("BGFETCH", null, "start key=$key ver=$ver pkg=$targetPackage")
                lg("bgfetch start key=$key ver=$ver pkg=$targetPackage")
                pendingBgRoot = WeakReference(root)
                pendingBgMac = mac
                pendingBgCtx = appCtx
                fetchBgBytes(appCtx, key, ver)
                return
            }
        } catch (_: Throwable) {}
    }

    /** 配置一到就预热背景：本地有缓存包直接解析、没有就后台拉，弹窗出现时立即有背景。 */
    private fun prewarmBg(ctx: Context) {
        try {
            for ((key, ver) in config.bgMap) {
                val ready = synchronized(bgLock) {
                    (bgLazyFrames["$key:$ver"] != null && bgBundleVer[key] == ver) ||
                        (bgCache.containsKey(key) && bgCacheVer[key] == ver)
                }
                if (!ready) fetchBgBytes(ctx, key, ver)
            }
        } catch (_: Throwable) {}
    }

    private fun fetchBgBytes(ctx: Context, key: String, version: Long) {
        synchronized(bgLock) {
            if (!bgInFlight.add(key)) return
        }
        Thread {
            try {
                // 本进程缓存里已有同版本帧包（进程重启后包还在）：直接解析，省掉整包重传
                val permPre = java.io.File(ctx.cacheDir, "mcf_bg_${key}_$version.zip")
                if (permPre.isFile && permPre.length() > 0L) {
                    val cached = try { parseBundleFile(permPre) } catch (_: Throwable) { null }
                    if (cached != null) {
                        synchronized(bgLock) {
                            val mapKey = "$key:$version"
                            bgLazyFrames[mapKey]?.close()
                            bgLazyFrames[mapKey] = cached
                            bgBundleVer[key] = version
                            clearBgMissing(key)
                        }
                        try { cached.prefetch(0) } catch (_: Throwable) {}
                        lg("bg bundle from cache key=$key frames=${cached.size} pkg=$targetPackage")
                        log("BGFETCH", null, "bundle cached key=$key frames=${cached.size} pkg=$targetPackage")
                        val root0 = pendingBgRoot?.get()
                        val c0 = pendingBgCtx
                        if (root0 != null && c0 != null) decorateRoot(root0, pendingBgMac, c0)
                        return@Thread
                    }
                }
                // 流式下载到本进程缓存文件再分流：原画质帧包可达上百 MB，
                // 整包读进内存会爆，超过上限还会被截断成坏包
                val dlTmp = java.io.File(ctx.cacheDir, "mcf_bg_dl_$key.tmp")
                var dlTotal = 0L
                val head = ByteArray(8)
                var headLen = 0
                // 期望长度（提供方文件大小）：传输中途断了能当场发现并重试一次，
                // 别把截断的坏包当成功（坏包解析失败再叠加失败闩锁，就是「只有重导才显示」）
                val expectedLen = try {
                    ctx.contentResolver.openAssetFileDescriptor(
                        Uri.parse(ConfigBus.bgUri(key)), "r",
                    )?.use { it.length } ?: -1L
                } catch (_: Throwable) {
                    -1L
                }
                var dlOk = false
                for (attempt in 0..1) {
                    dlTotal = 0L
                    headLen = 0
                    val okOnce = try {
                        ctx.contentResolver.openInputStream(
                            Uri.parse(ConfigBus.bgUri(key)),
                        )?.use { ins ->
                            java.io.FileOutputStream(dlTmp).use { out ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    val n = ins.read(buf)
                                    if (n <= 0) break
                                    if (headLen < head.size) {
                                        val c = minOf(n, head.size - headLen)
                                        System.arraycopy(buf, 0, head, headLen, c)
                                        headLen += c
                                    }
                                    out.write(buf, 0, n)
                                    dlTotal += n
                                    if (dlTotal > 256L * 1024 * 1024) break
                                }
                            }
                            true
                        } ?: false
                    } catch (_: Throwable) {
                        false
                    }
                    if (okOnce && (expectedLen <= 0L || dlTotal == expectedLen)) {
                        dlOk = true
                        break
                    }
                    log(
                        "BGFETCH", null,
                        "retry key=$key attempt=$attempt got=$dlTotal want=$expectedLen pkg=$targetPackage",
                    )
                }
                var bytes: ByteArray? = null
                if (dlOk && dlTotal > 0L && headLen >= 2 &&
                    head[0] == 0x50.toByte() && head[1] == 0x4B.toByte()
                ) {
                    // 视频帧包：转正到缓存文件，ZipFile 随机访问逐帧读，内存只留解码缓存
                    val perm = java.io.File(ctx.cacheDir, "mcf_bg_${key}_$version.zip")
                    try { dlTmp.copyTo(perm, overwrite = true) } catch (_: Throwable) {}
                    val parsed = parseBundleFile(perm)
                    synchronized(bgLock) {
                        val mapKey = "$key:$version"
                        val old = bgLazyFrames[mapKey]
                        if (parsed != null) {
                            if (old != null && old !== parsed) {
                                old.close()
                                old.file?.let {
                                    if (it.absolutePath != perm.absolutePath) {
                                        try { it.delete() } catch (_: Throwable) {}
                                    }
                                }
                            }
                            bgLazyFrames[mapKey] = parsed
                            bgBundleVer[key] = version
                            clearBgMissing(key)
                            try { parsed.prefetch(0) } catch (_: Throwable) {}
                            log(
                                "BGFETCH", null,
                                "bundle ok key=$key frames=${parsed.size} bytes=$dlTotal pkg=$targetPackage",
                            )
                        } else {
                            markBgMissing(key)
                            log(
                                "BGFETCH", null,
                                "bundle parse failed key=$key bytes=$dlTotal pkg=$targetPackage",
                            )
                            try { perm.delete() } catch (_: Throwable) {}
                        }
                        bgCacheVer[key] = version
                    }
                    try {
                        ctx.cacheDir.listFiles()?.forEach { f ->
                            if (f.name.startsWith("mcf_bg_${key}_") && f.name != perm.name) {
                                try { f.delete() } catch (_: Throwable) {}
                            }
                        }
                    } catch (_: Throwable) {}
                    lg("bg bundle fetched key=$key version=$version bytes=$dlTotal pkg=$targetPackage")
                } else {
                    bytes = if (dlOk && dlTotal > 0L) {
                        try { dlTmp.readBytes() } catch (_: Throwable) { null }
                    } else {
                        null
                    }
                    synchronized(bgLock) {
                        if (bytes != null && bytes.isNotEmpty()) {
                            bgCache[key] = bytes
                            clearBgMissing(key)
                        } else {
                            bgCache.remove(key)
                            markBgMissing(key)
                        }
                        bgCacheVer[key] = version
                    }
                }
                try { dlTmp.delete() } catch (_: Throwable) {}
                // 原视频：先抓一张海报帧兜底（解码器出帧前先有图可画，解码失败也不空）
                if (bytes != null && bytes.size >= 8 &&
                    bytes.copyOfRange(4, 8).toString(Charsets.US_ASCII) == "ftyp"
                ) {
                    try {
                        val r = android.media.MediaMetadataRetriever()
                        try {
                            r.setDataSource(mediaDataSourceFor(bytes))
                            val rot = r.extractMetadata(
                                android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION,
                            )?.toIntOrNull() ?: 0
                            var poster = r.getFrameAtTime(
                                0L, android.media.MediaMetadataRetriever.OPTION_CLOSEST,
                            )
                            if (poster != null && rot != 0) {
                                val m = android.graphics.Matrix().apply {
                                    postRotate(rot.toFloat())
                                }
                                poster = android.graphics.Bitmap.createBitmap(
                                    poster, 0, 0, poster.width, poster.height, m, true,
                                )
                            }
                            if (poster != null) {
                                synchronized(bgLock) {
                                    bgVideoPoster["$key:$version"] = poster
                                }
                                lg("bg video poster key=$key ${poster.width}x${poster.height}")
                            }
                        } finally {
                            try { r.release() } catch (_: Throwable) {}
                        }
                    } catch (t: Throwable) {
                        lg("bg video poster failed key=$key: ${t.message}")
                    }
                }
                // GIF 在拉取线程里先解成帧（自有播放器要逐帧控制模式/速度），
                // 别等到贴图时在主线程解码卡弹窗。
                if (bytes != null && GifDecoder.isGif(bytes)) {
                    try {
                        val frames = GifDecoder.thinFrames(
                            GifDecoder.decode(bytes), 150_000_000L,
                        )
                        if (frames.size >= 2) {
                            val bitmaps = frames.map { f ->
                                android.graphics.Bitmap.createBitmap(
                                    f.pixels, f.width, f.height,
                                    android.graphics.Bitmap.Config.ARGB_8888,
                                )
                            }
                            synchronized(bgLock) {
                                bgGifFrames["$key:$version"] = bitmaps
                                bgGifDelays["$key:$version"] =
                                    IntArray(frames.size) { frames[it].delayMs }
                            }
                            log("BGGIF", null, "key=$key frames=${frames.size} pkg=$targetPackage")
                            lg("bg gif decoded key=$key frames=${frames.size}")
                        }
                    } catch (t: Throwable) {
                        lg("bg gif decode failed key=$key: ${t.message}")
                    }
                }
                lg("bg fetched key=$key version=$version bytes=${bytes?.size ?: -1} pkg=$targetPackage")
                if (bytes != null && bytes.isNotEmpty()) {
                    log("BGFETCH", null, "bytes ok key=$key bytes=${bytes.size} pkg=$targetPackage")
                }
                val root = pendingBgRoot?.get()
                val c = pendingBgCtx
                if (root != null && c != null) decorateRoot(root, pendingBgMac, c)
            } catch (t: Throwable) {
                synchronized(bgLock) {
                    bgCache.remove(key)
                    markBgMissing(key)
                    bgCacheVer[key] = version
                }
                lg("bg fetch failed key=$key version=$version pkg=$targetPackage: ${t.message}")
                log("BGFETCH", null, "failed2 key=$key ver=$version err=${t.message} pkg=$targetPackage")
                log("BGFETCH", null, "failed key=$key ver=$version err=${t.message} pkg=$targetPackage")
            } finally {
                synchronized(bgLock) { bgInFlight.remove(key) }
            }
        }.start()
    }

    /** 贴图内容：静态位图 / 系统动图（WebP 用）/ GIF 帧 / 视频懒帧 / 视频直放，五选一。 */
    private class BgContent(
        val bitmap: android.graphics.Bitmap? = null,
        val animated: AnimatedImageDrawable? = null,
        val gifBitmaps: List<android.graphics.Bitmap>? = null,
        val gifDelays: IntArray? = null,
        val lazy: LazyFrames? = null,
        val videoBytes: ByteArray? = null,
    ) {
        val kind: String
            get() = when {
                gifBitmaps != null -> "frames(${gifBitmaps.size}f)"
                lazy != null -> "frames(${lazy.size}f)"
                videoBytes != null -> "video"
                animated != null -> "anim"
                else -> "static"
            }
    }

    /**
     * 视频帧包文件（ZIP：meta.txt 记 fps，fNNN.jpg 为帧）拆成懒帧源：ZipFile 随机访问，
     * 帧字节按需从文件读，不整包进内存。总像素预算靠第一帧尺寸估算，超了抽帧、帧时长加倍补偿。
     */
    private fun parseBundleFile(file: java.io.File): LazyFrames? {
        return try {
            val zip = java.util.zip.ZipFile(file)
            var fps = 10
            val names = ArrayList<String>()
            val en = zip.entries()
            while (en.hasMoreElements()) {
                val e = en.nextElement()
                if (e.name == "meta.txt") {
                    val text = zip.getInputStream(e).use { it.readBytes() }
                        .toString(Charsets.US_ASCII)
                    val m = Regex("fps=(\\d+)").find(text)
                    fps = m?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 120) ?: 10
                } else if (e.name.endsWith(".jpg")) {
                    names.add(e.name)
                }
            }
            names.sort()
            if (names.size < 2) {
                try { zip.close() } catch (_: Throwable) {}
                return null
            }
            val first = zip.getEntry(names[0])?.let {
                zip.getInputStream(it).use { st -> st.readBytes() }
            }
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            if (first != null) {
                BitmapFactory.decodeByteArray(first, 0, first.size, boundsOpts)
            }
            val px = boundsOpts.outWidth.toLong() * boundsOpts.outHeight.toLong()
            var delay = 1000 / fps
            var keep = names
            // 播放端软件解码上限约 30fps：源帧率高于 40fps 时先抽帧降下来，
            // 不然每帧间隔比解码耗时还短、必然掉帧（延时按倍数补偿，总时长不变）
            if (fps > 40 && keep.size > 2) {
                val step = (fps + 29) / 30
                val next = ArrayList<String>()
                var i = 0
                while (i < keep.size) {
                    next.add(keep[i])
                    i += step
                }
                if (next.size >= 2) {
                    keep = next
                    delay *= step
                }
            }
            if (px > 0) {
                while (keep.size > 2 && px * keep.size > 200_000_000L) {
                    val next = ArrayList<String>()
                    var i = 0
                    while (i < keep.size) {
                        next.add(keep[i])
                        i += 2
                    }
                    keep = next
                    delay *= 2
                }
            }
            LazyFrames(zip, keep, IntArray(keep.size) { delay })
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 静态图解码：只在解码时按 2 的幂采样到长边 ≤2880（存的文件不动、画质不减），
     * 防超大原图照片在弹窗进程里全尺寸解码爆内存。
     */
    private fun decodeSampled(bytes: ByteArray): android.graphics.Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
        while (maxDim / (sample * 2) >= 2880) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun buildBgContent(bytes: ByteArray, key: String, version: Long): BgContent? {
        // 帧缓存优先：GIF 在拉取线程里解好了帧；视频帧包是懒帧源（拆包即得）
        val (cachedBitmaps, cachedDelays) = synchronized(bgLock) {
            bgGifFrames["$key:$version"] to bgGifDelays["$key:$version"]
        }
        if (cachedBitmaps != null && cachedDelays != null && cachedBitmaps.size >= 2) {
            return BgContent(gifBitmaps = cachedBitmaps, gifDelays = cachedDelays)
        }
        val lazy = synchronized(bgLock) { bgLazyFrames["$key:$version"] }
        if (lazy != null && lazy.size >= 2) {
            return BgContent(lazy = lazy)
        }
        // 原视频（MP4 容器）：直播放器边解边画，原帧率；海报帧作兜底静态图
        if (bytes.size >= 8 &&
            bytes.copyOfRange(4, 8).toString(Charsets.US_ASCII) == "ftyp"
        ) {
            val poster = synchronized(bgLock) { bgVideoPoster["$key:$version"] }
            return BgContent(bitmap = poster, videoBytes = bytes)
        }
        if (GifDecoder.isGif(bytes)) {
            // 只有 1 帧或解码没跟上：当静态图用第一帧
            return try {
                decodeSampled(bytes)?.let { BgContent(bitmap = it) }
            } catch (_: Throwable) {
                null
            }
        }
        // JPEG/PNG 静态图：采样解码，不走 ImageDecoder 全尺寸解码
        if (bytes.size >= 4 &&
            ((bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) ||
                (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte()))
        ) {
            return try {
                decodeSampled(bytes)?.let { BgContent(bitmap = it) }
            } catch (_: Throwable) {
                null
            }
        }
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                val d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes)))
                if (d is AnimatedImageDrawable) {
                    // WebP 等系统动图：播一次停靠 repeatCount，其余模式退化为循环
                    try {
                        d.repeatCount = if (config.bgOptFor(key).mode == BgOpt.MODE_ONCE) {
                            0
                        } else {
                            AnimatedImageDrawable.REPEAT_INFINITE
                        }
                    } catch (_: Throwable) {}
                    return BgContent(animated = d)
                }
                if (d is BitmapDrawable) return BgContent(bitmap = d.bitmap)
            } catch (_: Throwable) {}
        }
        return try {
            decodeSampled(bytes)?.let { BgContent(bitmap = it) }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 把图片贴进窗口里的卡片。窗口刚建时尺寸还没量出来、内容可能还没塞完，
     * 所以带重试多试几次（0/200/400/800/1500/2500/4000ms，内容挂得晚也能赶上）。
     */
    private fun decorateWithBytes(root: View, bytes: ByteArray, key: String, version: Long, attempt: Int) {
        root.post {
            try {
                if (config.bgMap[key] != version) return@post
                val token = "$key:$version"
                if (bgRootTokens[root] == token) {
                    // 已贴好：重试轮次顺手把卡片背景里的动图再 start 一次，防首次启动时机不对
                    restartCardBg(root)
                    applyFontColorForRoot(root)
                } else {
                    val card = findCardIn(root)
                    if (card != null && cardBgTokens[card] == token) {
                        // 同一张卡已经贴着同版本背景：只补 start，不重建（重建会从头播）
                        restartCardBg(root)
                        applyFontColorForRoot(root)
                    } else if (card != null) {
                        val content = buildBgContent(bytes, key, version)
                        if (content != null && applyToCard(card, content, key)) {
                            bgRootTokens[root] = token
                            cardBgTokens[card] = token
                            val fc = config.bgOptFor(key).fontColor
                            synchronized(bgFontByRoot) { bgFontByRoot[root] = fc }
                            applyFontColor(root, fc)
                            log(
                                "BGAPPLY", null,
                                "key=$key kind=${content.kind} bytes=${bytes.size} pkg=$targetPackage",
                            )
                            lg(
                                "bg applied card=${card.javaClass.name} ${card.width}x${card.height} " +
                                    "key=$key bytes=${bytes.size} attempt=$attempt kind=${content.kind}",
                            )
                            if (config.debug) dumpHierarchy(root)
                        }
                    } else if (attempt >= 6) {
                        // 找不到卡片就放弃（旧版垫整窗兜底会糊满屏，已删）
                        lg("bg: no card found in window root=${root.javaClass.simpleName}")
                        if (config.debug) dumpHierarchy(root)
                    }
                }
            } catch (_: Throwable) {}
        }
        if (attempt < 6) {
            root.postDelayed(
                { decorateWithBytes(root, bytes, key, version, attempt + 1) },
                when (attempt) {
                    0 -> 200L
                    1 -> 400L
                    2 -> 800L
                    3 -> 1500L
                    4 -> 2500L
                    else -> 4000L
                },
            )
        }
    }

    /**
     * 找卡片：不再要求自带背景（v1.23 就栽在这——卡片可能没背景、或弹窗根本
     * 不在 Activity 里）。规则：宽 ≥ 窗口一半、高在窗口 15%~92% 之间的大块
     * 可见视图里，自带背景的权重最高、容器次之，按面积挑最大。
     */
    private fun findCardIn(root: View): View? {
        val rw = root.width
        val rh = root.height
        if (rw <= 0 || rh <= 0) return null
        var best: View? = null
        var bestScore = 0L
        fun walk(v: View, depth: Int) {
            if (depth > 12) return
            try {
                if (v !== root && v.visibility == View.VISIBLE) {
                    val w = v.width
                    val h = v.height
                    if (w >= rw * 0.5 && h >= rh * 0.15 && h <= rh * 0.92) {
                        var score = w.toLong() * h.toLong()
                        if (v.background != null) score *= 3
                        if (v is ViewGroup) score = score * 6 / 5
                        if (score > bestScore) {
                            bestScore = score
                            best = v
                        }
                    }
                }
                if (v is ViewGroup) {
                    var i = 0
                    while (i < v.childCount) {
                        walk(v.getChildAt(i), depth + 1)
                        i++
                    }
                }
            } catch (_: Throwable) {}
        }
        walk(root, 0)
        return best
    }

    /**
     * 贴到卡片上：直接把卡片的 background 换成按原圆角裁剪的图片 Drawable。
     * 背景永远画在内容之下，不碰布局——v1.25 用「插入 MATCH_PARENT 图片层」
     * 贴图，卡片若是纵向 LinearLayout 会把耳机图/电量行全挤出画面，已废弃。
     */
    private fun applyToCard(card: View, content: BgContent, key: String): Boolean {
        return try {
            (card.background as? ClippedBgDrawable)?.release()
            val bg = ClippedBgDrawable(
                bitmap = content.bitmap,
                animated = content.animated,
                gifBitmaps = content.gifBitmaps,
                gifDelays = content.gifDelays,
                lazyFrames = content.lazy,
                videoBytes = content.videoBytes,
                opt = config.bgOptFor(key),
                radii = cardRadii(card),
                bgLog = { msg ->
                    lg("bgvid key=$key $msg")
                    log("BGVID", null, "key=$key $msg")
                },
            )
            card.background = bg
            bg.startIfAnimated()
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** 已贴卡片背景里的动图再 start 一次（首次 start 时机不对会停在第一帧）。 */
    private fun restartCardBg(root: View) {
        try {
            val card = findCardIn(root) ?: return
            (card.background as? ClippedBgDrawable)?.startIfAnimated()
        } catch (_: Throwable) {}
    }

    /** 卡片圆角：优先读原背景 GradientDrawable 的逐角半径，其次视图轮廓，最后全 0（直角）。 */
    private fun cardRadii(card: View): FloatArray {
        try {
            val bgd = card.background
            if (bgd is android.graphics.drawable.GradientDrawable) {
                val r = try { bgd.cornerRadii } catch (_: Throwable) { null }
                if (r != null && r.size == 8) return r.clone()
                val u = try { bgd.cornerRadius } catch (_: Throwable) { 0f }
                if (u > 0f) return FloatArray(8) { u }
            }
            val outline = android.graphics.Outline()
            card.outlineProvider?.getOutline(card, outline)
            if (outline.radius > 0f) return FloatArray(8) { outline.radius }
        } catch (_: Throwable) {}
        return FloatArray(8) { 0f }
    }

    /** debug 时把窗口视图层级打进日志，供背景不显形时对着结构再适配。 */
    private fun dumpHierarchy(root: View) {
        try {
            val sb = StringBuilder("bg hierarchy root=${root.javaClass.simpleName}:")
            fun walk(v: View, d: Int) {
                if (sb.length > 1800) return
                sb.append('\n').append("  ".repeat(d)).append(v.javaClass.simpleName)
                    .append(' ').append(v.width).append('x').append(v.height)
                    .append(" bg=").append(v.background?.javaClass?.simpleName ?: "null")
                if (v is ViewGroup) {
                    var i = 0
                    while (i < v.childCount && i < 12) {
                        walk(v.getChildAt(i), d + 1)
                        i++
                    }
                }
            }
            walk(root, 0)
            lg(sb.toString())
        } catch (_: Throwable) {}
    }

    /** 最近一次带 MAC 的设备探测（窗口文字认不出改名耳机时，给背景挑图兜底）。 */
    private fun latestRecentMac(maxAgeMs: Long): String? {
        val now = System.currentTimeMillis()
        return recent.values
            .filter { it.mac != null && now - it.at <= maxAgeMs }
            .maxByOrNull { it.at }
            ?.mac
    }

    private fun remember(probe: Probe) {        try {
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
            if (!param.isFirstPackage) return
            if (hooked) return
            hooked = true
            targetPackage = param.packageName
            refreshConfig(force = true)
            ensureConfigBus()
            val cl = param.classLoader
            if (param.packageName == "com.xiaomi.bluetooth") {
                hookSaveLastBattery(cl)
                hookNotificationCancel(cl)
                hookHeadsetCardText(cl)
            }
            if (param.packageName !in TARGETS) {
                // 不在过滤目标内的进程：只留配置通道，过滤层不进
                lg("battery-only hooks in ${param.packageName}")
                return
            }
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
            maybeCleanupBg()
            return
        }
        val f = FilterConfig.load(CONF_FILE)
        // 文件能读到（普通进程）就用文件并标注来源；读不到时 load 返回默认配置，
        // 来源保持当前值（可能是 cache/default），日志里能看出这个进程没拿到真实配置。
        if (CONF_FILE.canRead()) {
            config = f
            configSource = "file"
        }
        maybeCleanupBg()
    }

    @Volatile private var lastCleanedBgMap: Map<String, Long>? = null

    /**
     * 背景集合变了（删除/替换）时，清掉不再被引用的帧包文件与内存帧源：
     * 用户在设置里删掉的背景，不能在弹窗进程的缓存目录里一直留着。
     * 配置没变时零开销（快照比对后直接返回）。
     */
    private fun maybeCleanupBg() {
        val ctx = appContext ?: return
        val current = config.bgMap
        if (lastCleanedBgMap == current) return
        lastCleanedBgMap = current
        Thread {
            try {
                val stale = synchronized(bgLock) {
                    val out = ArrayList<LazyFrames>()
                    val it = bgLazyFrames.entries.iterator()
                    while (it.hasNext()) {
                        val e = it.next()
                        val k = e.key.substringBefore(':')
                        val v = e.key.substringAfter(':').toLongOrNull()
                        if (current[k] != v) {
                            out.add(e.value)
                            it.remove()
                            if (bgBundleVer[k] == v) bgBundleVer.remove(k)
                        }
                    }
                    out
                }
                for (lf in stale) {
                    lf.close()
                    lf.file?.let { try { it.delete() } catch (_: Throwable) {} }
                }
                synchronized(bgLock) {
                    val kit = bgCache.keys.iterator()
                    while (kit.hasNext()) {
                        if (kit.next() !in current) kit.remove()
                    }
                    val git = bgGifFrames.keys.iterator()
                    while (git.hasNext()) {
                        val gk = git.next()
                        if (current[gk.substringBefore(':')] !=
                            gk.substringAfter(':').toLongOrNull()
                        ) {
                            git.remove()
                        }
                    }
                    bgGifDelays.keys.retainAll(bgGifFrames.keys)
                    val pit = bgVideoPoster.keys.iterator()
                    while (pit.hasNext()) {
                        val pk = pit.next()
                        if (current[pk.substringBefore(':')] !=
                            pk.substringAfter(':').toLongOrNull()
                        ) {
                            pit.remove()
                        }
                    }
                }
                // 磁盘扫尾：缓存目录里不再被当前配置引用的帧包/下载临时文件一律删掉
                val files = try { ctx.cacheDir.listFiles() } catch (_: Throwable) { null }
                if (files != null) {
                    for (f in files) {
                        val name = f.name
                        if (name.startsWith("mcf_bg_") && name.endsWith(".zip")) {
                            val core = name.removePrefix("mcf_bg_").removeSuffix(".zip")
                            val k = core.substringBeforeLast('_')
                            val v = core.substringAfterLast('_').toLongOrNull()
                            if (current[k] != v) {
                                try { f.delete() } catch (_: Throwable) {}
                            }
                        } else if (name.startsWith("mcf_bg_dl_") && name.endsWith(".tmp")) {
                            val k = name.removePrefix("mcf_bg_dl_").removeSuffix(".tmp")
                            val inFlight = synchronized(bgLock) { k in bgInFlight }
                            if (!inFlight) {
                                try { f.delete() } catch (_: Throwable) {}
                            }
                        }
                    }
                }
                lg("bg cleanup done, keep=${current.keys}")
            } catch (_: Throwable) {}
        }.start()
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
            if (config.bgMap.isNotEmpty()) prewarmBg(app)
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
                            bgMapCsv = intent.getStringExtra("bg_map") ?: "",
                            bgOptsCsv = intent.getStringExtra("bg_opts") ?: "",
                            fontColor = intent.getIntExtra("font_color", 0),
                            batteryCsv = intent.getStringExtra("battery") ?: "",
                            batterySync = intent.getBooleanExtra("battery_sync", true),
                            battery3Csv = intent.getStringExtra("battery3") ?: "",
                            batteryKeep = intent.getBooleanExtra("battery_keep", true),
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
                                    FilterConfig.render(cfg.enabled, cfg.whitelist, cfg.bypassUntilMs, cfg.debug, cfg.bgMap, cfg.bgOpts, cfg.fontColor, cfg.battery, cfg.batterySync, cfg.battery3, cfg.batteryKeep),
                                )
                            }
                        } catch (_: Throwable) {}
                        // 配置刚到时弹窗可能已经显示、当时还没背景配置：对最近的弹窗根补一次贴图
                        if (cfg.bgMap.isNotEmpty()) {
                            val r = lastBgRoot?.get()
                            val c = appContext
                            if (r != null && c != null) {
                                r.post {
                                    try {
                                        decorateRoot(r, lastBgMac, c)
                                    } catch (_: Throwable) {}
                                }
                            }
                        }
                        maybeCleanupBg()
                        if (cfg.bgMap.isNotEmpty()) appContext?.let { prewarmBg(it) }
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
                putExtra("from", targetPackage)
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
        // 已拿到配置（广播推送或私有缓存）就不再周期拉取：变更都靠广播即时推送，
        // 周期拉取只会每分钟把设置页进程唤醒一次（省电）。还没配置的进程才继续拉。
        if (busConfig != null) return
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

    @Volatile private var lastDecideRefreshMs = 0L

    private fun decide(probe: Probe, site: String): Decision {
        // 配置靠广播推送即时更新；判定路径里的 refresh 只是兜底愈合，5 秒节流，
        // 避免蓝牙广播密集时每个广播都跑一遍配置读取（省电）。
        val nowRt = SystemClock.elapsedRealtime()
        if (nowRt - lastDecideRefreshMs > 5_000) {
            lastDecideRefreshMs = nowRt
            refreshConfig()
        }
        remember(probe)
        val (d, reason) = FilterCore.decideWithReason(config, probe, System.currentTimeMillis())
        // 弹窗事件全量采集（带设备信息的才记）：进程/入口/设备/判定/原因/当时配置，
        // 同一条 5 秒去重。排查时看导出的 logcat 即可，不用猜。
        val hasDevice = probe.mac != null || probe.deviceName != null || probe.xiaomiEarbud
        if (hasDevice && config.debug) {
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

    // ------------------------------------------------------------ 三值电量：保住小米自己的缓存
    //
    // 蓝牙扩展的耳机服务把左/右/盒三值缓存在 DeviceIdCached 的
    // BATTERY_LEVEL_CACHED_CONFIG<MAC> 里（写入点 FragmentHandler.saveLastBattery），
    // 耳机 app、通知、离线查找都读它；但设备断开 MMA 连接时服务会以 clear=true
    // 调 saveLastBattery 把缓存删掉，断联后电量消失就是这么来的。这里只拦白名单
    // 设备的清除调用，写入原样放行：不轮询、不自存、零额外功耗。
    // 受 config.batterySync 控制，关了不拦（系统恢复原样删除）。

    private fun hookSaveLastBattery(cl: ClassLoader) {
        hookAll(cl, "com.android.bluetooth.ble.app.headset.FragmentHandler",
            { m -> m.name == "saveLastBattery" && m.parameterTypes.size == 3 },
            {
                object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        try {
                            if (config.batterySync && chain.args.getOrNull(2) != true) {
                                val devW = chain.args.getOrNull(1) as? BluetoothDevice
                                val macW = try { devW?.address } catch (_: Throwable) { null }
                                val normW = MacUtil.normalize(macW)
                                val strW = chain.args.getOrNull(0) as? String
                                if (normW != null && strW != null && config.whitelist.contains(normW)) {
                                    reportTriple(normW, strW)
                                }
                            }
                            if (config.batterySync && config.batteryKeep && chain.args.getOrNull(2) == true) {
                                val dev = chain.args.getOrNull(1) as? BluetoothDevice
                                val mac = try { dev?.address } catch (_: Throwable) { null }
                                val norm = MacUtil.normalize(mac)
                                if (norm != null && config.whitelist.contains(norm)) {
                                    lg("battery cache clear vetoed for $norm")
                                    reportDisconnect(norm)
                                    return null
                                }
                            }
                        } catch (_: Throwable) {}
                        return chain.proceed(chain.args.toTypedArray())
                    }
                }
            })
    }

    private val lastTripleSent = HashMap<String, String>()

    /** 断联信号（扩展进程拦下缓存清除时发出）：设置页收到后强制同步一轮，
     *  蓝牙栈进程据此检查框架存电表、被清掉就重写，治断开清理绕过拦截的情况。 */
    private fun reportDisconnect(mac: String) {
        try {
            val app = appContext ?: return
            val intent = Intent(ConfigBus.BATTERY_ACTION).apply {
                setPackage(ConfigBus.MODULE_PACKAGE)
                putExtra("token", ConfigBus.TOKEN)
                putExtra("mac", mac)
                putExtra("disc", true)
            }
            app.sendBroadcast(intent)
            lg("battery disconnect reported $mac")
        } catch (_: Throwable) {}
    }

    /** 耳机服务写入缓存的三值回传设置页落盘，再经配置通道同步到蓝牙栈进程写进框架。 */
    private fun reportTriple(mac: String?, tripleCsv: String) {
        if (!config.batterySync) return
        val norm = MacUtil.normalize(mac) ?: return
        val nums = tripleCsv.split(',').mapNotNull { it.trim().toIntOrNull()?.and(0x7F) }
        if (nums.size != 3 || nums.any { it !in 0..100 }) return
        val csv = nums.joinToString(",")
        synchronized(lastTripleSent) {
            if (lastTripleSent[norm] == csv) return
            lastTripleSent[norm] = csv
        }
        try {
            val key = MacUtil.keyOf(norm)
            if (key != null && config.battery3[key] != csv) {
                val next = LinkedHashMap(config.battery3)
                next[key] = csv
                config = config.copy(battery3 = next)
            }
        } catch (_: Throwable) {}
        try {
            val app = appContext ?: return
            val intent = Intent(ConfigBus.BATTERY_ACTION).apply {
                setPackage(ConfigBus.MODULE_PACKAGE)
                putExtra("token", ConfigBus.TOKEN)
                putExtra("mac", norm)
                putExtra("triple", csv)
            }
            app.sendBroadcast(intent)
            lg("battery3 report $norm=$csv")
        } catch (_: Throwable) {}
    }

    // ------------------------------------------------------------ 标明是断联时电量
    //
    // 框架的存电表只有数值、没有「断联时」标记位，标明只能落在显示层：
    // 1) 断开瞬间的那条提示（灵动岛/焦点通知）：正文后拼上断联时三值；
    // 2) 设置蓝牙列表页（需用户把 com.android.settings 加进作用域）：设备已断开、
    //    但框架里还留着它的三值时，在该设备摘要后加「（断联时电量）」。
    // 两处都只改显示文字，不动框架里的数。

    private fun readCachedTriple(mac: String): Triple<Int, Int, Int>? {
        return try {
            val ctx = appContext ?: return null
            val sp = ctx.getSharedPreferences("DeviceIdCached", 4)
            val raw = sp.getString("BATTERY_LEVEL_CACHED_CONFIG" + mac, null) ?: return null
            val parts = raw.split(',').mapNotNull { it.trim().toIntOrNull()?.and(0x7F) }
            if (parts.size != 3 || parts.any { it !in 0..100 }) null
            else Triple(parts[0], parts[1], parts[2])
        } catch (_: Throwable) { null }
    }

    /** 取消被拦下的同一刻，把存好的卡原件正文加上「（断联时）」重发一次：
     *  卡是连接时发出的、之后不再更新，不在取消时刻重发就永远没机会标明。 */
    private fun remarkCard(nmObj: Any?, tag: String, id: Int) {
        try {
            val notif = lastCardByTag[tag] ?: run {
                lg("headset card remark skipped: no stash for $tag")
                return
            }
            val extras = notif.extras ?: return
            val cur = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return
            // 全部断联：三个值都是断联时电量，逐个值后标明（已标的跳过）
            var nt = Regex("""(\d+%)(?!断)""").replace(cur) { it.groupValues[1] + "断" }
            if (nt == cur) {
                if (cur.contains("断")) return
                nt = cur + "断"
            }
            extras.putCharSequence(Notification.EXTRA_TEXT, nt)
            val nm = nmObj as? android.app.NotificationManager ?: return
            nm.notify(tag, id, notif)
            lg("headset card remarked tag=$tag")
        } catch (e: Throwable) {
            lg("headset card remark failed: $e")
        }
    }

    /** 耳机连接卡（tag=BTHeadset<MAC>）的正文由扩展 headset.n 拼装：没连上的
     *  部分（在仓里的耳、仓本身）一律填「-」。在 notify 出口把正文里的「-」
     *  按顺序（盒、左、右）换成保住的三值；已断开时再加「（断联时）」。
     *  这样单耳在外、双耳在外、全部断开三种状态卡上都是完整三值。 */
    /** 最近一次发出的耳机卡原件（按 tag 存）：断开取消被拦时用它重发一份带标明的。 */
    private val lastCardByTag = HashMap<String, Notification>()

    private fun hookHeadsetCardText(cl: ClassLoader) {
        hookAll(cl, "android.app.NotificationManager",
            { m -> m.name == "notify" || m.name == "notifyAsUser" || m.name == "notifyAsPackage" },
            {
                object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        try {
                            if (config.batterySync) {
                                val tag = chain.args.firstOrNull { it is String } as? String
                                val notif = chain.args.firstOrNull { it is Notification } as? Notification
                                if (tag != null && notif != null && tag.startsWith("BTHeadset")) {
                                    val mac = MacUtil.normalize(tag.removePrefix("BTHeadset"))
                                    if (mac != null && config.whitelist.contains(mac)) {
                                        val t = readCachedTriple(mac) ?: parseTripleCsv(config.battery3For(mac))
                                        if (t != null) {
                                            val extras = notif.extras
                                            val cur = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                                            if (cur != null) {
                                                // 标明精确到部位：被补的那个值后面直接跟「断」。
                                                // 补值必标，只跟「断联电量显示」开关走；「全断联时保留电量」
                                                // 只管整机断开时留不留卡，不管这里的标。
                                                val nt = fillCardText(cur, t.first, t.second, t.third, true)
                                                if (nt != cur) {
                                                    extras.putCharSequence(Notification.EXTRA_TEXT, nt)
                                                    lg("headset card text filled $mac -> ${nt.replace("\n", " / ")}")
                                                }
                                                lastCardByTag[tag] = notif
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (_: Throwable) {}
                        return chain.proceed(chain.args.toTypedArray())
                    }
                }
            })
    }

    /** 卡正文里「：-」按出现顺序是盒、左、右，只补空缺，真实值不覆盖。 */
    private fun fillCardText(text: String, l: Int, r: Int, b: Int, mark: Boolean = false): String {
        val suffix = if (mark) "断" else ""
        val vals = listOf("$b%" + suffix, "$l%" + suffix, "$r%" + suffix)
        var idx = 0
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            if (idx < 3 && text.startsWith("：-", i)) {
                sb.append("：").append(vals[idx])
                idx++
                i += 2
            } else {
                sb.append(text[i])
                i++
            }
        }
        return sb.toString()
    }

    private fun parseTripleCsv(csv: String?): Triple<Int, Int, Int>? {
        if (csv.isNullOrBlank()) return null
        val parts = csv.split(',').mapNotNull { it.trim().toIntOrNull() }
        return if (parts.size == 3) Triple(parts[0], parts[1], parts[2]) else null
    }

    private fun hookNotificationCancel(cl: ClassLoader) {
        hookAll(cl, "android.app.NotificationManager",
            { m -> m.name == "cancel" || m.name == "cancelAsUser" || m.name == "cancelAll" },
            {
                object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        try {
                            val tag = chain.args.firstOrNull { it is String } as? String
                            val id = chain.args.firstOrNull { it is Int } as? Int
                            val headset = tag != null && (tag.contains("BTHeadset") ||
                                config.whitelist.any { tag.contains(it) })
                            lg("notify cancel seen tag=$tag id=$id headset=$headset")
                            if (headset && chain.args.isNotEmpty() &&
                                config.batterySync && config.batteryKeep
                            ) {
                                lg("notify cancel vetoed tag=$tag id=$id")
                                if (tag != null && id != null) remarkCard(chain.thisObject, tag, id)
                                return null
                            }
                        } catch (_: Throwable) {}
                        return chain.proceed(chain.args.toTypedArray())
                    }
                }
            })
    }

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

    /** 电量探针（仅 debug）：把弹窗 intent 的全部 extras 打进日志，定位电量字段的位置。 */
    private fun dumpIntentExtras(intent: Intent) {
        try {
            val cls = intent.component?.className ?: intent.action ?: "?"
            val extras = intent.extras
            if (extras == null) {
                lg("intentprobe $cls extras=null")
                return
            }
            val sb = StringBuilder("intentprobe $cls extras:")
            for (k in extras.keySet()) {
                val v = try { extras.get(k) } catch (_: Throwable) { null }
                val s = when (v) {
                    is ByteArray -> "hex:" + v.joinToString("") { "%02x".format(it) }.take(440)
                    is IntArray -> v.joinToString(",", "[", "]")
                    is Array<*> -> v.joinToString(",", "[", "]") { it?.toString() ?: "null" }
                    else -> v?.toString()?.replace('\n', ' ')?.take(800) ?: "null"
                }
                sb.append("\n  ").append(k).append('=').append(s)
            }
            lg(sb.toString().take(6000))
        } catch (_: Throwable) {}
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
                                    } else {
                                        // 弹窗被放行（自家耳机）：按这台设备挑背景图贴进卡片
                                        applyPopupBackground(activity, probe.mac)
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
                                joined.contains("左耳") || joined.contains("右耳") ||
                                MacUtil.extractMacs(joined).isNotEmpty()
                            val probe = enrichWithRecent(DeviceProbe.fromTexts(texts))
                            if (earbudish) {
                                if (decide(probe, "window:addView") == Decision.BLOCK) {
                                    val args = chain.args.toTypedArray()
                                    shrinkParams(args)
                                    synchronized(blockedViews) { blockedViews.add(view) }
                                    lg("window hidden mac=${probe.mac} name=${probe.deviceName}")
                                    return chain.proceed(args)
                                }
                                // 放行的耳机窗口：弹窗若是独立窗口/Dialog，Activity 层
                                // 够不到，就在这里给这个窗口的卡片贴背景
                                decorateRoot(view, probe.mac, view.context.applicationContext)
                                applyFontColorForRoot(view)
                            } else if (config.bgMap.isNotEmpty()) {
                                // 改过名的耳机（如改成玩笑名）文字里没有任何耳机关键词，
                                // 窗口层此前直接放行、背景永远贴不上。用最近一次弹窗探测
                                // 的 MAC 兜底，只贴背景不参与拦截判定。
                                val macHint = probe.mac ?: latestRecentMac(15_000L)
                                if (macHint != null || config.bgMap.containsKey(FilterConfig.BG_ALL_KEY)) {
                                    decorateRoot(view, macHint, view.context.applicationContext)
                                    applyFontColorForRoot(view)
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
                                if (config.debug && intent != null) {
                                    val act = intent.action ?: ""
                                    if (act.contains("bluetooth", true) &&
                                        (act.contains("ACL") || act.contains("CONNECTION_STATE") ||
                                            act.contains("BATTERY"))
                                    ) {
                                        dumpIntentExtras(intent)
                                    }
                                }
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

/**
 * 弹窗卡片背景：把图片按「显示区域」选项（缩放 + 焦点）裁剪后画满卡片，
 * 并按卡片原来的逐角圆角裁剪。内容三种：
 * - 静态图：直接画 Bitmap；
 * - GIF：自解码帧 + 自有播放时钟（GifTimeline 管循环/播一次停/往返，
 *   速度按 speedPct 折算），不依赖系统动图Drawable的回调机制；
 * - WebP 等系统动图：AnimatedImageDrawable 作内层，回调标准转发。
 * 作卡片 background 使用，天然画在内容之下。
 */
/** 内存字节喂给 MediaExtractor / MediaMetadataRetriever 的随机访问数据源。 */
private fun mediaDataSourceFor(bytes: ByteArray): android.media.MediaDataSource =
    object : android.media.MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= bytes.size) return -1
            val n = minOf(size.toLong(), bytes.size - position).toInt()
            System.arraycopy(bytes, position.toInt(), buffer, offset, n)
            return n
        }

        override fun getSize(): Long = bytes.size.toLong()

        override fun close() {}
    }

/**
 * 视频直播放器：MP4 字节常驻内存，MediaExtractor + MediaCodec（缓冲模式、无 Surface）
 * 按原视频时间戳逐帧解出 Bitmap，原帧率播放。模式：循环（放完回跳重解）/ 播一次停
 * （停在最后一帧）/ 往返（正放时缓存帧，放完用缓存倒放、正放，无限往返；缓存超限
 * 退化为循环）。速度按 speedPct 缩放时钟。不可见即暂停（时钟冻结）；暂停超 90 秒
 * 线程自退，防弹窗关掉后在蓝牙进程里空转。
 */
private class VideoBgPlayer(
    private val bytes: ByteArray,
    private val opt: BgOpt,
    private val onFrame: (android.graphics.Bitmap) -> Unit,
    private val onError: (String) -> Unit,
    private val onEvent: (String) -> Unit = {},
) {

    @Volatile private var released = false
    @Volatile private var wantPlay = false
    @Volatile private var thread: Thread? = null
    @Volatile private var current: android.graphics.Bitmap? = null

    fun currentFrame(): android.graphics.Bitmap? = current

    fun start() {
        wantPlay = true
        synchronized(this) {
            if (!released && thread?.isAlive != true) {
                thread = Thread({ runLoop() }, "mcf-bg-video").apply {
                    isDaemon = true
                    start()
                }
            }
        }
    }

    fun pause() {
        wantPlay = false
    }

    fun release() {
        released = true
        wantPlay = false
    }

    /** 播放时钟：位置（µs）随真实时间 × 速度推进，暂停冻结。只在解码线程里操作。 */
    private class Clock(private val speedPct: Int) {
        private var basePosUs = 0L
        private var baseStamp = 0L
        private var running = false

        fun restart() {
            basePosUs = 0L
            baseStamp = android.os.SystemClock.uptimeMillis()
            running = true
        }

        fun pos(): Long =
            if (running) {
                basePosUs + (android.os.SystemClock.uptimeMillis() - baseStamp) * 10L * speedPct
            } else {
                basePosUs
            }

        fun pause() {
            if (running) {
                basePosUs = pos()
                running = false
            }
        }

        fun resume() {
            if (!running) {
                baseStamp = android.os.SystemClock.uptimeMillis()
                running = true
            }
        }
    }

    /** 等时钟走到 posUs；暂停时冻结等待，释放或暂停超时（90 秒）返回 false。 */
    private fun waitUntil(clock: Clock, posUs: Long): Boolean {
        var pausedSince = 0L
        while (!released) {
            if (!wantPlay) {
                clock.pause()
                val now = android.os.SystemClock.uptimeMillis()
                if (pausedSince == 0L) pausedSince = now
                if (now - pausedSince > 90_000L) return false
                try {
                    Thread.sleep(30)
                } catch (_: InterruptedException) {
                    return false
                }
                continue
            }
            pausedSince = 0L
            clock.resume()
            val remainMs = (posUs - clock.pos()) / 1000L
            if (remainMs <= 0) return true
            try {
                Thread.sleep(minOf(remainMs, 20L).coerceAtLeast(1L))
            } catch (_: InterruptedException) {
                return false
            }
        }
        return false
    }

    private fun runLoop() {
        val extractor = android.media.MediaExtractor()
        var codec: android.media.MediaCodec? = null
        try {
            extractor.setDataSource(mediaDataSourceFor(bytes))
            var mime = ""
            var format: android.media.MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val m = f.getString(android.media.MediaFormat.KEY_MIME) ?: continue
                if (m.startsWith("video/")) {
                    extractor.selectTrack(i)
                    mime = m
                    format = f
                    break
                }
            }
            val fmt = format ?: run {
                onError("no video track")
                return
            }
            val rotation = try {
                fmt.getInteger(android.media.MediaFormat.KEY_ROTATION)
            } catch (_: Throwable) {
                0
            }
            // 只取解码必需字段建新 format：抽取器给的 format 带厂商私有项，
            // 个别机型 configure 会因此失败，净化掉
            val clean = android.media.MediaFormat()
            clean.setString(android.media.MediaFormat.KEY_MIME, mime)
            try {
                clean.setInteger(
                    android.media.MediaFormat.KEY_WIDTH,
                    fmt.getInteger(android.media.MediaFormat.KEY_WIDTH),
                )
                clean.setInteger(
                    android.media.MediaFormat.KEY_HEIGHT,
                    fmt.getInteger(android.media.MediaFormat.KEY_HEIGHT),
                )
            } catch (_: Throwable) {}
            for (csd in listOf("csd-0", "csd-1")) {
                try {
                    if (fmt.containsKey(csd)) clean.setByteBuffer(csd, fmt.getByteBuffer(csd))
                } catch (_: Throwable) {}
            }
            val decoder = android.media.MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(clean, null, null, 0)
            decoder.start()
            onEvent("start mime=$mime rot=$rotation")

            val clock = Clock(opt.speedPct)
            var bufFrames: ArrayList<android.graphics.Bitmap>? =
                if (opt.mode == BgOpt.MODE_PINGPONG) ArrayList() else null
            var bufRels: ArrayList<Long>? =
                if (opt.mode == BgOpt.MODE_PINGPONG) ArrayList() else null
            var bufPx = 0L

            var round = 0
            while (!released) {
                round++
                if (round > 1) {
                    val bf = bufFrames
                    val br = bufRels
                    if (bf != null && br != null && bf.size >= 2) {
                        // 往返的后续轮次：缓存正放 + 倒放，不再解码
                        if (!playBuffer(bf, br, forward = true, clock)) return
                        if (!playBuffer(bf, br, forward = false, clock)) return
                        continue
                    }
                    extractor.seekTo(0L, android.media.MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    try { decoder.flush() } catch (_: Throwable) {}
                }
                val eos = decodeForward(extractor, decoder, rotation, clock) { bmp, rel ->
                    val bf = bufFrames
                    val br = bufRels
                    if (bf != null && br != null) {
                        bufPx += bmp.width.toLong() * bmp.height.toLong()
                        if (bufPx > 48_000_000L || bf.size > 240) {
                            bufFrames = null
                            bufRels = null
                        } else {
                            bf.add(bmp)
                            br.add(rel)
                        }
                    }
                }
                if (!eos || released) return
                when (opt.mode) {
                    BgOpt.MODE_ONCE -> return
                    BgOpt.MODE_PINGPONG -> {
                        val bf = bufFrames
                        val br = bufRels
                        if (bf != null && br != null && bf.size >= 2) {
                            if (!playBuffer(bf, br, forward = false, clock)) return
                        }
                        // 缓存被丢弃时退化为循环：下一轮回跳重解
                    }
                    else -> Unit // 循环：下一轮回跳重解
                }
            }
        } catch (t: Throwable) {
            if (!released) onError(t.message ?: "decode error")
        } finally {
            try { codec?.stop() } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
            try { extractor.release() } catch (_: Throwable) {}
            synchronized(this) {
                if (thread === Thread.currentThread()) thread = null
            }
        }
    }

    /** 正向解码一遍：按时间戳等待后逐帧交付。返回 true = 正常放到流尾。 */
    private fun decodeForward(
        extractor: android.media.MediaExtractor,
        codec: android.media.MediaCodec,
        rotation: Int,
        clock: Clock,
        deliver: (android.graphics.Bitmap, Long) -> Unit,
    ): Boolean {
        val info = android.media.MediaCodec.BufferInfo()
        var inputDone = false
        var firstPts = -1L
        var delivered = 0
        var convertFails = 0
        val startStamp = android.os.SystemClock.uptimeMillis()
        clock.restart()
        while (!released) {
            // 看门狗：解码器不出帧（格式/厂商坑）时别静默空转，报错退出、海报兜底
            if (delivered == 0 &&
                android.os.SystemClock.uptimeMillis() - startStamp > 8_000L
            ) {
                onError("no frame decoded in 8s")
                return false
            }
            if (!inputDone) {
                val inIdx = try {
                    codec.dequeueInputBuffer(10_000L)
                } catch (_: Throwable) {
                    -1
                }
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx) ?: return false
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(
                            inIdx, 0, 0, 0,
                            android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                        )
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIdx = try {
                codec.dequeueOutputBuffer(info, 10_000L)
            } catch (_: Throwable) {
                -1
            }
            if (outIdx >= 0) {
                val eos = info.flags and
                    android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                if (info.size > 0) {
                    if (firstPts < 0) firstPts = info.presentationTimeUs
                    val rel = info.presentationTimeUs - firstPts
                    val img = try {
                        codec.getOutputImage(outIdx)
                    } catch (_: Throwable) {
                        null
                    }
                    if (img != null) {
                        val bmp = yuvToBitmap(img, rotation)
                        img.close()
                        if (bmp == null) {
                            convertFails++
                            if (convertFails > 30) {
                                onError("frame convert failed x$convertFails")
                                try { codec.releaseOutputBuffer(outIdx, false) } catch (_: Throwable) {}
                                return false
                            }
                        } else {
                            convertFails = 0
                            if (!waitUntil(clock, rel)) {
                                try { codec.releaseOutputBuffer(outIdx, false) } catch (_: Throwable) {}
                                return false
                            }
                            delivered++
                            if (delivered == 1) {
                                onEvent("first frame ${bmp.width}x${bmp.height}")
                            }
                            current = bmp
                            onFrame(bmp)
                            deliver(bmp, rel)
                        }
                    }
                }
                try { codec.releaseOutputBuffer(outIdx, false) } catch (_: Throwable) {}
                if (eos) return true
            }
        }
        return false
    }

    /** 按缓存帧的相对时间戳正放/倒放一遍（往返模式用，帧已全部解好）。 */
    private fun playBuffer(
        frames: List<android.graphics.Bitmap>,
        rels: List<Long>,
        forward: Boolean,
        clock: Clock,
    ): Boolean {
        val total = rels.lastOrNull() ?: 0L
        clock.restart()
        if (forward) {
            for (i in frames.indices) {
                if (!waitUntil(clock, rels[i])) return false
                current = frames[i]
                onFrame(frames[i])
            }
        } else {
            for (i in frames.indices.reversed()) {
                if (!waitUntil(clock, total - rels[i])) return false
                current = frames[i]
                onFrame(frames[i])
            }
        }
        val tail = if (rels.size >= 2) {
            (rels.last() - rels[rels.size - 2]).coerceIn(10_000L, 500_000L)
        } else {
            100_000L
        }
        return waitUntil(clock, total + tail)
    }

    /** 解码输出转 Bitmap：整数 BT.601，采样到长边 ≤960 省 CPU，带旋转修正。 */
    private fun yuvToBitmap(
        image: android.media.Image,
        rotation: Int,
    ): android.graphics.Bitmap? {
        return try {
            val crop = image.cropRect
            val w0 = crop.width()
            val h0 = crop.height()
            if (w0 <= 0 || h0 <= 0) return null
            val step = maxOf(1, (maxOf(w0, h0) + 959) / 960)
            val w = (w0 + step - 1) / step
            val h = (h0 + step - 1) / step
            val yPlane = image.planes[0]
            val uPlane = image.planes[1]
            val vPlane = image.planes[2]
            val yBuf = yPlane.buffer
            val uBuf = uPlane.buffer
            val vBuf = vPlane.buffer
            val pixels = IntArray(w * h)
            for (yy in 0 until h) {
                val srcY = crop.top + yy * step
                val yRow = srcY * yPlane.rowStride
                val uRow = (srcY / 2) * uPlane.rowStride
                val vRow = (srcY / 2) * vPlane.rowStride
                var dst = yy * w
                for (xx in 0 until w) {
                    val srcX = crop.left + xx * step
                    val yv = yBuf.get(yRow + srcX * yPlane.pixelStride).toInt() and 0xFF
                    val uv = (uBuf.get(uRow + (srcX / 2) * uPlane.pixelStride).toInt() and 0xFF) - 128
                    val vv = (vBuf.get(vRow + (srcX / 2) * vPlane.pixelStride).toInt() and 0xFF) - 128
                    val y1192 = 1192 * (yv - 16).coerceAtLeast(0)
                    val r = ((y1192 + 1634 * vv) shr 10).coerceIn(0, 255)
                    val g = ((y1192 - 833 * vv - 400 * uv) shr 10).coerceIn(0, 255)
                    val b = ((y1192 + 2066 * uv) shr 10).coerceIn(0, 255)
                    pixels[dst++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            var bmp = android.graphics.Bitmap.createBitmap(
                w, h, android.graphics.Bitmap.Config.ARGB_8888,
            )
            bmp.setPixels(pixels, 0, w, 0, 0, w, h)
            if (rotation != 0) {
                val m = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
                bmp = android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }
            bmp
        } catch (_: Throwable) {
            null
        }
    }
}

/**
 * 视频帧包的懒解码帧源：只保存每帧的 JPEG 字节，画到哪帧才解哪帧，
 * 解码结果按 LRU 只留最近 10 帧。弹窗通常只开几秒，没播到的帧永远不解码——省 CPU、省内存。
 */
private class LazyFrames private constructor(
    private val zipFile: java.util.zip.ZipFile?,
    private val entryNames: List<String>,
    private val memFrames: List<ByteArray>?,
    val delays: IntArray,
) {

    constructor(jpegs: List<ByteArray>, delays: IntArray) :
        this(null, emptyList(), jpegs, delays)

    constructor(zip: java.util.zip.ZipFile, names: List<String>, delays: IntArray) :
        this(zip, names, null, delays)

    /** 文件后端的 ZIP 文件（替换/清理时用）；内存后端为 null。 */
    val file: java.io.File? get() = zipFile?.let { java.io.File(it.name) }

    // 后台预解码：播放时主线程只贴图、不再同步解码（原画质帧一张解码几十 ms，
    // 在主线程解必掉帧）。解码只在显示侧采样到长边 ≤2048，存的文件不动。
    private val zipLock = Any()
    private val decodePool = java.util.concurrent.Executors.newFixedThreadPool(4) { r ->
        Thread(r, "mcf-bg-decode").apply { isDaemon = true }
    }
    private val queued: MutableSet<Int> =
        java.util.Collections.synchronizedSet(HashSet())

    // 缓存像素预算（位图内存约 = 像素 × 4 字节）
    private val pxBudget = 30_000_000L

    // 一帧的像素数（首帧解出后实测）：预取窗口按 预算/帧像素 定——窗口比缓存能装的
    // 帧数还大时，还没播到的帧会被先挤出缓存、回头再重解，是掉帧的主因之一
    @Volatile private var framePx: Long = 0L

    // 当前真正需要的帧集合：解码任务执行时已不在集合里（方向翻转/跳帧）就跳过，
    // 不让过期帧白占解码线程
    @Volatile private var wanted: Set<Int> = emptySet()

    /** 预取窗口（不含当前帧）：缓存装得下的帧数留 2 帧余量。 */
    fun windowSize(): Int {
        val px = framePx
        if (px <= 0L) return 10
        return ((pxBudget / px).toInt() - 2).coerceIn(5, 18)
    }

    // 解码缓存按像素预算淘汰：原画质帧一张就好几 MB，只按张数算会爆内存
    private val cache = object : java.util.LinkedHashMap<Int, android.graphics.Bitmap>(
        16, 0.75f, true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<Int, android.graphics.Bitmap>,
        ): Boolean {
            if (size <= 3) return false
            var px = 0L
            for (b in values) px += b.width.toLong() * b.height
            return px > pxBudget
        }
    }

    val size: Int get() = delays.size

    private fun readJpeg(i: Int): ByteArray? {
        val zf = zipFile
        if (zf != null) {
            synchronized(zipLock) {
                val e = try { zf.getEntry(entryNames[i]) } catch (_: Throwable) { null }
                    ?: return null
                return try {
                    zf.getInputStream(e).use { it.readBytes() }
                } catch (_: Throwable) {
                    null
                }
            }
        }
        return memFrames?.getOrNull(i)
    }

    private fun decodeRaw(i: Int): android.graphics.Bitmap? {
        val jpg = readJpeg(i) ?: return null
        val bmp = try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpg, 0, jpg.size, bounds)
            var sample = 1
            val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
            while (maxDim / (sample * 2) >= 2048) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeByteArray(jpg, 0, jpg.size, opts)
        } catch (_: Throwable) {
            null
        }
        if (bmp != null && framePx == 0L) {
            framePx = bmp.width.toLong() * bmp.height
        }
        return bmp
    }

    fun frame(i: Int): android.graphics.Bitmap? {
        synchronized(this) { cache[i]?.let { return it } }
        val bmp = decodeRaw(i) ?: return null
        synchronized(this) { cache[i] = bmp }
        return bmp
    }

    /** 只取已解码的缓存（不触发解码）：绘制用，未就绪就跳过、绝不卡主线程。 */
    fun frameCached(i: Int): android.graphics.Bitmap? = synchronized(this) { cache[i] }

    /** 后台预解码后续帧（按帧号环形往前取；只适合启动预热，播放中要用 prefetchFrames 沿序列预取）。 */
    fun prefetch(from: Int) {
        if (size < 2) return
        val list = ArrayList<Int>(16)
        for (k in 1..16) {
            val idx = (from + k) % size
            if (idx != from) list.add(idx)
        }
        enqueue(list)
    }

    private fun enqueue(indices: List<Int>) {
        if (size < 2) return
        wanted = indices.toSet()
        for (idx in indices) {
            if (idx < 0 || idx >= size) continue
            val need = synchronized(this) {
                !cache.containsKey(idx) && queued.add(idx)
            }
            if (!need) continue
            try {
                decodePool.execute {
                    try {
                        if (idx in wanted && frameCached(idx) == null) {
                            decodeRaw(idx)?.let { bmp ->
                                synchronized(this) { cache[idx] = bmp }
                            }
                        }
                    } catch (_: Throwable) {
                    } finally {
                        queued.remove(idx)
                    }
                }
            } catch (_: Throwable) {
                queued.remove(idx)
            }
        }
    }

    /** 按播放序列顺序预解码一组帧（往返逆向段传入的是递减序号）。 */
    fun prefetchFrames(indices: List<Int>) {
        enqueue(indices)
    }

    fun close() {
        try { decodePool.shutdownNow() } catch (_: Throwable) {}
        try { zipFile?.close() } catch (_: Throwable) {}
    }
}

private class ClippedBgDrawable(
    private val bitmap: android.graphics.Bitmap?,
    private val animated: AnimatedImageDrawable?,
    private val gifBitmaps: List<android.graphics.Bitmap>?,
    private val gifDelays: IntArray?,
    private val lazyFrames: LazyFrames?,
    private val videoBytes: ByteArray?,
    private val opt: BgOpt,
    private val radii: FloatArray,
    private val bgLog: (String) -> Unit = {},
) : Drawable(), Drawable.Callback {

    private val path = Path()
    private val srcRect = Rect()
    private val dstRect = RectF()

    // ---- 视频直放（原视频字节在时才建播放器；帧经主线程回调刷新） ----
    @Volatile private var videoFrame: android.graphics.Bitmap? = null
    @Volatile private var videoErrors = 0
    private val videoPlayer: VideoBgPlayer? = if (videoBytes != null) {
        VideoBgPlayer(
            bytes = videoBytes,
            opt = opt,
            onFrame = { bmp ->
                videoFrame = bmp
                gifHandler.post { invalidateSelf() }
            },
            onError = { msg ->
                videoErrors++
                bgLog("error: $msg")
            },
            onEvent = { msg -> bgLog(msg) },
        )
    } else {
        null
    }

    // ---- 自有播放时钟（GIF 帧 / 视频懒帧共用） ----
    private val frameDelays: IntArray? = gifDelays ?: lazyFrames?.delays
    private val frameCount: Int = gifBitmaps?.size ?: lazyFrames?.size ?: 0
    private val gifTimeline: GifTimeline? =
        if (frameCount >= 2 && frameDelays != null) {
            GifTimeline(frameDelays, opt.mode)
        } else {
            null
        }
    private val gifHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var gifPlaying = false
    private var gifWantPlay = false
    private var gifDone = false
    private var gifStartStamp = 0L
    private var gifElapsedBase = 0L
    private var gifFrame = 0
    private var lastLazyBmp: android.graphics.Bitmap? = null
    private val gifTick = object : Runnable {
        override fun run() {
            val tl = gifTimeline ?: return
            if (!gifPlaying) return
            val eff = GifTimeline.effective(
                gifElapsedBase + (android.os.SystemClock.uptimeMillis() - gifStartStamp),
                opt.speedPct,
            )
            val idx = tl.frameAt(eff)
            if (idx != gifFrame) {
                gifFrame = idx
                try { val lf = lazyFrames; lf?.prefetchFrames(tl.upcoming(eff, lf.windowSize() + 1)) } catch (_: Throwable) {}
                invalidateSelf()
            }
            if (tl.isFinished(eff)) {
                gifElapsedBase += android.os.SystemClock.uptimeMillis() - gifStartStamp
                gifPlaying = false
                gifDone = true
                return
            }
            val nextEff = tl.nextChangeAt(eff)
            if (nextEff == Long.MAX_VALUE) {
                gifElapsedBase += android.os.SystemClock.uptimeMillis() - gifStartStamp
                gifPlaying = false
                gifDone = true
                return
            }
            val delayReal = ((nextEff - eff) * 100 / opt.speedPct).coerceIn(2L, 60_000L) + 1
            gifHandler.postDelayed(this, delayReal)
        }
    }

    init {
        animated?.callback = this
    }

    fun startIfAnimated() {
        try { animated?.start() } catch (_: Throwable) {}
        try { videoPlayer?.start() } catch (_: Throwable) {}
        try {
            val tl = gifTimeline
            if (tl != null && lazyFrames != null) {
                val eff = GifTimeline.effective(gifElapsedBase, opt.speedPct)
                lazyFrames.prefetchFrames(tl.upcoming(eff, lazyFrames.windowSize() + 1))
            } else {
                lazyFrames?.prefetch(gifFrame)
            }
        } catch (_: Throwable) {}
        gifWantPlay = true
        startGif()
    }

    /** 背景被换掉时停掉一切播放（视频解码线程、GIF 时钟、系统动图）。 */
    fun release() {
        gifWantPlay = false
        pauseGif()
        try { videoPlayer?.release() } catch (_: Throwable) {}
        try { animated?.stop() } catch (_: Throwable) {}
    }

    private fun startGif() {
        // gifDone 守卫：播一次模式播完后，任何迟到的 start 都不许从头重播（「播放两遍」的病根）
        if (gifTimeline == null || gifPlaying || !gifWantPlay || gifDone) return
        gifPlaying = true
        gifStartStamp = android.os.SystemClock.uptimeMillis()
        gifHandler.removeCallbacks(gifTick)
        gifHandler.post(gifTick)
    }

    private fun pauseGif() {
        if (!gifPlaying) return
        gifElapsedBase += android.os.SystemClock.uptimeMillis() - gifStartStamp
        gifPlaying = false
        gifHandler.removeCallbacks(gifTick)
    }

    // AnimatedImageDrawable 靠可见性/状态回调驱动帧推进；作 background 时这些
    // 回调先到本包装层，必须转发给内层，否则动图会停在第一帧（v1.23 的病根）。
    // GIF 时钟同样跟可见性走：不可见就暂停，省电且不空转。
    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        try { animated?.setVisible(visible, restart) } catch (_: Throwable) {}
        try {
            if (visible) videoPlayer?.start() else videoPlayer?.pause()
        } catch (_: Throwable) {}
        if (visible) startGif() else pauseGif()
        return super.setVisible(visible, restart)
    }

    override fun onStateChange(state: IntArray): Boolean {
        try { animated?.state = state } catch (_: Throwable) {}
        return super.onStateChange(state)
    }

    override fun onLevelChange(level: Int): Boolean {
        try { animated?.level = level } catch (_: Throwable) {}
        return super.onLevelChange(level)
    }

    override fun jumpToCurrentState() {
        try { animated?.jumpToCurrentState() } catch (_: Throwable) {}
        super.jumpToCurrentState()
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        path.reset()
        if (bounds.width() > 0 && bounds.height() > 0) {
            path.addRoundRect(
                bounds.left.toFloat(), bounds.top.toFloat(),
                bounds.right.toFloat(), bounds.bottom.toFloat(),
                radii, Path.Direction.CW,
            )
        }
    }

    /**
     * 显示区域：先算「刚好填满卡片」的缩放，再按 scalePct 放大，
     * 可见窗口按 focus（千分比）在图上滑动。返回源图上的可见矩形。
     */
    private fun cropRect(sw: Int, sh: Int, dw: Int, dh: Int): Rect {
        val s0 = maxOf(dw.toFloat() / sw, dh.toFloat() / sh)
        val s = s0 * opt.scalePct / 100f
        val vw = (dw / s).coerceIn(1f, sw.toFloat())
        val vh = (dh / s).coerceIn(1f, sh.toFloat())
        val maxOx = (sw - vw).coerceAtLeast(0f)
        val maxOy = (sh - vh).coerceAtLeast(0f)
        val ox = (opt.focusX / 1000f * maxOx).toInt()
        val oy = (opt.focusY / 1000f * maxOy).toInt()
        return Rect(ox, oy, ox + vw.toInt().coerceAtLeast(1), oy + vh.toInt().coerceAtLeast(1))
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        // 启动自愈：贴图/可见性回调时序不对、动画没被拉起来时，draw 发现就补启动
        if (isVisible) {
            if (gifTimeline != null && !gifPlaying && !gifDone) {
                gifWantPlay = true
                startGif()
            }
            if (videoPlayer != null && videoFrame == null && videoErrors < 2) {
                try { videoPlayer.start() } catch (_: Throwable) {}
            }
        }
        canvas.save()
        if (!path.isEmpty) canvas.clipPath(path)
        val anim = animated
        val frames = gifBitmaps
        when {
            anim != null -> {
                val iw = anim.intrinsicWidth
                val ih = anim.intrinsicHeight
                if (iw > 0 && ih > 0) {
                    val s0 = maxOf(b.width().toFloat() / iw, b.height().toFloat() / ih)
                    val s = s0 * opt.scalePct / 100f
                    val vw = (b.width() / s).coerceIn(1f, iw.toFloat())
                    val vh = (b.height() / s).coerceIn(1f, ih.toFloat())
                    val ox = opt.focusX / 1000f * (iw - vw).coerceAtLeast(0f)
                    val oy = opt.focusY / 1000f * (ih - vh).coerceAtLeast(0f)
                    anim.bounds = Rect(
                        (b.left - ox * s).toInt(), (b.top - oy * s).toInt(),
                        (b.left + (iw - ox) * s).toInt(), (b.top + (ih - oy) * s).toInt(),
                    )
                } else {
                    anim.bounds = b
                }
                anim.draw(canvas)
            }
            videoPlayer != null -> {
                // 还没解出帧（或解码失败）时先画海报静态图，背景不空
                val bmp = videoFrame ?: bitmap
                if (bmp != null) {
                    val src = cropRect(bmp.width, bmp.height, b.width(), b.height())
                    dstRect.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
                    canvas.drawBitmap(bmp, src, dstRect, null)
                }
            }
            frames != null && frames.isNotEmpty() -> {
                val bmp = frames[gifFrame.coerceIn(0, frames.size - 1)]
                val src = cropRect(bmp.width, bmp.height, b.width(), b.height())
                dstRect.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
                canvas.drawBitmap(bmp, src, dstRect, null)
            }
            lazyFrames != null -> {
                val idx = gifFrame.coerceIn(0, lazyFrames.size - 1)
                val cur = lazyFrames.frameCached(idx)
                if (cur != null) lastLazyBmp = cur
                val bmp = cur ?: lastLazyBmp
                if (bmp != null) {
                    val src = cropRect(bmp.width, bmp.height, b.width(), b.height())
                    dstRect.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
                    canvas.drawBitmap(bmp, src, dstRect, null)
                }
            }
            bitmap != null -> {
                val src = cropRect(bitmap.width, bitmap.height, b.width(), b.height())
                dstRect.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
                canvas.drawBitmap(bitmap, src, dstRect, null)
            }
        }
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {
        animated?.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        animated?.colorFilter = colorFilter
    }

    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getOutline(outline: Outline) {
        val maxR = radii.maxOrNull() ?: 0f
        if (maxR > 0f) outline.setRoundRect(bounds, maxR) else super.getOutline(outline)
    }

    override fun invalidateDrawable(who: Drawable) {
        invalidateSelf()
    }

    override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
        scheduleSelf(what, `when`)
    }

    override fun unscheduleDrawable(who: Drawable, what: Runnable) {
        unscheduleSelf(what)
    }
}
