package io.github.wang2035.miconnectfilter

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import java.io.File

/** UI 侧 root 操作（KernelSU 的 su 同样适用）：状态检测与配置同步。 */
object RootUtil {

    fun execSu(cmd: String): Pair<Int, String> {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val out = p.inputStream.bufferedReader().readText()
            val err = p.errorStream.bufferedReader().readText()
            val code = p.waitFor()
            code to (out + err).trim()
        } catch (t: Throwable) {
            -1 to ""
        }
    }

    fun execPlain(vararg cmd: String): Pair<Int, String> {
        return try {
            val p = Runtime.getRuntime().exec(cmd)
            val out = p.inputStream.bufferedReader().readText()
            val code = p.waitFor()
            code to out.trim()
        } catch (t: Throwable) {
            -1 to ""
        }
    }

    fun getProp(key: String): String {
        val (c, o) = execPlain("getprop", key)
        return if (c == 0) o else ""
    }

    fun checkRoot(): Boolean {
        val (code, out) = execSu("id")
        return code == 0 && out.contains("uid=0")
    }

    /** KernelSU / Magisk 任一存在即可（配置同步只依赖 su，不依赖具体方案）。 */
    fun checkRootManager(): String {
        val (_, out) = execSu(
            "ls -d /data/adb/ksu /data/adb/magisk /data/adb/ap 2>/dev/null; " +
                "command -v ksud magisk 2>/dev/null"
        )
        return when {
            out.contains("ksu") || out.contains("ksud") -> "KernelSU"
            out.contains("magisk") -> "Magisk"
            out.contains("/ap") -> "APatch"
            else -> "su 可用（方案未识别）"
        }
    }

    fun checkLspd(): Boolean {
        val (code, _) = execSu("ls -d /data/adb/lspd /data/adb/modules/*lsp* 2>/dev/null")
        return code == 0
    }

    /**
     * 用 root 重启全部作用域应用进程：逐包 pidof + kill。
     * 系统进程（蓝牙栈等）被 kill 后系统会自动拉起，普通应用下次被唤起时启动，
     * 两者都会在新进程里重新被 LSPosed 注入。返回每包结果：
     * killed / not-running / failed。
     */
    fun restartScopeApps(): List<Pair<String, String>> {
        val pkgs = Targets.PACKAGES.sorted()
        val script = StringBuilder()
        for (pkg in pkgs) {
            script.append("pid=\$(pidof $pkg 2>/dev/null); ")
            script.append("if [ -n \"\$pid\" ]; then kill \$pid 2>/dev/null && echo \"$pkg killed\" || echo \"$pkg failed\"; ")
            script.append("else echo \"$pkg not-running\"; fi; ")
        }
        val (_, out) = execSu(script.toString())
        val map = HashMap<String, String>()
        for (line in out.lines()) {
            val parts = line.trim().split(' ')
            if (parts.size == 2) map[parts[0]] = parts[1]
        }
        return pkgs.map { it to (map[it] ?: "failed") }
    }

    fun readLogTail(lines: Int = 60): String {
        val (_, out) = execSu("tail -n $lines ${ConfSync.LOG_PATH} 2>/dev/null")
        return out
    }

    fun clearLog(): Boolean {
        val (code, _) = execSu("rm -f ${ConfSync.LOG_PATH}")
        return code == 0
    }
}

/** UI 偏好（SharedPreferences）与 hook 配置文件之间的同步。 */
object ConfSync {
    const val PREFS = "mcf_ui"
    const val CONF_PATH = "/data/local/tmp/miconnectfilter.conf"
    const val LOG_PATH = "/data/local/tmp/miconnectfilter.log"

    fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 白名单条目存为 "MAC|备注名" 集合。 */
    fun readWhitelist(p: SharedPreferences): List<Pair<String, String>> {
        val set = p.getStringSet("whitelist", emptySet()) ?: emptySet()
        val out = ArrayList<Pair<String, String>>()
        for (e in set) {
            val i = e.indexOf('|')
            val mac = MacUtil.normalize(if (i >= 0) e.substring(0, i) else e) ?: continue
            val label = if (i >= 0) e.substring(i + 1) else ""
            out.add(mac to label)
        }
        return out.sortedBy { it.first }
    }

    fun writeWhitelist(p: SharedPreferences, list: List<Pair<String, String>>) {
        val set = list.map { "${it.first}|${it.second}" }.toSet()
        p.edit().putStringSet("whitelist", set).apply()
    }

    fun addToWhitelist(ctx: Context, mac: String, label: String): Boolean {
        val norm = MacUtil.normalize(mac) ?: return false
        val p = prefs(ctx)
        val list = readWhitelist(p).toMutableList()
        if (list.any { it.first == norm }) return false
        list.add(norm to label.trim())
        writeWhitelist(p, list)
        return true
    }

