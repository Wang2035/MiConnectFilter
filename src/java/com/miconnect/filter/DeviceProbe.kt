package com.miconnect.filter

import android.app.Notification
import android.bluetooth.le.ScanResult
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.os.Bundle
import java.util.IdentityHashMap

/**
 * 从 Intent / Notification / 方法参数里提取设备画像（运行在 connectionshare 进程内）。
 *
 * 提取策略（深度受限、防循环）：
 *  - BluetoothDevice 对象：直接取 address / name / bondState（最可靠）
 *  - String：用正则扫 MAC，同时收集文本用于品牌/场景判定
 *  - Bundle / Intent extras：递归展开
 *  - 其它 Parcelable：只收其 toString 文本，不反射私有字段，避免破坏系统进程稳定性
 *
 * 配对判定：拿到 MAC 后用 BluetoothAdapter 的配对表复核——
 * 已配对设备无论弹窗形态一律放行，这是保住开盖动画/降噪卡片/跨设备流转的根。
 */
object DeviceProbe {

    private const val MAX_DEPTH = 4

    private val POPUP_MARKERS = listOf(
        "fastconnect", "fast_connect", "fastpair", "fast_pair", "quickconnect",
        "popup", "popwindow", "discovery", "discover", "nearby", "earphone",
        "headset", "buds", "妙享", "快连", "发现弹窗",
    )
    private val DISCOVERY_MARKERS = listOf(
        "fastconnect", "fast_connect", "fastpair", "discovery", "discover", "scanresult",
    )

    class Acc {
        val macs = LinkedHashSet<String>()
        val texts = StringBuilder()
        var deviceName: String? = null
        var bondState: Int? = null
        var sawXiaomiVendor: Boolean = false

        fun addText(s: String?) {
            if (s.isNullOrEmpty()) return
            if (texts.length < 8192) texts.append(s).append('\n')
            for (m in MacUtil.extractMacs(s)) macs.add(m)
            // 小米 BLE 厂商 ID 0x038F，广播/附加信息里常以 8F03 / 038F 出现
            if (s.contains("038F", true) || s.contains("8F03", true)) sawXiaomiVendor = true
        }
    }

    private fun walk(acc: Acc, value: Any?, depth: Int, seen: IdentityHashMap<Any, Boolean>) {
        if (value == null || depth > MAX_DEPTH) return
        try {
            when (value) {
                is BluetoothDevice -> {
                    try { MacUtil.normalize(value.address)?.let { acc.macs.add(it) } } catch (_: Throwable) {}
                    try { value.name?.let { if (acc.deviceName == null) acc.deviceName = it; acc.addText(it) } } catch (_: Throwable) {}
                    try { acc.bondState = value.bondState } catch (_: Throwable) {}
                }
                is CharSequence -> acc.addText(value.toString())
                is Intent -> {
                    if (seen.put(value, true) != null) return
                    acc.addText(value.action)
                    acc.addText(value.component?.className)
                    acc.addText(value.dataString)
                    walk(acc, value.extras, depth + 1, seen)
                }
                is Bundle -> {
                    if (seen.put(value, true) != null) return
                    for (key in value.keySet()) {
                        acc.addText(key)
                        @Suppress("DEPRECATION")
                        walk(acc, value.get(key), depth + 1, seen)
                    }
                }
                is Notification -> {
                    if (seen.put(value, true) != null) return
                    try { acc.addText(value.channelId) } catch (_: Throwable) {}
                    try { acc.addText(value.tickerText?.toString()) } catch (_: Throwable) {}
                    walk(acc, value.extras, depth + 1, seen)
                }
                is Array<*> -> for (e in value) walk(acc, e, depth + 1, seen)
                is Iterable<*> -> for (e in value) walk(acc, e, depth + 1, seen)
                is ByteArray -> {
                    // 厂商数据里找小米 0x038F（小端 8F 03）
                    for (i in 0 until value.size - 1) {
                        if (value[i] == 0x8F.toByte() && value[i + 1] == 0x03.toByte()) {
                            acc.sawXiaomiVendor = true; break
                        }
                    }
                }
                is Number, is Boolean -> Unit
                else -> acc.addText(value.toString())
            }
        } catch (_: Throwable) {}
    }

    /** 系统配对表里的全部设备名（小写），供只有名字、没有 MAC 的场景（悬浮窗文字）复核。 */
    fun bondedNames(): Set<String>? {
        return try {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return null
            val bonded = adapter.bondedDevices ?: return null
            bonded.mapNotNull { dev ->
                try {
                    val alias = if (android.os.Build.VERSION.SDK_INT >= 30) dev.alias else null
                    (alias ?: dev.name)?.lowercase()
                } catch (_: Throwable) { null }
            }.toSet()
        } catch (_: Throwable) {
            null
        }
    }

    /** 按名字查配对表：true=同名设备已配对；false=配对表可查且无此名；null=查不了。 */
    fun resolveBondedByName(name: String?): Boolean? {
        if (name.isNullOrBlank()) return null
        val names = bondedNames() ?: return null
        return names.contains(name.trim().lowercase())
    }

