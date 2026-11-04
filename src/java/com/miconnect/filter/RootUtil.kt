package com.miconnect.filter

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
        )
    }

    /** 当前配置的四元组（与 buildConf 同源）：enabled / 白名单 CSV / bypass 截止 / debug。 */
    fun currentValues(ctx: Context): FilterConfig {
        val p = prefs(ctx)
        return FilterConfig(
            enabled = p.getBoolean("enabled", true),
            whitelist = readWhitelist(p).map { it.first }.toSet(),
            bypassUntilMs = effectiveBypassUntil(p),
            debug = p.getBoolean("debug", false),
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
            for (pkg in Targets.PACKAGES) {
                val intent = Intent(ConfigBus.ACTION).apply {
                    setPackage(pkg)
                    putExtra("token", ConfigBus.TOKEN)
                    putExtra("enabled", v.enabled)
                    putExtra("whitelist", wlCsv)
                    putExtra("bypass_until", v.bypassUntilMs)
                    putExtra("debug", v.debug)
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
