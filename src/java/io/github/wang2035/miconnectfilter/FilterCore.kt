package io.github.wang2035.miconnectfilter

import java.io.File

/**
 * 妙享快连过滤 - 纯逻辑核心（无 Android 依赖，可 JVM 单测）。
 *
 * 判定原则（按顺序，命中即返回）：
 *  1) 模块未启用 -> PASS（不干预）
 *  2) 临时放行窗口内（添加新设备用，最长 5 分钟）-> ALLOW
 *  3) 设备已在系统蓝牙配对表里（bonded）-> ALLOW
 *     —— 已配对耳机的开盖动画 / 降噪卡片 / 跨设备流转都走这条，永不拦截。
 *  4) MAC 在白名单 -> ALLOW
 *  5) 是「发现/快连弹窗」场景，且能识别为小米系耳机，且未配对、未进白名单 -> BLOCK
 *     MAC 拿不到时，只有强发现信号（扫描回调/发现广播带着 BOND_NONE 设备）才 BLOCK，
 *     拿不准一律 PASS，避免误伤已配对设备的弹窗。
 *  6) 其它 -> PASS
 *
 * 本核心只做判定，不碰 MiLink、不碰通知权限、不做全局拦截；
 * 是否拦截由调用方（hook 层）按返回的 BLOCK 执行，且只作用于
 * com.miui.connectionshare 进程内带设备信息的弹窗入口。
 */
enum class Decision { ALLOW, BLOCK, PASS }

/**
 * 弹窗背景播放/显示选项（每个背景 key 一份，随配置下发）：
 * mode：0=循环 1=播一次停在最后一帧 2=往返（正放完倒放）；speedPct：播放速度百分比；
 * focusX/focusY：显示区域中心（千分比）；scalePct：区域缩放（100=刚好填满卡片，越大越放大）。
 */
data class BgOpt(
    // 默认播一次停在最后一帧（用户 2026-11-16 定），要循环/往返在背景设置里改
    val mode: Int = 1,
    val speedPct: Int = 100,
    val focusX: Int = 500,
    val focusY: Int = 500,
    val scalePct: Int = 100,
) {
    /** "mode:speed:fx:fy:scale" */
    fun render(): String = "$mode:$speedPct:$focusX:$focusY:$scalePct"

    companion object {
        const val MODE_LOOP = 0
        const val MODE_ONCE = 1
        const val MODE_PINGPONG = 2
        val DEFAULT = BgOpt()

        fun parse(text: String?): BgOpt {
            if (text.isNullOrBlank()) return DEFAULT
            val p = text.split(':')
            if (p.size < 5) return DEFAULT
            return BgOpt(
                mode = (p[0].toIntOrNull() ?: 1).coerceIn(0, 2),
                speedPct = (p[1].toIntOrNull() ?: 100).coerceIn(25, 300),
                focusX = (p[2].toIntOrNull() ?: 500).coerceIn(0, 1000),
                focusY = (p[3].toIntOrNull() ?: 500).coerceIn(0, 1000),
                scalePct = (p[4].toIntOrNull() ?: 100).coerceIn(100, 300),
            )
        }

        /** "all:<opt>,AABBCC112233:<opt>" <-> 分配表。 */
        fun parseMap(text: String?): Map<String, BgOpt> {
            if (text.isNullOrBlank()) return emptyMap()
            val out = LinkedHashMap<String, BgOpt>()
            for (part in text.split(',')) {
                val t = part.trim()
                if (t.isEmpty()) continue
                val i = t.indexOf(':')
                if (i <= 0) continue
                val key = t.substring(0, i).trim().lowercase()
                    .takeIf { it == FilterConfig.BG_ALL_KEY || it.matches(Regex("^[0-9a-f]{12}$")) }
                    ?: continue
                out[if (key == FilterConfig.BG_ALL_KEY) key else key.uppercase()] =
                    parse(t.substring(i + 1))
            }
            return out
        }

        fun renderMap(map: Map<String, BgOpt>): String =
            map.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value.render()}" }
    }
}