    /**
     * 悬浮窗/对话框文字 -> 画像。窗口里通常只有设备名（偶尔有 MAC），
     * 配对复核走 MAC 优先、名字兜底；bonded 能确认 false 时视为已核实未配对，
     * 调用方据此把 strongDiscovery 置 true（已展示的弹窗 + 已核实未配对，足够拦截）。
     */
    fun fromTexts(texts: List<String>): Probe {
        val acc = Acc()
        for (t in texts) acc.addText(t)
        val mac = acc.macs.firstOrNull()
        // 设备名：文字里第一条能被识别为小米耳机的，或最长的一条候选
        val name = texts.firstOrNull { FilterCore.isXiaomiEarbudName(it) }
            ?: acc.deviceName
            ?: texts.maxByOrNull { it.length }
        val bonded: Boolean? = resolveBonded(mac) ?: resolveBondedByName(name)
        val xiaomi = acc.sawXiaomiVendor || FilterCore.isXiaomiEarbudName(name) ||
            texts.any { FilterCore.isXiaomiEarbudName(it) }
        return Probe(
            mac = mac,
            bonded = bonded,
            deviceName = name,
            xiaomiEarbud = xiaomi,
            popupContext = true,
            strongDiscovery = bonded == false,
        )
    }

    /** 查系统配对表：true=已配对；false=适配器可用且不在配对表、也不是 BOND_BONDED；null=查不了。 */
    fun resolveBonded(mac: String?): Boolean? {
        if (mac == null) return null
        return try {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return null
            val remote = try { adapter.getRemoteDevice(mac) } catch (_: Throwable) { null }
            if (remote != null && remote.bondState == BluetoothDevice.BOND_BONDED) return true
            val bonded = adapter.bondedDevices ?: return null
            if (bonded.any { MacUtil.normalize(it.address) == mac }) true else false
        } catch (_: Throwable) {
            null
        }
    }

    /** BLE 扫描结果 -> 画像：小米耳机的广播里带厂商 ID 0x038F，是最可靠的识别点。 */
    fun fromScanResult(result: ScanResult?): Probe {
        if (result == null) return Probe()
        val acc = Acc()
        walk(acc, result.device, 0, IdentityHashMap())
        try {
            val record = result.scanRecord
            if (record != null) {
                record.deviceName?.let {
                    if (acc.deviceName == null) acc.deviceName = it
                    acc.addText(it)
                }
                val msd = record.manufacturerSpecificData
                if (msd != null) {
                    for (i in 0 until msd.size()) {
                        if (msd.keyAt(i) == 0x038F) acc.sawXiaomiVendor = true
                    }
                }
            }
        } catch (_: Throwable) {}
        val probe = buildProbe(acc, sitePopup = true)
        return probe.copy(strongDiscovery = probe.bonded == false || acc.bondState == BluetoothDevice.BOND_NONE)
    }

    fun fromValues(sitePopup: Boolean, values: List<Any?>): Probe {
        val acc = Acc()
        val seen = IdentityHashMap<Any, Boolean>()
        for (v in values) walk(acc, v, 0, seen)
        return buildProbe(acc, sitePopup)
    }

    fun fromIntent(intent: Intent?, sitePopup: Boolean = false): Probe {
        val acc = Acc()
        walk(acc, intent, 0, IdentityHashMap())
        return buildProbe(acc, sitePopup)
    }

    fun fromNotification(notification: Notification?, tag: String?): Probe {
        val acc = Acc()
        acc.addText(tag)
        walk(acc, notification, 0, IdentityHashMap())
        val text = acc.texts.toString().lowercase()
        val sitePopup = POPUP_MARKERS.any { text.contains(it) }
        return buildProbe(acc, sitePopup)
    }

    private fun buildProbe(acc: Acc, sitePopup: Boolean): Probe {
        val text = acc.texts.toString()
        val lower = text.lowercase()
        val mac = acc.macs.firstOrNull()
        val bonded: Boolean? = when {
            acc.bondState == BluetoothDevice.BOND_BONDED -> true
            acc.bondState == BluetoothDevice.BOND_NONE || acc.bondState == BluetoothDevice.BOND_BONDING ->
                resolveBonded(mac) ?: false
            else -> resolveBonded(mac)
        }
        val popup = sitePopup || POPUP_MARKERS.any { lower.contains(it) }
        var strong = DISCOVERY_MARKERS.any { lower.contains(it) } &&
            (acc.bondState == BluetoothDevice.BOND_NONE)
        // 纯文字的发现提示（通知/弹窗里只有“发现 Redmi Buds，点击连接”这类文案、没有 MAC）：
        //带发现语义且没有“已连接”时，也算强发现信号；已连接类文案不拦。
        if (!strong && popup) {
            val discoveryText = lower.contains("发现") || lower.contains("点击连接") ||
                lower.contains("快速连接") || lower.contains("tap to connect") ||
                lower.contains("click to connect")
            val connectedText = lower.contains("已连接") || lower.contains("已配对")
            if (discoveryText && !connectedText) strong = true
        }
        val xiaomi = acc.sawXiaomiVendor || FilterCore.isXiaomiEarbudName(acc.deviceName) ||
            FilterCore.isXiaomiEarbudName(text)
        return Probe(
            mac = mac,
            bonded = bonded,
            deviceName = acc.deviceName,
            xiaomiEarbud = xiaomi,
            popupContext = popup,
            strongDiscovery = strong,
        )
    }
}