    /** 按 MAC 批量移除白名单条目，返回实际删除条数。 */
    fun removeFromWhitelist(ctx: Context, macs: Collection<String>): Int {
        val norm = macs.mapNotNull { MacUtil.normalize(it) }.toSet()
        if (norm.isEmpty()) return 0
        val p = prefs(ctx)
        val list = readWhitelist(p)
        val kept = list.filter { it.first !in norm }
        if (kept.size == list.size) return 0
        writeWhitelist(p, kept)
        return list.size - kept.size
    }

    /** 当前有效的临时放行截止时间；已过期自动归零。 */
    fun effectiveBypassUntil(p: SharedPreferences): Long {
        val until = p.getLong("bypass_until", 0L)
        if (until == 0L) return 0L
        if (until <= System.currentTimeMillis()) {
            p.edit().putLong("bypass_until", 0L).apply()
            return 0L
        }
        return until
    }

    fun buildConf(ctx: Context): String {
        val p = prefs(ctx)
        return FilterConfig.render(
            enabled = p.getBoolean("enabled", true),
            whitelist = readWhitelist(p).map { it.first }.toSet(),
            bypassUntilMs = effectiveBypassUntil(p),
            debug = p.getBoolean("debug", false),
            bgMap = BgStore.versionMap(ctx),
            bgOpts = BgStore.optsMap(ctx),
            fontColor = p.getInt("font_color", 0),
        )
    }

    /** 当前配置（与 buildConf 同源）。 */
    fun currentValues(ctx: Context): FilterConfig {
        val p = prefs(ctx)
        return FilterConfig(
            enabled = p.getBoolean("enabled", true),
            whitelist = readWhitelist(p).map { it.first }.toSet(),
            bypassUntilMs = effectiveBypassUntil(p),
            debug = p.getBoolean("debug", false),
            bgMap = BgStore.versionMap(ctx),
            bgOpts = BgStore.optsMap(ctx),
            fontColor = p.getInt("font_color", 0),
        )
    }

    /**
     * 广播通道：把当前配置发给每个目标包的 hook 进程（进程内内存接收，
     * 不依赖文件与 SELinux）。App 直发一遍，再用 root 的 am broadcast 发一遍
     * （特权发送方对系统进程更稳）。每次 sync 都发，暂停拦截点下去立刻到达。
     */
    fun sendConfigBroadcast(ctx: Context) {
        try {
            val v = currentValues(ctx)
            val wlCsv = v.whitelist.sorted().joinToString(",")
            val bgCsv = FilterConfig.renderBgMap(v.bgMap)
            val bgOptsCsv = BgOpt.renderMap(v.bgOpts)
            for (pkg in Targets.PACKAGES) {
                val intent = Intent(ConfigBus.ACTION).apply {
                    setPackage(pkg)
                    putExtra("token", ConfigBus.TOKEN)
                    putExtra("enabled", v.enabled)
                    putExtra("whitelist", wlCsv)
                    putExtra("bypass_until", v.bypassUntilMs)
                    putExtra("debug", v.debug)
                    putExtra("bg_map", bgCsv)
                    putExtra("bg_opts", bgOptsCsv)
                    putExtra("font_color", v.fontColor)
                }
                ctx.sendBroadcast(intent)
            }
            val am = StringBuilder()
            for (pkg in Targets.PACKAGES) {
                if (am.isNotEmpty()) am.append("; ")
                am.append("am broadcast -a ").append(ConfigBus.ACTION)
                    .append(" -p ").append(pkg)
                    .append(" --es token ").append(ConfigBus.TOKEN)
                    .append(" --ez enabled ").append(if (v.enabled) "true" else "false")
                    .append(" --es whitelist '").append(wlCsv).append("'")
                    .append(" --el bypass_until ").append(v.bypassUntilMs)
                    .append(" --ez debug ").append(if (v.debug) "true" else "false")
                    .append(" --es bg_map '").append(bgCsv).append("'")
                    .append(" --es bg_opts '").append(bgOptsCsv).append("'")
                    .append(" --ei font_color ").append(v.fontColor)
            }
            Thread { RootUtil.execSu(am.toString()) }.start()
        } catch (_: Throwable) {}
    }

    /** 双通道同步：文件（普通进程读）+ 广播（系统进程内存收）。 */
    fun sync(ctx: Context): Boolean {
        val fileOk = try {
            val tmp = File(ctx.cacheDir, "miconnectfilter.conf")
            tmp.writeText(buildConf(ctx))
            val (code, _) = RootUtil.execSu(
                "cp ${tmp.absolutePath} $CONF_PATH && chmod 666 $CONF_PATH",
            )
            code == 0
        } catch (_: Throwable) {
            false
        }
        sendConfigBroadcast(ctx)
        return fileOk
    }
}