data class FilterConfig(
    val enabled: Boolean = true,
    val whitelist: Set<String> = emptySet(),
    val bypassUntilMs: Long = 0L,
    val debug: Boolean = false,
    /**
     * 弹窗背景图分配表：key = "all"（全部设备）或设备 MAC 去分隔符大写（如 AABBCC112233），
     * value = 该图版本号（设置页每次更换/清除递增，hook 据此刷新缓存）。
     * 弹窗按设备 MAC 先查专属图，没有再用 "all"。
     */
    val bgMap: Map<String, Long> = emptyMap(),
    /** 每张背景图的播放/显示选项（模式/速度/区域），key 同 bgMap。 */
    val bgOpts: Map<String, BgOpt> = emptyMap(),
    /** 弹窗字体颜色：0=系统（跟随系统深色/浅色，不改） 1=白字 2=黑字。 */
    val fontColor: Int = 0,
) {
    fun isBypassActive(nowMs: Long): Boolean = bypassUntilMs > nowMs

    /** 某张背景图的选项（没设过用默认：循环、原速、居中填满）。 */
    fun bgOptFor(key: String): BgOpt = bgOpts[key] ?: BgOpt.DEFAULT

    /** 这台设备的弹窗该用哪张背景图：专属优先，其次全部设备；都没有返回 null。 */
    fun bgKeyFor(mac: String?): String? = bgKeysFor(mac).firstOrNull()

    /** 候选 key 顺序：专属在前、"all" 兜底。专属图被删/拉不到时 hook 按序回退。 */
    fun bgKeysFor(mac: String?): List<String> {
        val out = ArrayList<String>(2)
        val k = MacUtil.keyOf(mac)
        if (k != null && bgMap.containsKey(k)) out.add(k)
        if (bgMap.containsKey(BG_ALL_KEY)) out.add(BG_ALL_KEY)
        return out
    }

    companion object {
        const val BG_ALL_KEY = "all"
        const val FONT_SYSTEM = 0
        const val FONT_WHITE = 1
        const val FONT_BLACK = 2

        /** "all:3,AABBCC112233:5" <-> 分配表。 */
        fun parseBgMap(text: String?): Map<String, Long> {
            if (text.isNullOrBlank()) return emptyMap()
            val out = LinkedHashMap<String, Long>()
            for (part in text.split(',', ';', ' ')) {
                val p = part.trim()
                if (p.isEmpty()) continue
                val i = p.lastIndexOf(':')
                if (i <= 0) continue
                val key = p.substring(0, i).trim().lowercase()
                    .takeIf { it == BG_ALL_KEY || it.matches(Regex("^[0-9a-f]{12}$")) } ?: continue
                val v = p.substring(i + 1).toLongOrNull() ?: continue
                if (v > 0) out[if (key == BG_ALL_KEY) key else key.uppercase()] = v
            }
            return out
        }

        fun renderBgMap(map: Map<String, Long>): String =
            map.entries.filter { it.value > 0 }
                .sortedBy { it.key }
                .joinToString(",") { "${it.key}:${it.value}" }

        fun parse(text: String): FilterConfig {
            var enabled = true
            var wl: Set<String> = emptySet()
            var bypass = 0L
            var debug = false
            var bgMap: Map<String, Long> = emptyMap()
            var bgOpts: Map<String, BgOpt> = emptyMap()
            var fontColor = 0
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val eq = line.indexOf('=')
                if (eq <= 0) continue
                val key = line.substring(0, eq).trim()
                val value = line.substring(eq + 1).trim()
                when (key) {
                    "enabled" -> enabled = (value == "1" || value.equals("true", true))
                    "whitelist" -> wl = value.split(',', ';', ' ')
                        .mapNotNull { MacUtil.normalize(it) }.toSet()
                    "bypass_until" -> bypass = value.toLongOrNull() ?: 0L
                    "debug" -> debug = (value == "1" || value.equals("true", true))
                    "bg_map" -> bgMap = parseBgMap(value)
                    "bg_opts" -> bgOpts = BgOpt.parseMap(value)
                    "font_color" -> fontColor = (value.toIntOrNull() ?: 0).coerceIn(0, 2)
                    // v1.21 的单图写法，读到就当「全部设备」
                    "bg_version" -> {
                        val v = value.toLongOrNull() ?: 0L
                        if (v > 0 && bgMap.isEmpty()) bgMap = mapOf(BG_ALL_KEY to v)
                    }
                }
            }
            return FilterConfig(enabled, wl, bypass, debug, bgMap, bgOpts, fontColor)
        }

        /** 广播通道用的构造：入参来自 Intent extras，已在 UI 侧算好。 */
        fun of(enabled: Boolean, whitelistCsv: String, bypassUntilMs: Long, debug: Boolean, bgMapCsv: String = "", bgOptsCsv: String = "", fontColor: Int = 0): FilterConfig =
            FilterConfig(
                enabled = enabled,
                whitelist = whitelistCsv.split(',', ';', ' ')
                    .mapNotNull { MacUtil.normalize(it) }.toSet(),
                bypassUntilMs = bypassUntilMs.coerceAtLeast(0L),
                debug = debug,
                bgMap = parseBgMap(bgMapCsv),
                bgOpts = BgOpt.parseMap(bgOptsCsv),
                fontColor = fontColor.coerceIn(0, 2),
            )

        fun load(file: File): FilterConfig = try {
            if (file.exists()) parse(file.readText()) else FilterConfig()
        } catch (_: Throwable) {
            FilterConfig()
        }

        fun render(enabled: Boolean, whitelist: Set<String>, bypassUntilMs: Long, debug: Boolean, bgMap: Map<String, Long> = emptyMap(), bgOpts: Map<String, BgOpt> = emptyMap(), fontColor: Int = 0): String {
            val sb = StringBuilder()
            sb.append("# miconnectfilter conf v1\n")
            sb.append("enabled=").append(if (enabled) "1" else "0").append('\n')
            sb.append("whitelist=").append(
                whitelist.mapNotNull { MacUtil.normalize(it) }.sorted().joinToString(",")
            ).append('\n')
            sb.append("bypass_until=").append(bypassUntilMs.coerceAtLeast(0L)).append('\n')
            sb.append("debug=").append(if (debug) "1" else "0").append('\n')
            sb.append("bg_map=").append(renderBgMap(bgMap)).append('\n')
            sb.append("bg_opts=").append(BgOpt.renderMap(bgOpts)).append('\n')
            sb.append("font_color=").append(fontColor.coerceIn(0, 2)).append('\n')
            return sb.toString()
        }
    }
}

