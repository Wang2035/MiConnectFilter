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

data class FilterConfig(
    val enabled: Boolean = true,
    val whitelist: Set<String> = emptySet(),
    val bypassUntilMs: Long = 0L,
    val debug: Boolean = false,
) {
    fun isBypassActive(nowMs: Long): Boolean = bypassUntilMs > nowMs

    companion object {
        fun parse(text: String): FilterConfig {
            var enabled = true
            var wl: Set<String> = emptySet()
            var bypass = 0L
            var debug = false
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
                }
            }
            return FilterConfig(enabled, wl, bypass, debug)
        }

        /** 广播通道用的构造：入参来自 Intent extras，已在 UI 侧算好。 */
        fun of(enabled: Boolean, whitelistCsv: String, bypassUntilMs: Long, debug: Boolean): FilterConfig =
            FilterConfig(
                enabled = enabled,
                whitelist = whitelistCsv.split(',', ';', ' ')
                    .mapNotNull { MacUtil.normalize(it) }.toSet(),
                bypassUntilMs = bypassUntilMs.coerceAtLeast(0L),
                debug = debug,
            )

        fun load(file: File): FilterConfig = try {
            if (file.exists()) parse(file.readText()) else FilterConfig()
        } catch (_: Throwable) {
            FilterConfig()
        }

        fun render(enabled: Boolean, whitelist: Set<String>, bypassUntilMs: Long, debug: Boolean): String {
            val sb = StringBuilder()
            sb.append("# miconnectfilter conf v1\n")
            sb.append("enabled=").append(if (enabled) "1" else "0").append('\n')
            sb.append("whitelist=").append(
                whitelist.mapNotNull { MacUtil.normalize(it) }.sorted().joinToString(",")
            ).append('\n')
            sb.append("bypass_until=").append(bypassUntilMs.coerceAtLeast(0L)).append('\n')
            sb.append("debug=").append(if (debug) "1" else "0").append('\n')
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
}

object MacUtil {
    private val HEX12 = Regex("^[0-9A-F]{12}$")
    private val MAC_IN_TEXT = Regex("(?i)([0-9A-F]{2}[:-]){5}[0-9A-F]{2}")

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