/**
 * 蓝牙设备显示名：优先用户改名后的别名（BluetoothDevice.getAlias，API 30+），
 * 没有别名才用原始广播名。此前只读 name，改过名的设备在应用里一直显示旧名、
 * 白名单里存的备注也永远不更新。
 */
object DeviceNames {
    fun displayName(dev: android.bluetooth.BluetoothDevice): String? {
        val alias = try {
            if (android.os.Build.VERSION.SDK_INT >= 30) dev.alias else null
        } catch (_: Throwable) { null }
        val raw = try { dev.name } catch (_: Throwable) { null }
        return alias?.takeIf { it.isNotBlank() } ?: raw?.takeIf { it.isNotBlank() }
    }

    /** 当前已配对设备的 MAC -> 显示名；无蓝牙权限时返回空表（调用方静默跳过）。 */
    fun bondedNameMap(): Map<String, String> {
        return try {
            val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter() ?: return emptyMap()
            val out = HashMap<String, String>()
            for (dev in adapter.bondedDevices ?: emptySet()) {
                val name = displayName(dev) ?: continue
                val mac = MacUtil.normalize(dev.address) ?: continue
                out[mac] = name
            }
            out
        } catch (_: Throwable) {
            emptyMap()
        }
    }
}

/**
 * 弹窗背景图的本地存储（设置页写、BgProvider 读）：每台设备一张图，
 * 文件 bg_<key>.img（key = "all" 或 MAC 去分隔符大写），prefs 记 bg_mime_<key>、
 * bg_ver_<key>。hook 靠配置里的分配表（key->版本号）挑图并刷新缓存。
 */
object BgStore {
    const val ALL = "all"
    private const val LEGACY_FILE = "popup_bg.img"
    private val KEY_OK = Regex("^[A-Za-z0-9_]+$")

    fun validKey(key: String?): String? =
        key?.takeIf { it.matches(KEY_OK) && (it == ALL || MacUtil.macOfKey(it) != null) }

    fun fileFor(ctx: Context, key: String): File = File(ctx.filesDir, "bg_$key.img")

    fun exists(ctx: Context, key: String): Boolean = fileFor(ctx, key).exists()

    fun isGif(ctx: Context, key: String): Boolean =
        ConfSync.prefs(ctx).getString("bg_mime_$key", "") == "image/gif"

    /** 动态图（GIF / 动态 WebP / 视频）：播放控制走帧序列、系统动图或视频直放。 */
    fun isAnimated(ctx: Context, key: String): Boolean {
        val m = ConfSync.prefs(ctx).getString("bg_mime_$key", "")
        return m == "image/gif" || m == "image/webp" || m == "video/frames" || m == "video/mp4"
    }

    /** 视频背景：帧包（video/frames，旧版抽帧）或原视频直放（video/mp4）。 */
    fun isVideo(ctx: Context, key: String): Boolean {
        val m = ConfSync.prefs(ctx).getString("bg_mime_$key", "")
        return m == "video/frames" || m == "video/mp4"
    }

    /** 原视频直放（导入存的是 MP4 原文件，hook 边解边播）。 */
    fun isNativeVideo(ctx: Context, key: String): Boolean =
        ConfSync.prefs(ctx).getString("bg_mime_$key", "") == "video/mp4"

    /** 直放视频的海报帧文件（列表缩略图/设置预览用，导入时存好，避免每次现抓）。 */
    fun posterFile(ctx: Context, key: String): File = File(ctx.filesDir, "bg_${key}_poster.jpg")