/**
 * 配置广播通道：系统进程（蓝牙域）被 SELinux 挡住读 /data/local/tmp 的配置文件时，
 * hook 改走进程内广播接收配置——设置页每次同步都把当前配置广播给全部目标包，
 * hook 收到即更新内存配置，优先级高于文件。开机由 BootResyncReceiver 再发一次。
 */
object ConfigBus {
    const val ACTION = "io.github.wang2035.miconnectfilter.action.CONFIG_SYNC"
    const val REQUEST_ACTION = "io.github.wang2035.miconnectfilter.action.CONFIG_REQUEST"
    const val TOKEN = "mcf-cfg-v1"
    const val MODULE_PACKAGE = "io.github.wang2035.miconnectfilter"

    /** 弹窗背景图 Provider：hook 进程经 content:// 拉取设置页选好的图片字节（按 key 取图）。 */
    const val BG_AUTHORITY = "io.github.wang2035.miconnectfilter.bg"
    fun bgUri(key: String): String = "content://$BG_AUTHORITY/bg/$key"
}

object MacUtil {
    private val HEX12 = Regex("^[0-9A-F]{12}$")
    private val MAC_IN_TEXT = Regex("(?i)([0-9A-F]{2}[:-]){5}[0-9A-F]{2}")

    /** 背景图分配表的 key：MAC 去分隔符大写（AABBCC112233）；非法返回 null。 */
    fun keyOf(raw: String?): String? = normalize(raw)?.replace(":", "")

    /** 分配表 key 还原成标准 MAC（"all" 返回 null）。 */
    fun macOfKey(key: String?): String? {
        if (key == null || key.length != 12) return null
        if (!key.all { it.isDigit() || it in 'A'..'F' || it in 'a'..'f' }) return null
        val u = key.uppercase()
        return (0 until 6).joinToString(":") { u.substring(it * 2, it * 2 + 2) }
    }

