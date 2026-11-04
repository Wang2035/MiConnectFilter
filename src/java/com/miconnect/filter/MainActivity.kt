package com.miconnect.filter

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 设置页（v1.3 重写）：Miuix 设置风——分组卡片 + 统一字号（标题 26 / 行 16 / 说明 13），
 * 首屏只放状态、开关、白名单、临时放行；说明文字压到最短，次要操作收进按钮/弹窗。
 */
class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private var dark = false

    private val accent = "#3482FF"
    private val warn = "#FF9500"
    private lateinit var bg: String
    private lateinit var cardBg: String
    private lateinit var titleC: String
    private lateinit var subC: String
    private lateinit var chipBg: String
    private lateinit var dividerC: String

    private lateinit var vRoot: TextView
    private lateinit var vLsp: TextView
    private lateinit var vOs: TextView
    private lateinit var vTargets: TextView
    private lateinit var bypassState: TextView
    private lateinit var bypassBtn: TextView
    private lateinit var wlCount: TextView
    private lateinit var wlBox: LinearLayout
    private val chipViews = HashMap<Int, TextView>()
    private var bypassMinutes = FilterCore.MAX_BYPASS_MINUTES

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            updateBypassUi()
            handler.postDelayed(this, 1000)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
    }

    // ---------- 通用小部件（全页统一规格） ----------

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), dp(8))
        background = GradientDrawable().apply {
            setColor(Color.parseColor(cardBg))
            cornerRadius = dp(24).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(12) }
    }

    private fun txt(s: String, size: Float, color: String, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(Color.parseColor(color))
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    /** 设置行：左标题（16）+ 右侧控件/值。 */
    private fun row(title: String, right: View, withDivider: Boolean = true): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(13), 0, dp(13))
            addView(txt(title, 16f, titleC).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(right)
            if (withDivider) {
                // 用下边距模拟分隔感由外层顺序承担，这里不画线，保持干净
            }
        }

    private fun valueText(): TextView = txt("…", 14f, subC)

    private fun capsule(label: String, filled: Boolean): TextView = TextView(this).apply {
        text = label
        textSize = 15f
        gravity = Gravity.CENTER
        setPadding(0, dp(12), 0, dp(12))
        setTextColor(Color.parseColor(if (filled) "#FFFFFF" else accent))
        background = GradientDrawable().apply {
            setColor(Color.parseColor(if (filled) accent else chipBg))
            cornerRadius = dp(24).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(10); bottomMargin = dp(10) }
    }

    // ---------- 生命周期 ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = ConfSync.prefs(this)
        bypassMinutes = FilterCore.clampBypassMinutes(
            prefs.getInt("bypass_minutes", FilterCore.MAX_BYPASS_MINUTES),
        )
        dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        bg = if (dark) "#000000" else "#F5F5F7"
        cardBg = if (dark) "#1C1C1E" else "#FFFFFF"
        titleC = if (dark) "#F5F5F5" else "#1A1A1A"
        subC = if (dark) "#98989F" else "#8E8E93"
        chipBg = if (dark) "#2C2C2E" else "#F2F2F7"
        dividerC = if (dark) "#38383A" else "#EFEFF4"
        window.statusBarColor = Color.parseColor(bg)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        refreshWhitelist()
        runChecks()
        syncAsync(showError = false)
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    // ---------- 界面 ----------

    private fun buildUi(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), statusBarHeight() + dp(14), dp(16), dp(20))
            setBackgroundColor(Color.parseColor(bg))
        }
        root.addView(txt("妙享快连过滤", 26f, titleC, bold = true))
        root.addView(txt("小米耳机弹窗白名单", 13f, subC).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(14) }
        })

        // 状态
        val statusCard = card()
        vRoot = valueText(); statusCard.addView(row("Root", vRoot))
        vLsp = valueText(); statusCard.addView(row("LSPosed", vLsp))
        vOs = valueText(); statusCard.addView(row("系统", vOs))
        vTargets = valueText(); statusCard.addView(row("作用域应用", vTargets))
        val restartBtn = capsule("重启作用域应用", filled = false).apply {
            setOnClickListener { confirmRestartScope() }
        }
        statusCard.addView(restartBtn)
        root.addView(statusCard)

        // 过滤
        val ctlCard = card()
        val enableSwitch = MiuixUi.MiuixSwitch(this, prefs.getBoolean("enabled", true)).apply {
            onCheckedChange = { on ->
                prefs.edit().putBoolean("enabled", on).apply()
                syncAsync(showError = true)
            }
        }
        ctlCard.addView(row("启用拦截", enableSwitch))
        val debugSwitch = MiuixUi.MiuixSwitch(this, prefs.getBoolean("debug", false)).apply {
            onCheckedChange = { on ->
                prefs.edit().putBoolean("debug", on).apply()
                syncAsync(showError = true)
            }
        }
        ctlCard.addView(row("诊断日志", debugSwitch))
        val logBtn = capsule("拦截记录", filled = false).apply {
            setOnClickListener { showLog() }
        }
        ctlCard.addView(logBtn)
        val diagBtn = capsule("查看日志", filled = false).apply {
            setOnClickListener { showDiagLog() }
        }
        ctlCard.addView(diagBtn)
        root.addView(ctlCard)

        // 临时放行
        val bypassCard = card()
        bypassState = txt("拦截中", 16f, titleC)
        bypassCard.addView(row("临时放行", bypassState))
        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        for (m in listOf(1, 3, 5)) {
            val chip = TextView(this).apply {
                text = "$m 分钟"
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(8))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = dp(8) }
                setOnClickListener {
                    bypassMinutes = m
                    prefs.edit().putInt("bypass_minutes", m).apply()
                    paintChips()
                    updateBypassUi()
                }
            }
            chipViews[m] = chip
            chipRow.addView(chip)
        }
        bypassCard.addView(chipRow)
        bypassBtn = capsule("暂停拦截", filled = true).apply {
            setOnClickListener { onBypassButton() }
        }
        bypassCard.addView(bypassBtn)
        root.addView(bypassCard)

        // 白名单
        val wlCard = card()
        wlCount = txt("", 14f, subC)
        wlCard.addView(row("白名单", wlCount))
        wlBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wlCard.addView(wlBox)
        val pairedBtn = capsule("从已配对设备添加", filled = true).apply {
            setOnClickListener { startActivity(Intent(this@MainActivity, PairedActivity::class.java)) }
        }
        wlCard.addView(pairedBtn)
        val manualBtn = capsule("手动输入 MAC", filled = false).apply {
            setOnClickListener { showAddDialog() }
        }
        wlCard.addView(manualBtn)
        root.addView(wlCard)

        root.addView(txt("不在白名单的小米耳机弹窗都会拦，包括已配对的；先把自己的耳机加进白名单。", 13f, subC))
        root.addView(txt("作用域用 LSPosed 推荐勾选（6 个全选）；改完点「重启作用域应用」。", 13f, subC))
        root.addView(txt("项目地址：github.com/Wang2035/MiConnectFilter", 13f, accent).apply {
            setPadding(0, dp(8), 0, 0)
            setOnClickListener {
                try {
                    startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://github.com/Wang2035/MiConnectFilter"),
                        ),
                    )
                } catch (_: Throwable) {
                    toast("没有可用的浏览器")
                }
            }
        })

        paintChips()
        updateBypassUi()
        return ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(bg))
            addView(root)
        }
    }

    private fun paintChips() {
        for ((m, chip) in chipViews) {
            val selected = m == bypassMinutes
            chip.setTextColor(Color.parseColor(if (selected) "#FFFFFF" else titleC))
            chip.background = GradientDrawable().apply {
                setColor(Color.parseColor(if (selected) accent else chipBg))
                cornerRadius = dp(18).toFloat()
            }
        }
    }

    private fun updateBypassUi() {
        if (!::bypassState.isInitialized) return
        val until = ConfSync.effectiveBypassUntil(prefs)
        if (until > 0) {
            val remainSec = ((until - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
            bypassState.text = "剩 ${remainSec / 60}:${"%02d".format(remainSec % 60)}"
            bypassState.setTextColor(Color.parseColor(warn))
            bypassBtn.text = "恢复拦截"
        } else {
            bypassState.text = "未开启"
            bypassState.setTextColor(Color.parseColor(subC))
            bypassBtn.text = "暂停拦截 $bypassMinutes 分钟"
        }
    }

    private fun onBypassButton() {
        val active = ConfSync.effectiveBypassUntil(prefs) > 0
        if (active) {
            prefs.edit().putLong("bypass_until", 0L).apply()
            toast("已恢复拦截")
        } else {
            val minutes = FilterCore.clampBypassMinutes(bypassMinutes)
            prefs.edit().putLong(
                "bypass_until", System.currentTimeMillis() + minutes * 60_000L,
            ).apply()
            toast("已暂停 $minutes 分钟，去配对新耳机")
        }
        syncAsync(showError = true)
        updateBypassUi()
    }

    private fun refreshWhitelist() {
        if (!::wlBox.isInitialized) return
        var list = ConfSync.readWhitelist(prefs)
        // 名称同步：已配对设备改过名（别名）时，把白名单里存的旧名自动更新为新名
        val names = DeviceNames.bondedNameMap()
        if (names.isNotEmpty()) {
            var changed = false
            val updated = list.map { (mac, label) ->
                val cur = names[mac]
                if (cur != null && cur != label) {
                    changed = true
                    mac to cur
                } else {
                    mac to label
                }
            }
            if (changed) {
                ConfSync.writeWhitelist(prefs, updated)
                list = updated
            }
        }
        wlCount.text = "${list.size} 台"
        wlBox.removeAllViews()
        if (list.isEmpty()) {
            wlBox.addView(txt("还没有添加", 14f, subC).apply {
                setPadding(0, dp(6), 0, dp(6))
            })
            return
        }
        for ((mac, label) in list) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(9), 0, dp(9))
                addView(txt(if (label.isNotBlank()) label else "未命名", 16f, titleC))
                addView(txt(mac, 12f, subC))
                setOnClickListener {
                    MiuixUi.showDialog(
                        this@MainActivity,
                        title = "删除这台耳机？",
                        message = mac,
                        positiveText = "删除",
                        onPositive = {
                            ConfSync.writeWhitelist(
                                prefs,
                                ConfSync.readWhitelist(prefs).filter { it.first != mac },
                            )
                            refreshWhitelist()
                            syncAsync(showError = true)
                        },
                        negativeText = "取消",
                    )
                }
            }
            wlBox.addView(item)
        }
    }

    private fun showAddDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val macInput = MiuixUi.input(this, "MAC，如 AA:BB:CC:DD:EE:FF")
        val labelInput = MiuixUi.input(this, "备注名（可空）")
        box.addView(macInput)
        box.addView(labelInput)
        MiuixUi.showDialog(
            this,
            title = "手动添加",
            content = box,
            positiveText = "添加",
            onPositive = {
                val norm = MacUtil.normalize(macInput.text?.toString())
                if (norm == null) {
                    toast("MAC 格式不对")
                } else if (ConfSync.addToWhitelist(this, norm, labelInput.text?.toString() ?: "")) {
                    refreshWhitelist()
                    syncAsync(showError = true)
                    toast("已添加 $norm")
                } else {
                    toast("已经在白名单里")
                }
            },
            negativeText = "取消",
        )
    }

    // ---------- 重启作用域 ----------

    private fun confirmRestartScope() {
        MiuixUi.showDialog(
            this,
            title = "重启作用域应用？",
            message = "用 root 结束这 4 个应用的进程，它们会自动重新启动并加载模块。蓝牙会短暂断开重连。",
            positiveText = "重启",
            onPositive = { restartScope() },
            negativeText = "取消",
        )
    }

    private fun restartScope() {
        toast("正在重启…")
        Thread {
            val results = RootUtil.restartScopeApps()
            val msg = results.joinToString("\n") { (pkg, state) ->
                val label = Targets.LABELS[pkg] ?: pkg
                when (state) {
                    "killed" -> "$label：已重启"
                    "not-running" -> "$label：未运行（下次启动生效）"
                    else -> "$label：失败"
                }
            }
            runOnUiThread {
                MiuixUi.showDialog(
                    this, title = "重启结果", message = msg, positiveText = "好",
                )
            }
        }.start()
    }

    // ---------- 记录 / 状态 ----------

    private fun showLog() {
        Thread {
            val fileLog = RootUtil.readLogTail(40)
            val (_, lc) = RootUtil.execSu(
                "logcat -d -s MiConnectFilter:* 2>/dev/null | grep -E 'EVT|CONFIG|INIT|HOOK|SCAN|config' | tail -n 80",
            )
            val lcLines = lc.lines().map { line ->
                val idx = line.indexOf("MiConnectFilter:")
                if (idx >= 0) line.substring(idx + "MiConnectFilter:".length).trim() else line
            }.filter { it.isNotBlank() }
            val parts = ArrayList<String>()
            if (lcLines.isNotEmpty()) parts.add(lcLines.joinToString("\n"))
            if (fileLog.isNotBlank()) parts.add(fileLog)
            val pretty = if (parts.isEmpty()) "暂无记录" else parts.joinToString("\n")
            runOnUiThread {
                MiuixUi.showDialog(
                    this,
                    title = "拦截记录",
                    message = pretty,
                    positiveText = "关闭",
                    negativeText = "清除",
                    onNegative = {
                        Thread {
                            RootUtil.clearLog()
                            RootUtil.execSu("logcat -c 2>/dev/null")
                        }.start()
                    },
                    scrollMessage = true,
                )
            }
        }.start()
    }

    /** 汇总一份完整排查文本：版本/系统/当前设置/配置文件/logcat 与事件记录。 */
    private fun buildDiagText(): String {
        val sb = StringBuilder()
        try {
            val own = packageManager.getPackageInfo(packageName, 0)
            sb.append("MiConnectFilter v").append(own.versionName)
                .append(" (").append(own.longVersionCode).append(")\n")
        } catch (_: Throwable) {}
        sb.append("time=").append(java.util.Date().toString()).append('\n')
        sb.append("model=").append(RootUtil.getProp("ro.product.model")).append('\n')
        sb.append("miui=").append(RootUtil.getProp("ro.mi.os.version.name"))
            .append(" android=").append(RootUtil.getProp("ro.build.version.release")).append('\n')
        val v = ConfSync.currentValues(this)
        sb.append("settings: enabled=").append(v.enabled)
            .append(" debug=").append(v.debug)
            .append(" whitelist=").append(v.whitelist.sorted().joinToString(","))
            .append(" bypass_until=").append(v.bypassUntilMs)
            .append(" now=").append(System.currentTimeMillis()).append("\n\n")
        val installedList = Targets.PACKAGES.sorted().joinToString(", ") { pkg ->
            val ok = try { packageManager.getPackageInfo(pkg, 0); true } catch (_: Throwable) { false }
            "$pkg=${if (ok) "installed" else "missing"}"
        }
        sb.append("targets: ").append(installedList).append("\n\n")
        val (_, conf) = RootUtil.execSu("cat ${ConfSync.CONF_PATH} 2>/dev/null; ls -l ${ConfSync.CONF_PATH} 2>&1")
        sb.append("== conf file ==\n").append(conf).append("\n\n")
        val (_, lc) = RootUtil.execSu("logcat -d -s MiConnectFilter:* 2>/dev/null | tail -n 300")
        sb.append("== logcat (MiConnectFilter) ==\n").append(lc).append("\n\n")
        val (_, tl) = RootUtil.execSu("tail -n 200 ${ConfSync.LOG_PATH} 2>/dev/null")
        sb.append("== decision log file ==\n").append(tl).append('\n')
        return sb.toString()
    }

    /** 查看完整日志；导出按钮就在这个弹窗里。 */
    private fun showDiagLog() {
        Thread {
            val text = buildDiagText()
            runOnUiThread {
                MiuixUi.showDialog(
                    this,
                    title = "日志",
                    message = text,
                    positiveText = "导出",
                    onPositive = { exportDiagText(text) },
                    negativeText = "关闭",
                    extraText = "清除",
                    onExtra = { clearAllLogs() },
                    scrollMessage = true,
                )
            }
        }.start()
    }

    /** 清除全部日志：决策日志文件 + logcat 缓冲 + 已导出文件。 */
    private fun clearAllLogs() {
        Thread {
            RootUtil.clearLog()
            RootUtil.execSu("logcat -c 2>/dev/null; rm -f /sdcard/Download/MiConnectFilter-diag.txt")
            try { java.io.File(cacheDir, "MiConnectFilter-diag.txt").delete() } catch (_: Throwable) {}
        }.start()
        toast("日志已清除")
    }

    private fun exportDiagText(text: String) {
        toast("正在导出…")
        Thread {
            val outFile = java.io.File(cacheDir, "MiConnectFilter-diag.txt")
            outFile.writeText(text)
            val dest = "/sdcard/Download/MiConnectFilter-diag.txt"
            val (code, _) = RootUtil.execSu("cp ${outFile.absolutePath} $dest && chmod 644 $dest")
            runOnUiThread {
                toast(if (code == 0) "已导出到 下载/MiConnectFilter-diag.txt" else "导出失败：需要 Root")
            }
        }.start()
    }

    private fun runChecks() {
        Thread {
            val root = RootUtil.checkRoot()
            val lsp = if (root) RootUtil.checkLspd() else false
            val installed = Targets.PACKAGES.count { pkg ->
                try { packageManager.getPackageInfo(pkg, 0); true } catch (_: Throwable) { false }
            }
            val miOs = RootUtil.getProp("ro.mi.os.version.name")
            val androidVer = RootUtil.getProp("ro.build.version.release")
            runOnUiThread {
                vRoot.text = if (root) "已授权" else "未授权"
                vLsp.text = if (!root) "需 Root 检测" else if (lsp) "已激活" else "未检测到"
                vOs.text = when {
                    miOs.isNotEmpty() && androidVer.isNotEmpty() -> "澎湃 $miOs · Android $androidVer"
                    miOs.isNotEmpty() -> "澎湃 $miOs"
                    androidVer.isNotEmpty() -> "Android $androidVer"
                    else -> "未知"
                }
                vTargets.text = "已装 $installed/${Targets.PACKAGES.size}"
            }
        }.start()
    }

    private fun syncAsync(showError: Boolean) {
        Thread {
            val ok = ConfSync.sync(this)
            if (!ok && showError) runOnUiThread { toast("同步失败：需要 Root 授权") }
        }.start()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