    fun savePoster(ctx: Context, key: String, bmp: android.graphics.Bitmap) {
        try {
            posterFile(ctx, key).outputStream().use {
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, it)
            }
        } catch (_: Throwable) {
        }
    }

    fun posterBitmap(ctx: Context, key: String): android.graphics.Bitmap? = try {
        val f = posterFile(ctx, key)
        if (f.exists()) android.graphics.BitmapFactory.decodeFile(f.absolutePath) else null
    } catch (_: Throwable) {
        null
    }

    fun mimeFor(ctx: Context, key: String): String =
        ConfSync.prefs(ctx).getString("bg_mime_$key", null) ?: "image/*"

    /** 已有图片的 key 列表（"all" 在前，其余按 key 排序）。 */
    fun keys(ctx: Context): List<String> {
        val p = ConfSync.prefs(ctx)
        val out = ArrayList<String>()
        for (k in p.all.keys) {
            if (!k.startsWith("bg_ver_")) continue
            val key = k.removePrefix("bg_ver_")
            if (validKey(key) != null && exists(ctx, key)) out.add(key)
        }
        return out.sortedWith(compareBy({ it != ALL }, { it }))
    }

    /** 配置下发用的分配表：只含图片文件还在的 key（删图即从表里消失，hook 不再尝试拉取）。 */
    fun versionMap(ctx: Context): Map<String, Long> {
        val p = ConfSync.prefs(ctx)
        val out = LinkedHashMap<String, Long>()
        for (k in p.all.keys) {
            if (!k.startsWith("bg_ver_")) continue
            val key = k.removePrefix("bg_ver_")
            if (validKey(key) == null) continue
            if (!exists(ctx, key)) continue
            val v = p.getLong(k, 0L)
            if (v > 0) out[key] = v
        }
        return out
    }

    /** 保存图片字节并递增该 key 的版本号。返回新版本号。 */
    fun save(ctx: Context, key: String, bytes: ByteArray, mime: String): Long {
        fileFor(ctx, key).writeBytes(bytes)
        try { posterFile(ctx, key).delete() } catch (_: Throwable) {}
        val p = ConfSync.prefs(ctx)
        val v = p.getLong("bg_ver_$key", 0L) + 1
        p.edit().putString("bg_mime_$key", mime).putLong("bg_ver_$key", v)
            .remove("bg_frames_$key").apply()
        return v
    }

    /** 清除某 key 的背景：删文件、递增版本号（hook 收到新版本后拉不到图，自然不显示）。 */
    fun clear(ctx: Context, key: String): Long {
        try { fileFor(ctx, key).delete() } catch (_: Throwable) {}
        try { posterFile(ctx, key).delete() } catch (_: Throwable) {}
        val p = ConfSync.prefs(ctx)
        val v = p.getLong("bg_ver_$key", 0L) + 1
        p.edit().remove("bg_mime_$key").remove("bg_opt_$key").remove("bg_frames_$key")
            .putLong("bg_ver_$key", v).apply()
        return v
    }

    /** 某 key 的播放/显示选项。 */
    fun optFor(ctx: Context, key: String): BgOpt =
        BgOpt.parse(ConfSync.prefs(ctx).getString("bg_opt_$key", null))

    fun setOpt(ctx: Context, key: String, opt: BgOpt) {
        ConfSync.prefs(ctx).edit().putString("bg_opt_$key", opt.render()).apply()
    }

    /** 配置下发用的选项表：只含图片还在的 key、且与默认不同的。 */
    fun optsMap(ctx: Context): Map<String, BgOpt> {
        val out = LinkedHashMap<String, BgOpt>()
        for (key in keys(ctx)) {
            val opt = optFor(ctx, key)
            if (opt != BgOpt.DEFAULT) out[key] = opt
        }
        return out
    }

    /** GIF 帧信息（帧数 to 总时长 ms），导入时解码一次缓存下来，列表显示用；非 GIF 返回 null。 */
    fun frameInfo(ctx: Context, key: String): Pair<Int, Long>? {
        val s = ConfSync.prefs(ctx).getString("bg_frames_$key", null) ?: return null
        val p = s.split(':')
        if (p.size < 2) return null
        val n = p[0].toIntOrNull() ?: return null
        val ms = p[1].toLongOrNull() ?: return null
        return n to ms
    }

    fun setFrameInfo(ctx: Context, key: String, frames: Int, totalMs: Long) {
        ConfSync.prefs(ctx).edit().putString("bg_frames_$key", "$frames:$totalMs").apply()
    }

    /** 这张图在界面上的称呼：全部设备 / 白名单里的设备名（或 MAC）。 */
    fun labelFor(ctx: Context, key: String): String {
        if (key == ALL) return "全部设备"
        val mac = MacUtil.macOfKey(key) ?: return key
        val hit = ConfSync.readWhitelist(ConfSync.prefs(ctx)).firstOrNull { it.first == mac }
        val label = hit?.second?.takeIf { it.isNotBlank() && it != mac }
        return label ?: mac
    }

    /** v1.21 的单图（popup_bg.img + bg_version）迁移成「全部设备」图，只做一次。 */
    fun migrateIfNeeded(ctx: Context) {
        try {
            val p = ConfSync.prefs(ctx)
            val legacy = File(ctx.filesDir, LEGACY_FILE)
            if (!legacy.exists()) return
            val allFile = fileFor(ctx, ALL)
            if (!allFile.exists()) {
                legacy.copyTo(allFile, overwrite = true)
                val v = p.getLong("bg_version", 0L)
                p.edit()
                    .putString("bg_mime_$ALL", p.getString("bg_mime", "image/jpeg") ?: "image/jpeg")
                    .putLong("bg_ver_$ALL", if (v > 0) v else 1L)
                    .apply()
            }
            legacy.delete()
            p.edit().remove("bg_version").remove("bg_mime").apply()
        } catch (_: Throwable) {}
    }
}