    /** 任意写法的 MAC -> 标准 AA:BB:CC:DD:EE:FF；非法返回 null。 */
    fun normalize(raw: String?): String? {
        if (raw == null) return null
        val cleaned = raw.trim().uppercase().replace(":", "").replace("-", "")
            .replace(".", "").replace(" ", "")
        if (!HEX12.matches(cleaned)) return null
        return cleaned.chunked(2).joinToString(":")
    }

    fun isValid(raw: String?): Boolean = normalize(raw) != null

    /** 从任意文本里提取全部 MAC（冒号/横线分隔写法）。 */
    fun extractMacs(text: String?): List<String> {
        if (text.isNullOrEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        for (m in MAC_IN_TEXT.findAll(text)) normalize(m.value)?.let { out.add(it) }
        return out.toList()
    }
}

/** 已配对列表的一条记录（显示名 + 已归一化 MAC）。 */
data class PairedEntry(val name: String?, val mac: String)

/**
 * 与系统蓝牙设置页对齐的合并：同一台物理设备可能有两条配对记录
 * （经典蓝牙与 BLE 各配一次，地址不同），设置页按设备只显示一行，
 * 这里按显示名合并成一组；点一次把组内全部 MAC 加进白名单，
 * 弹窗事件报哪个地址都能命中。名字为空的记录无法判断同机，不合并。
 */
object PairedGrouping {
    fun group(entries: List<PairedEntry>): List<List<PairedEntry>> {
        val map = LinkedHashMap<String, MutableList<PairedEntry>>()
        for (e in entries) {
            val key = e.name?.trim()?.takeIf { it.isNotEmpty() } ?: e.mac
            map.getOrPut(key) { mutableListOf() }.add(e)
        }
        return map.values.map { it.toList() }
    }

    enum class WlStatus { ALL, PARTIAL, NONE }

    /** 一组地址相对白名单的加入状态，供列表把「已加入/未加入」一眼区分。 */
    fun status(macs: List<String>, whitelist: Set<String>): WlStatus {
        if (macs.isEmpty()) return WlStatus.NONE
        val hit = macs.count { it in whitelist }
        return when {
            hit == 0 -> WlStatus.NONE
            hit == macs.size -> WlStatus.ALL
            else -> WlStatus.PARTIAL
        }
    }
}

/** hook 层从 Intent / Notification / 方法参数里提取出的设备画像。 */
data class Probe(
    val mac: String? = null,
    /** true=已确认在配对表；false=已确认未配对；null=没查到（调用方查询失败等） */
    val bonded: Boolean? = null,
    val deviceName: String? = null,
    /** 能识别为小米系耳机（品牌名/型号名/厂商数据 0x038F 等） */
    val xiaomiEarbud: Boolean = false,
    /** 当前入口是发现/快连弹窗类入口（弹窗 Activity、快连通知、发现回调等） */
    val popupContext: Boolean = false,
    /** 强发现信号：扫描/发现路径且设备明确未配对（BOND_NONE） */
    val strongDiscovery: Boolean = false,
)

object FilterCore {
    const val MAX_BYPASS_MINUTES = 5

    private val BRAND_WORDS = listOf(
        "xiaomi", "redmi", "poco", "小米", "红米", "flipbuds", "airdots",
        "mi true wireless", "mi earbuds", "mi buds",
    )
    private val EARBUD_WORDS = listOf(
        "buds", "airdots", "flipbuds", "earbuds", "earphone", "headset",
        "耳机", "air 3", "air 4", "air 5", "air 6",
    )

    fun isXiaomiEarbudName(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val n = name.lowercase()
        val brand = BRAND_WORDS.any { n.contains(it) } ||
            n.startsWith("mi ") || n == "mi" || n.contains("小米")
        if (!brand) return false
        // 品牌命中且带耳机类词，或品牌本身就是耳机产品线词（flipbuds/airdots）
        return EARBUD_WORDS.any { n.contains(it) } ||
            n.contains("flipbuds") || n.contains("airdots")
    }

    fun clampBypassMinutes(m: Int): Int = m.coerceIn(1, MAX_BYPASS_MINUTES)

    fun decide(cfg: FilterConfig, probe: Probe, nowMs: Long): Decision =
        decideWithReason(cfg, probe, nowMs).first

    /** 带原因的判定，原因字符串直接进排查日志（如 unbonded-xiaomi-popup）。 */
    fun decideWithReason(cfg: FilterConfig, probe: Probe, nowMs: Long): Pair<Decision, String> {
        if (!cfg.enabled) return Decision.PASS to "disabled"
        if (cfg.isBypassActive(nowMs)) return Decision.ALLOW to "bypass-active"
        val mac = MacUtil.normalize(probe.mac)
        if (probe.bonded == true) {
            val xiaomiish = probe.xiaomiEarbud || isXiaomiEarbudName(probe.deviceName)
            // 严格执行写死（用户 11:46 拍板，无开关）：弹窗场景 + 已配对 + 不在白名单 → 拦
            if (probe.popupContext && xiaomiish && mac != null &&
                !cfg.whitelist.contains(mac)
            ) {
                return Decision.BLOCK to "strict-bonded-not-whitelisted"
            }
            return Decision.ALLOW to "bonded"
        }
        if (mac != null && cfg.whitelist.contains(mac)) return Decision.ALLOW to "whitelisted"
        if (!probe.popupContext) return Decision.PASS to "not-popup"
        if (!probe.xiaomiEarbud && !isXiaomiEarbudName(probe.deviceName)) {
            return Decision.PASS to "not-xiaomi-earbud"
        }
        // 到这里：弹窗场景 + 小米系耳机 + 未确认配对 + 不在白名单
        if (mac != null) {
            // MAC 明确：调用方已先查过配对表（bonded==true 在上面已放行），
            // 这里 bonded 为 false/null 都按未配对处理——配对表查询在系统进程内是可靠的。
            return Decision.BLOCK to "unbonded-xiaomi-popup"
        }
        // MAC 不明确：只有强发现信号才拦，拿不准放行，保已配对流程。
        return if (probe.strongDiscovery) Decision.BLOCK to "strong-discovery-no-mac"
        else Decision.PASS to "unknown-pass"
    }
}

/**
 * 目标包（LSPosed 推荐作用域与 hook 生效范围一致）：
 *  - com.miui.connectionshare：用户指定，HyperOS 妙享/通信共享侧弹窗入口
 *  - com.milink.service：澎湃 OS 3 的「设备互联」主体（17.x），OS3 起
 *    HyperConnect 的设备发现/弹窗可能由它拉起；判定逻辑完全相同——
 *    只按耳机设备信息逐条判定，它的互传/妙享桌面等流程不带耳机信息、
 *    已配对设备永远放行，不受影响。这不是禁用 MiLink。
 */
object Targets {
    val PACKAGES: Set<String> = setOf(
        // 用户指定 + OS3 设备互联主体
        "com.miui.connectionshare",
        "com.milink.service",
        // 备选（v1.2 追加）：实测弹窗不在上面两个进程里时，最可能的宿主——
        // 蓝牙扩展与蓝牙栈才是耳机发现广播/快连弹窗的实际处理方
        // （同类模块 HyperEars 的必选作用域就是 com.android.bluetooth + com.milink.service，
        //   连接弹窗还需 com.xiaomi.bluetooth；MiuiBluetooth 的弹窗决策也在该包内）
        "com.miui.bluetoothext",
        "com.xiaomi.bluetooth",
        "com.android.bluetooth",
        "com.xiaomi.mi_connect_service",
    )

    /** 推荐作用域 = 全部支持包。v1.7 曾精简为 4 个，结果把真实弹窗宿主
     * （通信共享）挤出了推荐，用户重勾后模块根本没注入，日志全空、拦截失效；
     * v1.11 起恢复全量推荐，不再精简。 */
    val RECOMMENDED: Set<String> = PACKAGES

    /** 友好名，供设置页状态汇总。 */
    val LABELS: Map<String, String> = mapOf(
        "com.miui.connectionshare" to "通信共享/妙享",
        "com.milink.service" to "设备互联 MiLink",
        "com.miui.bluetoothext" to "蓝牙扩展",
        "com.xiaomi.bluetooth" to "MIUI 蓝牙扩展",
        "com.android.bluetooth" to "蓝牙栈",
        "com.xiaomi.mi_connect_service" to "小米互联服务",
    )
}
