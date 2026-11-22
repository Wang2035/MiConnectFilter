package io.github.wang2035.miconnectfilter

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
import android.widget.ImageView
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
    private lateinit var bgState: TextView
    private lateinit var bgBox: LinearLayout
    private var bgProgressRow: LinearLayout? = null
    private var bgProgressBar: android.widget.ProgressBar? = null
    private var bgProgressText: TextView? = null

    /** 只有用户主动导入时才让抽帧进度刷到进度条（开机自动迁移不刷）。 */
    @Volatile
    private var importUiActive = false
    private var pendingBgBytes: ByteArray? = null
    private var pendingBgFile: java.io.File? = null
    private var pendingBgMime: String = "image/jpeg"
    private var pendingBgOpt: BgOpt = BgOpt.DEFAULT
    private var pendingBgFrames: Int = 0
    private var pendingBgTotalMs: Long = 0L
    private var pendingBgPoster: android.graphics.Bitmap? = null
    private var pendingBgNote: String? = null

    private companion object {
        const val REQ_PICK_BG = 4211

        /** 真机弹窗卡片宽高比（按用户截图量得：卡片 864×987，截图宽 921）。 */
        const val POPUP_CARD_RATIO = 0.875f
    }
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
        setPadding(dp(16), dp(4), dp(16), dp(4))
        background = GradientDrawable().apply {
            setColor(Color.parseColor(cardBg))
            cornerRadius = dp(24).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(10) }
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
            setPadding(0, dp(9), 0, dp(9))
            addView(txt(title, 16f, titleC).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(right)
            if (withDivider) {
                // 用下边距模拟分隔感由外层顺序承担，这里不画线，保持干净
            }
        }

    /** 两行式开关行：标题 + 小字说明叠在左侧，开关在右侧，节奏与其它卡一致。 */
    private fun switchRow(title: String, hint: String, sw: View): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(9), 0, dp(9))
            val left = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(txt(title, 16f, titleC))
                addView(txt(hint, 13f, subC).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(2) }
                })
            }
            addView(left)
            addView(sw)
        }

    private fun valueText(): TextView = txt("…", 14f, subC)

    private fun capsule(label: String, filled: Boolean): TextView = TextView(this).apply {
        text = label
        textSize = 15f
        gravity = Gravity.CENTER
        setPadding(0, dp(9), 0, dp(9))
        setTextColor(Color.parseColor(if (filled) "#FFFFFF" else accent))
        background = GradientDrawable().apply {
            setColor(Color.parseColor(if (filled) accent else chipBg))
            cornerRadius = dp(24).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8); bottomMargin = dp(8) }
    }

    // ---------- 生命周期 ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = ConfSync.prefs(this)
        BgStore.migrateIfNeeded(this)
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
        // 上次没走完的导入残留（取消选目标等）清掉，不长期占着
        try { java.io.File(cacheDir, "pending_bg.zip").delete() } catch (_: Throwable) {}
        try { java.io.File(cacheDir, "bg_frames_tmp").deleteRecursively() } catch (_: Throwable) {}
        migrateFontColorPref()
        migrateNativeVideos()
    }

    /** v1.41 前字体颜色是全局一档；迁到每个背景自己的设置里，迁完清掉全局值免得误会。 */
    private fun migrateFontColorPref() {
        try {
            val g = prefs.getInt("font_color", 0).coerceIn(0, 2)
            if (g == 0) return
            for (key in BgStore.keys(this)) {
                val o = BgStore.optFor(this, key)
                if (o.fontColor == 0) BgStore.setOpt(this, key, o.copy(fontColor = g))
            }
            prefs.edit().putInt("font_color", 0).apply()
        } catch (_: Throwable) {}
    }

    /**
     * v1.29/v1.30 直放时代存的原视频背景（video/mp4）自动转成帧包：
     * 蓝牙进程用不了媒体服务、直放播不了；在本应用进程里按原帧率抽帧替换，
     * 用户不用重新导入。转完 mime 变成 video/frames，不会重复转。
     */
    private fun migrateNativeVideos() {
        Thread {
            try {
                var changed = false
                for (key in BgStore.keys(this)) {
                    if (!BgStore.isNativeVideo(this, key)) continue
                    val tmp = java.io.File(cacheDir, "migrate_bg.mp4")
                    try {
                        BgStore.fileFor(this, key).copyTo(tmp, overwrite = true)
                    } catch (_: Throwable) {
                        continue
                    }
                    val err = processBgVideoFile(tmp)
                    try { tmp.delete() } catch (_: Throwable) {}
                    val bytes = pendingBgBytes
                    val pFile = pendingBgFile
                    if (err == null && (bytes != null || pFile != null)) {
                        if (pFile != null) BgStore.saveFile(this, key, pFile, pendingBgMime)
                        else BgStore.save(this, key, bytes!!, pendingBgMime)
                        if (pendingBgFrames > 0) {
                            BgStore.setFrameInfo(this, key, pendingBgFrames, pendingBgTotalMs)
                        }
                        pendingBgPoster?.let { BgStore.savePoster(this, key, it) }
                        changed = true
                    }
                    pendingBgBytes = null
                    if (pFile != null) {
                        try { pFile.delete() } catch (_: Throwable) {}
                        pendingBgFile = null
                    }
                    pendingBgPoster = null
                    pendingBgFrames = 0
                    pendingBgTotalMs = 0L
                }
                if (changed) {
                    runOnUiThread { refreshBgUi() }
                    syncAsync(showError = false)
                }
            } catch (_: Throwable) {}
        }.start()
    }

    private var autoUpdateChecked = false

    override fun onResume() {
        super.onResume()
        refreshWhitelist()
        refreshBgUi()
        runChecks()
        syncAsync(showError = false)
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        if (!autoUpdateChecked) {
            autoUpdateChecked = true
            // 自动检查放在 onResume：onCreate 阶段弹的窗会被开场转场吞掉（一闪而过）
            checkUpdate(auto = true)
        }
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
        root.addView(txt("妙享快连", 26f, titleC, bold = true))
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
            // 状态卡里只做个小按钮靠右放，不整行占高
            setPadding(dp(16), dp(7), dp(16), dp(7))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.END
                topMargin = dp(4)
                bottomMargin = dp(6)
            }
        }
        statusCard.addView(restartBtn)
        root.addView(statusCard)

        // 临时放行（独立卡片，整体排在第 2 张）
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

        // 过滤
        val ctlCard = card()
        val batterySyncSwitch = MiuixUi.MiuixSwitch(this, prefs.getBoolean("battery_sync", true)).apply {
            onCheckedChange = { on ->
                prefs.edit().putBoolean("battery_sync", on).apply()
                syncAsync(showError = true)
            }
        }
        ctlCard.addView(switchRow("断联电量显示", "断开时把左/右/盒电量补进通知卡，补的值后标「断」。", batterySyncSwitch))
        val batteryKeepSwitch = MiuixUi.MiuixSwitch(this, prefs.getBoolean("battery_keep", true)).apply {
            onCheckedChange = { on ->
                prefs.edit().putBoolean("battery_keep", on).apply()
                syncAsync(showError = true)
            }
        }
        ctlCard.addView(switchRow("全断联时保留电量", "全部断开后通知卡保留；关掉恢复原样。", batteryKeepSwitch))

        // 弹窗背景（可按设备分别设置）
        val bgCard = card()
        bgState = valueText(); bgCard.addView(row("弹窗背景", bgState))
        bgBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        bgCard.addView(bgBox)
        val bgAddBtn = capsule("添加背景", filled = true).apply {
            setOnClickListener { pickBgImage() }
        }
        bgCard.addView(bgAddBtn)
        val pBar = android.widget.ProgressBar(
            this, null, android.R.attr.progressBarStyleHorizontal,
        ).apply {
            max = 100
            progressTintList =
                android.content.res.ColorStateList.valueOf(Color.parseColor("#3482FF"))
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { weight = 1f }
        }
        val pText = txt("导入中…", 12.5f, subC).apply { setPadding(dp(10), 0, 0, 0) }
        bgProgressBar = pBar
        bgProgressText = pText
        bgProgressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(0, dp(10), 0, dp(2))
            addView(pBar)
            addView(pText)
        }
        bgCard.addView(bgProgressRow)
        bgCard.addView(txt("照片、动图、视频都行；点开可改播放方式、速度、区域和字体颜色。单台耳机的优先于全部设备。", 13f, subC).apply {
            setPadding(0, dp(4), 0, dp(8))
        })
        root.addView(bgCard)

        // 白名单
        val wlCard = card()
        val enableSwitch = MiuixUi.MiuixSwitch(this, prefs.getBoolean("enabled", true)).apply {
            onCheckedChange = { on ->
                prefs.edit().putBoolean("enabled", on).apply()
                syncAsync(showError = true)
            }
        }
        wlCard.addView(row("启用拦截", enableSwitch))
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

        wlCard.addView(txt("不在白名单的小米耳机弹窗都拦，包括已配对的。", 13f, subC).apply {
            setPadding(0, dp(6), 0, dp(6))
        })
        root.addView(ctlCard)
        // 日志（独立卡片，倒数第二张）
        val diagCard = card()
        val debugSwitch = MiuixUi.MiuixSwitch(this, prefs.getBoolean("debug", false)).apply {
            onCheckedChange = { on ->
                prefs.edit().putBoolean("debug", on).apply()
                syncAsync(showError = true)
            }
        }
        diagCard.addView(row("诊断日志", debugSwitch))
        val diagBtn = capsule("查看日志", filled = false).apply {
            setOnClickListener { showDiagLog() }
        }
        diagCard.addView(diagBtn)
        root.addView(diagCard)

        // 关于：项目地址单独一栏（用户要求，不再放页脚小字）
        val aboutCard = card()
        val ghRow = row("项目地址", txt("›", 20f, subC))
        ghRow.setOnClickListener {
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
        aboutCard.addView(ghRow)
        val updRow = row("检查更新", txt("›", 20f, subC))
        updRow.setOnClickListener { checkUpdate(auto = false) }
        aboutCard.addView(updRow)
        val verTxt = valueText()
        verTxt.text = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (_: Throwable) {
            ""
        }
        aboutCard.addView(row("版本", verTxt, withDivider = false))
        root.addView(aboutCard)

        paintChips()
        updateBypassUi()
        refreshBgUi()
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
        val groups = PairedGrouping.group(list.map { PairedEntry(it.second, it.first) })
        wlCount.text = "${groups.size} 台"
        wlBox.removeAllViews()
        if (list.isEmpty()) {
            wlBox.addView(txt("还没有添加", 14f, subC).apply {
                setPadding(0, dp(6), 0, dp(6))
            })
            return
        }
        for (group in groups) {
            val macs = group.map { it.mac }
            val label = group.first().name ?: ""
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(9), 0, dp(9))
                addView(txt(if (label.isNotBlank()) label else "未命名", 16f, titleC))
                addView(txt(
                    if (macs.size == 1) macs.single()
                    else macs.joinToString(" · ") + "\n同一设备的 ${macs.size} 个地址",
                    12f, subC))
                setOnClickListener {
                    MiuixUi.showDialog(
                        this@MainActivity,
                        title = "删除这台耳机？",
                        message = macs.joinToString("\n"),
                        positiveText = "删除",
                        onPositive = {
                            ConfSync.removeFromWhitelist(this@MainActivity, macs)
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

    // ---------- 弹窗背景 ----------

    private fun refreshBgUi() {
        if (!::bgBox.isInitialized) return
        BgStore.migrateIfNeeded(this)
        // 同一台设备的多个地址归成一行（和已配对列表的合并规则一致）
        val groups = LinkedHashMap<String, MutableList<String>>()
        for (k in BgStore.keys(this)) {
            groups.getOrPut(BgStore.labelFor(this, k)) { ArrayList() }.add(k)
        }
        bgState.text = if (groups.isEmpty()) "未设置" else "${groups.size} 张"
        bgBox.removeAllViews()
        for ((_, gkeys) in groups) bgBox.addView(bgRow(gkeys))
    }

    private fun bgRow(keys: List<String>): View {
        val key = keys.first()
        val rowL = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                setColor(Color.parseColor(chipBg))
                cornerRadius = dp(10).toFloat()
            }
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(44)).apply { rightMargin = dp(12) }
        }
        // 缩略图只显示静态首帧（设置页不播动态图，省性能）
        try {
            val f = BgStore.fileFor(this, key)
            thumb.setImageBitmap(
                when {
                    BgStore.isNativeVideo(this, key) -> BgStore.posterBitmap(this, key)
                    BgStore.isVideo(this, key) -> firstFrameOfBundle(f)
                    else -> android.graphics.BitmapFactory.decodeFile(f.absolutePath)
                },
            )
        } catch (_: Throwable) {}
        rowL.addView(thumb)
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(txt(BgStore.labelFor(this, key), 15f, titleC))
        val opt = BgStore.optFor(this, key)
        val parts = ArrayList<String>()
        val info = BgStore.frameInfo(this, key)
        when {
            BgStore.isNativeVideo(this, key) -> parts.add(
                if (info != null && info.second > 0) {
                    val sec = info.second / 1000.0
                    if (sec % 1.0 == 0.0) {
                        "视频 · ${sec.toInt()} 秒"
                    } else {
                        "视频 · ${String.format("%.1f", sec)} 秒"
                    }
                } else {
                    "视频"
                },
            )
            BgStore.isVideo(this, key) ->
                parts.add(if (info != null) "视频 · ${info.first} 帧" else "视频")
            BgStore.isGif(this, key) ->
                parts.add(if (info != null) "动态图 · ${info.first} 帧" else "动态图")
            BgStore.isAnimated(this, key) -> parts.add("动态图")
            else -> parts.add("照片")
        }
        if (BgStore.isAnimated(this, key)) {
            parts.add(
                when (opt.mode) {
                    BgOpt.MODE_ONCE -> "播一次停"
                    BgOpt.MODE_PINGPONG -> "往返"
                    else -> "循环"
                },
            )
            if (opt.speedPct != 100) {
                parts.add((opt.speedPct / 100.0).toString().trimEnd('0').trimEnd('.') + "×")
            }
        }
        if (opt.fontColor == 1) parts.add("白字") else if (opt.fontColor == 2) parts.add("黑字")
        if (opt.scalePct != 100) parts.add("区域 ${opt.scalePct}%")
        if (key == BgStore.ALL) {
            parts.add("所有放行的耳机弹窗")
        } else {
            val macs = keys.mapNotNull { MacUtil.macOfKey(it) }
            if (macs.size > 1) parts.add("${macs.first()} 等 ${macs.size} 个地址")
        }
        texts.addView(txt(parts.joinToString(" · "), 12.5f, subC))
        rowL.addView(texts)
        rowL.addView(txt("删除", 14f, "#FF3B30").apply {
            setPadding(dp(10), dp(6), dp(2), dp(6))
            setOnClickListener { clearBgImage(keys) }
        })
        rowL.setOnClickListener { showBgOptionsSheet(isNew = false, editKeys = keys) }
        rowL.setOnLongClickListener {
            showBgOptionsSheet(isNew = false, editKeys = keys, viaLongPress = true)
            true
        }
        return rowL
    }

    /** 导入进度条：只在导入期间显示；pct=null 为不确定状态（刚开始/静态图）。 */
    private fun showImportProgress(pct: Int?) {
        runOnUiThread {
            val rowV = bgProgressRow ?: return@runOnUiThread
            rowV.visibility = View.VISIBLE
            val bar = bgProgressBar ?: return@runOnUiThread
            if (pct == null) {
                bar.isIndeterminate = true
                bgProgressText?.text = "导入中…"
            } else {
                bar.isIndeterminate = false
                bar.progress = pct.coerceIn(0, 100)
                bgProgressText?.text = "导入中 ${bar.progress}%"
            }
        }
    }

    private fun hideImportProgress() {
        runOnUiThread { bgProgressRow?.visibility = View.GONE }
    }

    /** 抽帧线程报进度（0–99；导入完成时进度条直接收起，不停在 100）。 */
    private fun reportImportProgress(pct: Int) {
        if (importUiActive) showImportProgress(pct)
    }

    private fun pickBgImage() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
        }
        try {
            startActivityForResult(intent, REQ_PICK_BG)
        } catch (_: Throwable) {
            toast("没有可用的文件选择器")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK_BG || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        importUiActive = true
        showImportProgress(null)
        Thread {
            val err = processBgImage(uri)
            runOnUiThread {
                importUiActive = false
                hideImportProgress()
                if (err == null) {
                    pendingBgNote?.let { toast(it) }
                    pendingBgNote = null
                    // 重新导入：把已有背景的播放设置（模式/速度等）带进面板，
                    // 用户不动就是沿用，清一色默认值会把调好的设置悄悄覆盖掉
                    pendingBgOpt = BgStore.optFor(this@MainActivity, BgStore.ALL)
                        .takeIf { it != BgOpt.DEFAULT }
                        ?: BgStore.keys(this@MainActivity).firstNotNullOfOrNull { k ->
                            BgStore.optFor(this@MainActivity, k).takeIf { o -> o != BgOpt.DEFAULT }
                        }
                        ?: BgOpt.DEFAULT
                    showBgOptionsSheet(isNew = true)
                } else {
                    toast(err)
                }
            }
        }.start()
    }

    // ---------- 背景选项（模式 / 速度 / 显示区域） ----------

    /** 与 hook 侧裁剪同式的可见窗口计算：返回源图上可见区域的宽高。 */
    private fun bgVisibleWindow(sw: Int, sh: Int, dw: Int, dh: Int, opt: BgOpt): Pair<Float, Float> {
        val s0 = maxOf(dw.toFloat() / sw, dh.toFloat() / sh)
        val s = s0 * opt.scalePct / 100f
        return (dw / s).coerceIn(1f, sw.toFloat()) to (dh / s).coerceIn(1f, sh.toFloat())
    }

    /** 简单流式布局：子视图按自然宽排，排不下整颗换行（胶囊组用，防文字被挤成折行）。 */
    private class FlowLayout(ctx: android.content.Context) : ViewGroup(ctx) {
        var hGap = 0
        var vGap = 0

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val maxW = MeasureSpec.getSize(widthMeasureSpec)
            var x = 0
            var y = 0
            var rowH = 0
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                measureChild(c, widthMeasureSpec, heightMeasureSpec)
                val lp = c.layoutParams as MarginLayoutParams
                val cw = c.measuredWidth + lp.leftMargin + lp.rightMargin
                if (x > 0 && x + cw > maxW) {
                    x = 0
                    y += rowH + vGap
                    rowH = 0
                }
                x += cw + hGap
                rowH = maxOf(rowH, c.measuredHeight + lp.topMargin + lp.bottomMargin)
            }
            setMeasuredDimension(maxW, y + rowH)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val maxW = width
            var x = 0
            var y = 0
            var rowH = 0
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                val lp = c.layoutParams as MarginLayoutParams
                val cw = c.measuredWidth + lp.leftMargin + lp.rightMargin
                if (x > 0 && x + cw > maxW) {
                    x = 0
                    y += rowH + vGap
                    rowH = 0
                }
                c.layout(
                    x + lp.leftMargin, y + lp.topMargin,
                    x + lp.leftMargin + c.measuredWidth, y + lp.topMargin + c.measuredHeight,
                )
                x += cw + hGap
                rowH = maxOf(rowH, c.measuredHeight + lp.topMargin + lp.bottomMargin)
            }
        }

        override fun generateLayoutParams(attrs: android.util.AttributeSet?): LayoutParams =
            MarginLayoutParams(context, attrs)

        override fun generateDefaultLayoutParams(): LayoutParams =
            MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

        override fun generateLayoutParams(p: LayoutParams): LayoutParams = MarginLayoutParams(p)

        override fun checkLayoutParams(p: LayoutParams): Boolean = p is MarginLayoutParams
    }

    private fun chipGroup(
        labels: List<String>,
        initial: Int,
        enabled: (Int) -> Boolean,
        onPick: (Int) -> Unit,
    ): View {
        var sel = initial
        val flow = FlowLayout(this).apply {
            hGap = dp(8)
            vGap = dp(8)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        var paint: () -> Unit = {}
        val chips = labels.mapIndexed { i, label ->
            TextView(this).apply {
                text = label
                textSize = 14f
                isSingleLine = true
                gravity = android.view.Gravity.CENTER
                includeFontPadding = false
                setPadding(dp(15), 0, dp(15), 0)
                layoutParams = ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(40),
                )
                setOnClickListener {
                    if (enabled(i)) {
                        sel = i
                        paint()
                        onPick(i)
                    }
                }
            }
        }
        paint = {
            for ((i, chip) in chips.withIndex()) {
                val on = enabled(i)
                chip.setTextColor(
                    Color.parseColor(
                        when {
                            !on -> subC
                            i == sel -> "#FFFFFF"
                            else -> titleC
                        },
                    ),
                )
                chip.background = GradientDrawable().apply {
                    setColor(Color.parseColor(if (on && i == sel) accent else chipBg))
                    cornerRadius = dp(20).toFloat()
                }
                chip.alpha = if (on) 1f else 0.45f
            }
        }
        chips.forEach { flow.addView(it) }
        paint()
        return flow
    }

    private fun sheetLabel(s: String): TextView = txt(s, 13f, subC).apply {
        setPadding(0, dp(14), 0, dp(7))
    }

    /** 速度百分比显示：100 →「1×」、150 →「1.5×」、275 →「2.75×」。 */
    private fun formatSpeed(pct: Int): String {
        val s = String.format("%.2f", pct / 100f).trimEnd('0').trimEnd('.')
        return "$s×"
    }

    /** 预览播放用：把 GIF / 视频帧包的全部帧解成位图（采样到长边 ≤1080，预览够用）。 */
    private fun loadPreviewFrames(
        isNew: Boolean,
        editKey: String?,
    ): Pair<List<android.graphics.Bitmap>?, IntArray?> {
        val gifBytes: ByteArray? = when {
            isNew && pendingBgMime == "image/gif" -> pendingBgBytes
            !isNew && editKey != null && BgStore.isGif(this, editKey) ->
                try { BgStore.fileFor(this, editKey).readBytes() } catch (_: Throwable) { null }
            else -> null
        }
        if (gifBytes != null) {
            val decoded = try { GifDecoder.decode(gifBytes) } catch (_: Throwable) { null }
                ?: return null to null
            if (decoded.size < 2) return null to null
            val frames = decoded.map { f ->
                android.graphics.Bitmap.createBitmap(
                    f.pixels, f.width, f.height, android.graphics.Bitmap.Config.ARGB_8888,
                )
            }
            return frames to IntArray(decoded.size) { decoded[it].delayMs }
        }
        val zip: java.io.File? = when {
            isNew && pendingBgMime == "video/frames" -> pendingBgFile
            !isNew && editKey != null && BgStore.isVideo(this, editKey) ->
                BgStore.fileFor(this, editKey)
            else -> null
        }
        if (zip != null && zip.exists()) {
            return try {
                java.util.zip.ZipFile(zip).use { z ->
                    var fps = 30
                    z.getEntry("meta.txt")?.let { e ->
                        val text = z.getInputStream(e).bufferedReader().readText()
                        Regex("fps=(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
                            ?.let { if (it in 1..120) fps = it }
                    }
                    val entries = z.entries().asSequence()
                        .filter { it.name.endsWith(".jpg") }
                        .sortedBy { it.name }
                        .toList()
                    if (entries.size < 2) return null to null
                    val frames = ArrayList<android.graphics.Bitmap>(entries.size)
                    for (e in entries) {
                        val bytes = z.getInputStream(e).use { it.readBytes() }
                        val bounds = android.graphics.BitmapFactory.Options().apply {
                            inJustDecodeBounds = true
                        }
                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        var sample = 1
                        val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
                        while (maxDim / (sample * 2) >= 1080 && sample < 16) sample *= 2
                        val bmp = android.graphics.BitmapFactory.decodeByteArray(
                            bytes, 0, bytes.size,
                            android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
                        ) ?: continue
                        frames.add(bmp)
                    }
                    if (frames.size < 2) null to null
                    else frames to IntArray(frames.size) { 1000 / fps }
                }
            } catch (_: Throwable) {
                null to null
            }
        }
        return null to null
    }

    /**
     * 背景选项面板：导入新图时先过这里（保存后去挑用在哪台设备），
     * 点已有背景行也能再打开改。kind：0=照片 1=GIF/帧包 2=WebP 动图 3=原视频直放。
     */
    private fun showBgOptionsSheet(
        isNew: Boolean,
        editKeys: List<String>? = null,
        viaLongPress: Boolean = false,
    ) {
        val kind: Int
        val firstFrame: android.graphics.Bitmap?
        var cur: BgOpt
        if (isNew) {
            kind = when {
                (pendingBgMime == "image/gif" || pendingBgMime == "video/frames") && pendingBgFrames >= 2 -> 1
                pendingBgMime == "video/mp4" -> 3
                pendingBgMime == "image/webp" -> 2
                else -> 0
            }
            firstFrame = pendingBgPoster ?: pendingBgBytes?.let {
                try { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) } catch (_: Throwable) { null }
            }
            cur = pendingBgOpt
        } else {
            val key = editKeys!!.first()
            kind = when {
                BgStore.isGif(this, key) -> 1
                BgStore.isNativeVideo(this, key) -> 3
                BgStore.isVideo(this, key) -> 1
                BgStore.isAnimated(this, key) -> 2
                else -> 0
            }
            firstFrame = try {
                when {
                    BgStore.isNativeVideo(this, key) ->
                        BgStore.posterBitmap(this, key)
                            ?: firstFrameOfVideoFile(BgStore.fileFor(this, key))
                    BgStore.isVideo(this, key) -> firstFrameOfBundle(BgStore.fileFor(this, key))
                    else -> android.graphics.BitmapFactory.decodeFile(BgStore.fileFor(this, key).absolutePath)
                }
            } catch (_: Throwable) {
                null
            }
            cur = BgStore.optFor(this, key)
        }

        var curKeys: List<String> =
            if (isNew) listOf(BgStore.ALL) else (editKeys ?: emptyList())

        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // 预览（首帧静态显示、拖动调区域；点「播放预览」按当前面板设置播放）
        val preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.MATRIX
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(240),
            )
            background = GradientDrawable().apply {
                setColor(Color.parseColor(chipBg))
                cornerRadius = dp(16).toFloat()
            }
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(16).toFloat())
                }
            }
            clipToOutline = true
            if (firstFrame != null) setImageBitmap(firstFrame)
        }
        var previewBmp: android.graphics.Bitmap? = firstFrame
        fun updatePreview() {
            val bmp = previewBmp ?: return
            if (preview.width <= 0) {
                preview.post { updatePreview() }
                return
            }
            val (vw, vh) = bgVisibleWindow(bmp.width, bmp.height, preview.width, preview.height, cur)
            val ox = cur.focusX / 1000f * (bmp.width - vw).coerceAtLeast(0f)
            val oy = cur.focusY / 1000f * (bmp.height - vh).coerceAtLeast(0f)
            val m = android.graphics.Matrix().apply {
                setRectToRect(
                    android.graphics.RectF(ox, oy, ox + vw, oy + vh),
                    android.graphics.RectF(0f, 0f, preview.width.toFloat(), preview.height.toFloat()),
                    android.graphics.Matrix.ScaleToFit.FILL,
                )
            }
            preview.imageMatrix = m
        }
        var lastX = 0f
        var lastY = 0f
        preview.setOnTouchListener { v, e ->
            val bmp = previewBmp
            when (e.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    lastX = e.x
                    lastY = e.y
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (bmp != null && v.width > 0 && v.height > 0) {
                        val (vw, vh) = bgVisibleWindow(bmp.width, bmp.height, v.width, v.height, cur)
                        val slackX = bmp.width - vw
                        val slackY = bmp.height - vh
                        if (slackX > 0.5f) {
                            val d = (e.x - lastX) / v.width * (vw / slackX) * 1000f
                            cur = cur.copy(focusX = (cur.focusX - d.toInt()).coerceIn(0, 1000))
                        }
                        if (slackY > 0.5f) {
                            val d = (e.y - lastY) / v.height * (vh / slackY) * 1000f
                            cur = cur.copy(focusY = (cur.focusY - d.toInt()).coerceIn(0, 1000))
                        }
                        updatePreview()
                    }
                    lastX = e.x
                    lastY = e.y
                    true
                }
                else -> false
            }
        }
        // ---- 预览播放：按面板当前设置（模式/速度/区域）播，和弹窗里同一条时间轴 ----
        val playHandler = android.os.Handler(android.os.Looper.getMainLooper())
        var playFrames: List<android.graphics.Bitmap>? = null
        var playDelays: IntArray? = null
        var playTimeline: GifTimeline? = null
        var playTimelineMode = -1
        var playEff = 0L
        var playLastReal = 0L
        var playRunning = false
        var playLoading = false
        var webpAnim: android.graphics.drawable.AnimatedImageDrawable? = null
        val playBtn = txt("播放预览", 15f, accent).apply {
            setPadding(dp(4), dp(10), dp(4), dp(2))
        }
        fun stopPreviewPlay() {
            playRunning = false
            playHandler.removeCallbacksAndMessages(null)
            try { webpAnim?.stop() } catch (_: Throwable) {}
            webpAnim = null
            preview.setImageDrawable(null)
            previewBmp = firstFrame
            if (firstFrame != null) preview.setImageBitmap(firstFrame)
            updatePreview()
            playBtn.text = "播放预览"
        }
        val playTick = object : Runnable {
            override fun run() {
                if (!playRunning) return
                val frames = playFrames ?: return
                val delays = playDelays ?: return
                val now = android.os.SystemClock.uptimeMillis()
                playEff += (now - playLastReal) * cur.speedPct / 100
                playLastReal = now
                if (playTimeline == null || playTimelineMode != cur.mode) {
                    playTimeline = GifTimeline(delays, cur.mode)
                    playTimelineMode = cur.mode
                }
                val tl = playTimeline ?: return
                val idx = tl.frameAt(playEff)
                val bmp = frames.getOrNull(idx)
                if (bmp != null && bmp !== previewBmp) {
                    previewBmp = bmp
                    preview.setImageBitmap(bmp)
                    updatePreview()
                }
                if (tl.isFinished(playEff)) {
                    stopPreviewPlay()
                    return
                }
                val nextEff = tl.nextChangeAt(playEff)
                if (nextEff == Long.MAX_VALUE) {
                    stopPreviewPlay()
                    return
                }
                val delayReal = ((nextEff - playEff) * 100 / cur.speedPct).coerceIn(2L, 60_000L) + 1
                playHandler.postDelayed(this, delayReal)
            }
        }
        fun beginPreviewTick() {
            playRunning = true
            playEff = 0L
            playTimeline = null
            playTimelineMode = -1
            playLastReal = android.os.SystemClock.uptimeMillis()
            playBtn.text = "停止预览"
            playHandler.removeCallbacks(playTick)
            playHandler.post(playTick)
        }
        fun startPreviewPlay() {
            if (playLoading) return
            if (playFrames != null) {
                beginPreviewTick()
                return
            }
            playLoading = true
            playBtn.text = "准备中…"
            val editKey = editKeys?.firstOrNull()
            Thread {
                val loaded = loadPreviewFrames(isNew, editKey)
                runOnUiThread {
                    playLoading = false
                    if (loaded.first == null || loaded.second == null) {
                        playBtn.text = "播放预览"
                        toast("这个背景没法预览播放")
                    } else {
                        playFrames = loaded.first
                        playDelays = loaded.second
                        beginPreviewTick()
                    }
                }
            }.start()
        }
        fun startWebpPreview() {
            try {
                val bytes = if (isNew) {
                    pendingBgBytes
                } else {
                    editKeys?.firstOrNull()?.let {
                        try { BgStore.fileFor(this@MainActivity, it).readBytes() }
                        catch (_: Throwable) { null }
                    }
                }
                if (bytes == null) {
                    toast("这个背景没法预览播放")
                    return
                }
                val d = android.graphics.ImageDecoder.decodeDrawable(
                    android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes)),
                )
                if (d is android.graphics.drawable.AnimatedImageDrawable) {
                    webpAnim = d
                    preview.setImageDrawable(d)
                    d.start()
                    playRunning = true
                    playBtn.text = "停止预览"
                } else {
                    toast("这个背景没法预览播放")
                }
            } catch (_: Throwable) {
                toast("这个背景没法预览播放")
            }
        }
        playBtn.setOnClickListener {
            when {
                playRunning -> stopPreviewPlay()
                kind == 2 -> startWebpPreview()
                else -> startPreviewPlay()
            }
        }

        content.addView(preview)
        if (kind == 1 || kind == 2) {
            content.addView(playBtn)
        }
        val zoomVal = txt("${cur.scalePct}%", 13f, accent).apply {
            gravity = android.view.Gravity.END
        }
        val zoomLabelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            addView(sheetLabel("显示区域（拖动预览图移动，滑杆放大）").apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(zoomVal.apply {
                setPadding(0, dp(14), 0, dp(7))
            })
        }
        content.addView(zoomLabelRow)
        val zoom = android.widget.SeekBar(this).apply {
            max = 100
            progress = cur.scalePct - 100
            progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(accent))
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(
                Color.parseColor(if (dark) "#3A3A3C" else "#D8D8DE"),
            )
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(accent))
            minHeight = dp(28)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                    zoomVal.text = "${100 + p}%"
                    if (fromUser) {
                        cur = cur.copy(scalePct = 100 + p)
                        updatePreview()
                    }
                }

                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            })
        }
        content.addView(zoom)

        content.addView(sheetLabel("用在哪个弹窗"))
        val targetRow = txt(bgTargetName(curKeys), 15f, accent).apply {
            setPadding(dp(4), dp(10), dp(4), dp(10))
            setOnClickListener {
                showBgTargetChooserDialog(curKeys) { keys ->
                    curKeys = keys
                    text = bgTargetName(keys)
                }
            }
        }
        content.addView(targetRow)

        content.addView(sheetLabel("字体颜色（只对这个背景的弹窗生效）"))
        content.addView(
            chipGroup(
                labels = listOf("系统", "白字", "黑字"),
                initial = cur.fontColor,
                enabled = { true },
                onPick = { i -> cur = cur.copy(fontColor = i) },
            ),
        )

        if (kind != 0) {
            content.addView(sheetLabel("播放完后"))
            content.addView(
                chipGroup(
                    labels = listOf("循环播放", "停在最后一帧", "往返播放"),
                    initial = cur.mode,
                    enabled = { i -> kind == 1 || kind == 3 || i != BgOpt.MODE_PINGPONG },
                    onPick = { i -> cur = cur.copy(mode = i) },
                ),
            )
            if (kind == 1 || kind == 3) {
                // 播放速度改滑杆：25%–300%，5% 一档，右侧实时数值
                val speedVal = txt(formatSpeed(cur.speedPct), 13f, accent).apply {
                    gravity = android.view.Gravity.END
                }
                val speedRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                    addView(sheetLabel("播放速度").apply {
                        layoutParams = LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
                        )
                    })
                    addView(speedVal.apply { setPadding(0, dp(14), 0, dp(7)) })
                }
                content.addView(speedRow)
                val speedBar = android.widget.SeekBar(this).apply {
                    max = 275
                    progress = (cur.speedPct - 25).coerceIn(0, 275)
                    progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(accent))
                    progressBackgroundTintList = android.content.res.ColorStateList.valueOf(
                        Color.parseColor(if (dark) "#3A3A3C" else "#D8D8DE"),
                    )
                    thumbTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(accent))
                    minHeight = dp(28)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                    setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(
                            sb: android.widget.SeekBar?,
                            p: Int,
                            fromUser: Boolean,
                        ) {
                            val pct = 25 + p
                            speedVal.text = formatSpeed(pct)
                            if (fromUser) cur = cur.copy(speedPct = pct)
                        }

                        override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                        override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
                    })
                }
                content.addView(speedBar)
            } else {
                content.addView(txt("WebP 动态图暂不支持变速和往返播放。", 12.5f, subC).apply {
                    setPadding(0, dp(8), 0, 0)
                })
            }
        }

        // 面板内容变高了（预览按弹窗比例 + 设置项变多），原对话框不滚动会把
        // 按钮顶出屏幕：内容包一层滚动并限高，按钮始终留在对话框底部
        val scrollContent = object : android.widget.ScrollView(this) {
            private var downInPreview = false

            override fun onInterceptTouchEvent(ev: android.view.MotionEvent): Boolean {
                // 手势落在预览图上时不拦截，留给预览自己拖动取景
                when (ev.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        val loc = IntArray(2)
                        // 必须用屏幕坐标和 rawX/rawY 比：窗口坐标差了对话框的窗口偏移，
                        // 之前用 getLocationInWindow 导致命中框整体错位、拖动老被判成滚动
                        preview.getLocationOnScreen(loc)
                        downInPreview = ev.rawX >= loc[0] && ev.rawX < loc[0] + preview.width &&
                            ev.rawY >= loc[1] && ev.rawY < loc[1] + preview.height
                        if (downInPreview) return false
                    }
                    android.view.MotionEvent.ACTION_MOVE -> if (downInPreview) return false
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> downInPreview = false
                }
                return super.onInterceptTouchEvent(ev)
            }
        }
        scrollContent.addView(content)
        val sheetDialog = MiuixUi.showDialog(
            this,
            title = "背景设置",
            content = scrollContent,
            positiveText = "保存",
            onPositive = {
                if (isNew) {
                    pendingBgOpt = cur
                    savePendingBg(curKeys)
                } else if (editKeys != null) {
                    if (curKeys != editKeys) BgStore.move(this, editKeys, curKeys)
                    for (k in curKeys) BgStore.setOpt(this, k, cur)
                    refreshBgUi()
                    syncAsync(showError = true)
                    toast("已保存，下次弹窗生效")
                }
            },
            negativeText = if (!isNew && viaLongPress) "关闭" else "取消",
        )
        sheetDialog.setOnDismissListener { stopPreviewPlay() }
        scrollContent.post {
            val maxH = (resources.displayMetrics.heightPixels * 0.58f).toInt()
            if (scrollContent.height > maxH) {
                scrollContent.layoutParams.height = maxH
                scrollContent.requestLayout()
            }
        }
        // 预览比例与真机弹窗卡片一致（宽高比 0.875），所见即所得
        preview.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val w = v.width
            if (w > 0) {
                val target = (w / POPUP_CARD_RATIO).toInt()
                if (kotlin.math.abs(target - v.layoutParams.height) > 1) {
                    v.layoutParams.height = target
                    v.requestLayout()
                    updatePreview()
                }
            }
        }
        preview.post { updatePreview() }
    }

    /** 「这张图用在哪个弹窗？」多选框：可同时选多台设备；「全部设备」与单台互斥。 */
    private fun showBgTargetChooserDialog(
        initialKeys: List<String>,
        onPick: (List<String>) -> Unit,
    ) {
        val groups = LinkedHashMap<String, List<String>>()
        groups["全部设备"] = listOf(BgStore.ALL)
        val grouped = LinkedHashMap<String, MutableList<String>>()
        for ((mac, label) in ConfSync.readWhitelist(prefs)) {
            val key = MacUtil.keyOf(mac) ?: continue
            val name = label.takeIf { it.isNotBlank() && it != mac } ?: mac
            grouped.getOrPut(name) { ArrayList() }.add(key)
        }
        for ((k, v) in grouped) groups[k] = v

        val selected = LinkedHashSet<String>()
        if (initialKeys.contains(BgStore.ALL)) {
            selected.add("全部设备")
        } else {
            for ((label, keys) in groups) {
                if (label != "全部设备" && keys.all { it in initialKeys }) selected.add(label)
            }
        }

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val marks = HashMap<String, TextView>()
        fun refreshMarks() {
            for ((label, mark) in marks) {
                val on = label in selected
                mark.text = if (on) "✓" else ""
                (mark.background as? GradientDrawable)?.let { g ->
                    g.setColor(Color.parseColor(if (on) "#3482FF" else "#00000000"))
                    g.setStroke(dp(1), Color.parseColor(if (on) "#3482FF" else "#C7C7CC"))
                }
            }
        }
        for ((label, keys) in groups) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(11), dp(4), dp(11))
                isClickable = true
            }
            val mark = TextView(this).apply {
                textSize = 12f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                    rightMargin = dp(12)
                }
            }
            marks[label] = mark
            row.addView(mark)
            val suffix = if (keys.any { BgStore.exists(this, it) } &&
                keys.none { it in initialKeys }
            ) {
                "（已有背景，将替换）"
            } else {
                ""
            }
            row.addView(txt(label + suffix, 16f, titleC))
            row.setOnClickListener {
                if (label == "全部设备") {
                    selected.clear()
                    selected.add(label)
                } else {
                    selected.remove("全部设备")
                    if (!selected.add(label)) selected.remove(label)
                }
                refreshMarks()
            }
            list.addView(row)
        }
        refreshMarks()
        MiuixUi.showDialog(
            this,
            title = "这张图用在哪个弹窗？",
            content = list,
            positiveText = "确定",
            onPositive = {
                if (selected.isEmpty()) {
                    toast("至少选一个")
                } else {
                    onPick(selected.flatMap { groups[it].orEmpty() })
                }
            },
            negativeText = "取消",
        )
    }

    /** 一组背景 key 的显示名：全部设备，或一台/多台设备的名称。 */
    private fun bgTargetName(keys: List<String>): String {
        if (keys.contains(BgStore.ALL)) return "全部设备"
        val wl = ConfSync.readWhitelist(prefs)
        val names = LinkedHashSet<String>()
        for (k in keys) {
            val mac = MacUtil.macOfKey(k) ?: continue
            val label = wl.firstOrNull { it.first == mac }?.second
            names.add(label?.takeIf { it.isNotBlank() && it != mac } ?: mac)
        }
        return when {
            names.isEmpty() -> "—"
            names.size == 1 -> names.first()
            else -> "${names.first()} 等 ${names.size} 台"
        }
    }

    private fun savePendingBg(keys: List<String>) {
        val bytes = pendingBgBytes
        val file = pendingBgFile
        if (bytes == null && file == null) return
        for (key in keys) {
            if (file != null) BgStore.saveFile(this, key, file, pendingBgMime)
            else BgStore.save(this, key, bytes!!, pendingBgMime)
            BgStore.setOpt(this, key, pendingBgOpt)
            if ((pendingBgMime == "image/gif" || pendingBgMime == "video/frames") && pendingBgFrames > 0) {
                BgStore.setFrameInfo(this, key, pendingBgFrames, pendingBgTotalMs)
            }
            if (pendingBgMime == "video/mp4") {
                if (pendingBgTotalMs > 0) BgStore.setFrameInfo(this, key, 0, pendingBgTotalMs)
                pendingBgPoster?.let { BgStore.savePoster(this, key, it) }
            }
        }
        pendingBgBytes = null
        if (file != null) {
            try { file.delete() } catch (_: Throwable) {}
            pendingBgFile = null
        }
        pendingBgFrames = 0
        pendingBgPoster = null
        refreshBgUi()
        syncAsync(showError = true)
        toast("背景已设置，下次弹窗生效")
    }

    /** 读入所选图片存进 pending：GIF/动态 WebP 原样（≤12MB）；静态图原文件原样存（不压缩）；视频走抽帧。成功返回 null。 */
    private fun processBgImage(uri: android.net.Uri): String? {
        pendingBgFile?.let { try { it.delete() } catch (_: Throwable) {} }
        pendingBgFile = null
        val resolverMime = try { contentResolver.getType(uri) } catch (_: Throwable) { null }
        if (resolverMime != null && resolverMime.startsWith("video/")) {
            return processBgVideo(uri)
        }
        return try {
            val raw = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return "读不到这个文件"
            if (raw.size > 64 * 1024 * 1024) return "文件太大了（超过 64MB）"
            // 选择器没报类型的视频：MP4/MOV 的 ftyp 魔数在第 5~8 字节
            if (raw.size >= 8 && raw.copyOfRange(4, 8).toString(Charsets.US_ASCII) == "ftyp") {
                return processBgVideo(uri)
            }
            // 动态照片：本体是 JPG、里面内嵌一段 MP4（小米/谷歌动态照片都是这个结构）。
            // 当普通 JPG 存就永远是静态图；内嵌视频抽出来按原帧率抽帧播放
            if (raw.size >= 4 && raw[0] == 0xFF.toByte() && raw[1] == 0xD8.toByte()) {
                val mp4 = extractEmbeddedMp4(raw)
                if (mp4 != null) {
                    val tmp = java.io.File(cacheDir, "motion_photo.mp4")
                    tmp.writeBytes(mp4)
                    val err = processBgVideoFile(tmp)
                    tmp.delete()
                    if (err == null) pendingBgNote = "识别为动态照片（JPG 内嵌视频），已按原帧率抽帧"
                    return err
                }
            }
            val isGif = GifDecoder.isGif(raw)
            if (isGif) {
                if (raw.size > 12 * 1024 * 1024) return "GIF 太大了（超过 12MB），换个小一点的"
                pendingBgBytes = raw
                pendingBgMime = "image/gif"
                // 只扫结构数帧数/总时长（不做全量像素解码，导入快）；帧数也当场验证是不是真动图
                val (fc, totalMs) = try { GifDecoder.scanInfo(raw) } catch (_: Throwable) { 0 to 0L }
                pendingBgFrames = fc
                pendingBgTotalMs = totalMs
                return null
            }
            // 动态 WebP（微信/QQ 里存的「GIF」很多是这个）：RIFF/WEBP 头 + ANIM 块，原样存
            val head = raw.copyOfRange(0, minOf(raw.size, 65536)).toString(Charsets.ISO_8859_1)
            val isAnimWebP = raw.size >= 21 &&
                raw.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" &&
                raw.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP" &&
                head.contains("ANIM")
            if (isAnimWebP) {
                if (raw.size > 12 * 1024 * 1024) return "动态图太大了（超过 12MB），换个小一点的"
                pendingBgBytes = raw
                pendingBgMime = "image/webp"
                return null
            }
            // 静态图原样存：不解码、不缩放、不重压，画质零损失；
            // 显示时弹窗端按卡片大小裁切取景（只裁切）
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return "这张图片解码失败"
            pendingBgBytes = raw
            pendingBgMime = bounds.outMimeType ?: "image/jpeg"
            null
        } catch (t: Throwable) {
            "处理失败：${t.message ?: "未知错误"}"
        }
    }

    /**
     * 从动态照片 JPG 里抽出内嵌的 MP4（抽不到返回 null）。
     * 先试 XMP 的 MicroVideoOffset（视频字节数，从文件尾倒数）；再退回从尾部扫 ftyp 盒。
     */
    private fun extractEmbeddedMp4(jpeg: ByteArray): ByteArray? {
        try {
            val head = jpeg.copyOfRange(0, minOf(jpeg.size, 128 * 1024))
                .toString(Charsets.US_ASCII)
            val m = Regex("MicroVideoOffset=\"(\\d+)\"").find(head)
            if (m != null) {
                val len = m.groupValues[1].toLongOrNull() ?: 0L
                val start = jpeg.size - len
                if (len > 1000 && start > 0 && start < jpeg.size - 8 &&
                    jpeg.copyOfRange(start.toInt() + 4, start.toInt() + 8)
                        .toString(Charsets.US_ASCII) == "ftyp"
                ) {
                    return jpeg.copyOfRange(start.toInt(), jpeg.size)
                }
            }
        } catch (_: Throwable) {
        }
        val brands = setOf("isom", "iso2", "iso5", "iso6", "mp41", "mp42", "avc1", "M4V ", "dash")
        var i = jpeg.size - 4
        while (i >= 8) {
            if (jpeg[i] == 0x66.toByte() && jpeg[i + 1] == 0x74.toByte() &&
                jpeg[i + 2] == 0x79.toByte() && jpeg[i + 3] == 0x70.toByte()
            ) {
                val boxStart = i - 4
                val boxSize = ((jpeg[boxStart].toInt() and 0xFF) shl 24) or
                    ((jpeg[boxStart + 1].toInt() and 0xFF) shl 16) or
                    ((jpeg[boxStart + 2].toInt() and 0xFF) shl 8) or
                    (jpeg[boxStart + 3].toInt() and 0xFF)
                val brand = if (i + 8 <= jpeg.size) {
                    jpeg.copyOfRange(i + 4, i + 8).toString(Charsets.US_ASCII)
                } else {
                    ""
                }
                if (boxSize >= 16 && boxStart + boxSize <= jpeg.size && brand in brands) {
                    return jpeg.copyOfRange(boxStart, jpeg.size)
                }
            }
            i--
        }
        return null
    }

    /** 已存原视频的第一帧（海报丢了时兜底现抓）。 */
    private fun firstFrameOfVideoFile(file: java.io.File): android.graphics.Bitmap? {
        val r = android.media.MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            val rot = r.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION,
            )?.toIntOrNull() ?: 0
            var bmp = r.getFrameAtTime(
                0L, android.media.MediaMetadataRetriever.OPTION_CLOSEST,
            ) ?: return null
            if (rot != 0) {
                val m = android.graphics.Matrix().apply { postRotate(rot.toFloat()) }
                bmp = android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }
            bmp
        } catch (_: Throwable) {
            null
        } finally {
            try { r.release() } catch (_: Throwable) {}
        }
    }

    /**
     * 视频导入：应用进程内抽帧（MediaCodec 硬解 → retriever 抓帧兜底），
     * 抽帧产物 hook 侧逐帧播放。
     */
    private fun processBgVideo(uri: android.net.Uri): String? {
        // 抽帧在应用进程里做：弹窗所在的蓝牙进程连媒体解析服务都被系统挡住
        // （日志实证 Failed to instantiate extractor），视频解码只能放在这边
        val codecErr = extractVideoFramesCodec { ex -> ex.setDataSource(this, uri, null) }
        if (codecErr == null) return null
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, uri)
            extractVideoFrames(retriever)
        } catch (t: Throwable) {
            "视频处理失败：${t.message ?: "未知错误"}"
        } finally {
            try { retriever.release() } catch (_: Throwable) {}
        }
    }

    /** 本地文件版（动态照片内嵌 MP4、已存直放视频迁移都走这里）。 */
    private fun processBgVideoFile(file: java.io.File): String? {
        val codecErr = extractVideoFramesCodec { ex -> ex.setDataSource(file.absolutePath) }
        if (codecErr == null) return null
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            extractVideoFrames(retriever)
        } catch (t: Throwable) {
            "视频处理失败：${t.message ?: "未知错误"}"
        } finally {
            try { retriever.release() } catch (_: Throwable) {}
        }
    }

    /**
     * 抽好的帧打包进 pending（视频/动态照片两条抽帧路径共用）。成功返回 null。
     * 帧已逐个落在 framesDir 里，这里流式拼成 ZIP 文件，内存里不留整包——
     * 原画质帧包可达上百 MB，整包进内存必爆（v1.36 实测 160MB 分配失败）。
     */
    private fun finishVideoFrames(
        framesDir: java.io.File,
        frameCount: Int,
        fps: Int,
        poster: android.graphics.Bitmap?,
    ): String? {
        if (frameCount <= 0) return "这个视频读不到画面"
        pendingBgPoster = poster
        if (frameCount == 1) {
            pendingBgBytes = java.io.File(framesDir, "f000.jpg").readBytes()
            pendingBgMime = "image/jpeg"
            pendingBgFrames = 0
            pendingBgTotalMs = 0L
            try { framesDir.deleteRecursively() } catch (_: Throwable) {}
            return null
        }
        val outFile = java.io.File(cacheDir, "pending_bg.zip")
        try {
            java.util.zip.ZipOutputStream(
                java.io.BufferedOutputStream(java.io.FileOutputStream(outFile)),
            ).use { z ->
                z.setLevel(0)
                for (i in 0 until frameCount) {
                    val f = java.io.File(framesDir, "f%03d.jpg".format(i))
                    z.putNextEntry(java.util.zip.ZipEntry("f%03d.jpg".format(i)))
                    f.inputStream().use { it.copyTo(z, 64 * 1024) }
                    z.closeEntry()
                }
                z.putNextEntry(java.util.zip.ZipEntry("meta.txt"))
                z.write("fps=$fps\n".toByteArray())
                z.closeEntry()
            }
        } catch (t: Throwable) {
            return "视频打包失败：${t.message ?: "未知错误"}"
        }
        try { framesDir.deleteRecursively() } catch (_: Throwable) {}
        pendingBgFile = outFile
        pendingBgMime = "video/frames"
        pendingBgFrames = frameCount
        pendingBgTotalMs = frameCount * (1000L / fps)
        return null
    }

    /** MediaCodec 硬件顺序解码抽帧：不逐帧 seek，整段一次解完，比抓帧快一个量级。 */
    private fun extractVideoFramesCodec(
        configure: (android.media.MediaExtractor) -> Unit,
    ): String? {
        val extractor = android.media.MediaExtractor()
        var codec: android.media.MediaCodec? = null
        var encPool: java.util.concurrent.ExecutorService? = null
        try {
            configure(extractor)
            var track = -1
            var mime = ""
            var durationUs = 0L
            var rotation = 0
            var codedW = 0
            var codedH = 0
            var nativeFps = 30
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val m = f.getString(android.media.MediaFormat.KEY_MIME) ?: continue
                if (m.startsWith("video/")) {
                    track = i
                    mime = m
                    durationUs = try {
                        f.getLong(android.media.MediaFormat.KEY_DURATION)
                    } catch (_: Throwable) { 0L }
                    rotation = try {
                        f.getInteger(android.media.MediaFormat.KEY_ROTATION)
                    } catch (_: Throwable) { 0 }
                    codedW = try {
                        f.getInteger(android.media.MediaFormat.KEY_WIDTH)
                    } catch (_: Throwable) { 0 }
                    codedH = try {
                        f.getInteger(android.media.MediaFormat.KEY_HEIGHT)
                    } catch (_: Throwable) { 0 }
                    nativeFps = try {
                        f.getInteger(android.media.MediaFormat.KEY_FRAME_RATE)
                    } catch (_: Throwable) { 30 }
                    break
                }
            }
            if (track < 0) return "找不到视频轨"
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val swapped = rotation == 90 || rotation == 270
            val estW = if (swapped) codedH else codedW
            val estH = if (swapped) codedW else codedH
            // 原画质：帧按源分辨率抽，不缩放；预算只管总帧数/总像素（防超长视频爆内存）
            val estPx = if (estW > 0 && estH > 0) estW * estH else 1280 * 720
            // 按原帧率抽：从源帧率起步，只有帧数/总像素超预算才逐档降
            // （上限 8 秒、300 帧、5 亿像素；短视频全帧原样抽）
            val usableUs = if (durationUs > 0) minOf(durationUs, 8_000_000L) else 8_000_000L
            val nativeClamped = nativeFps.coerceIn(1, 60)
            val ladder = (listOf(nativeClamped) +
                listOf(48, 40, 30, 24, 20, 15, 12, 10, 8).filter { it < nativeClamped })
                .distinct()
            var fps = ladder.last()
            for (cand in ladder) {
                val n = (usableUs / 1_000_000.0 * cand).toInt().coerceAtLeast(1)
                if (n <= 300 && n.toLong() * estPx <= 500_000_000L) {
                    fps = cand
                    break
                }
            }
            val takeAll = fps >= nativeClamped
            val intervalUs = 1_000_000L / fps
            codec = android.media.MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            // JPEG 编码并行化：解码在本线程顺序走，最耗时的编码交给 4 个线程同时压；
            // 在途位图用信号量限住防爆内存；结果带序号收集，解码结束后按序组装
            val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
            encPool = pool
            val encSlots = java.util.concurrent.Semaphore(5)
            val encErr = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
            val encDone = java.util.concurrent.atomic.AtomicInteger(0)
            val expectedTotal = maxOf(
                1,
                (usableUs / 1_000_000.0 * (if (takeAll) nativeClamped else fps)).toInt(),
            )
            var frameIdx = 0
            var poster: android.graphics.Bitmap? = null
            // 帧逐个落临时文件、最后流式拼包：原画质帧包可达上百 MB，整包进内存会爆
            val framesDir = java.io.File(cacheDir, "bg_frames_tmp").apply {
                try { deleteRecursively() } catch (_: Throwable) {}
                mkdirs()
            }
            var nextWantedUs = 0L
            var firstTakenPts = -1L
            var lastTakenPts = 0L
            var inputDone = false
            var outputDone = false
            val info = android.media.MediaCodec.BufferInfo()
            val deadline = android.os.SystemClock.elapsedRealtime() + 45_000L
            while (!outputDone) {
                if (android.os.SystemClock.elapsedRealtime() > deadline) return "视频解码超时"
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx) ?: return "解码器异常"
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
                val outIdx = codec.dequeueOutputBuffer(info, 10_000L)
                if (outIdx >= 0) {
                    val eos = info.flags and
                        android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val pts = info.presentationTimeUs
                    if (info.size > 0 && pts >= nextWantedUs && pts <= usableUs) {
                        val img = codec.getOutputImage(outIdx)
                        if (img != null) {
                            var bmp = yuvImageToBitmap(img)
                            img.close()
                            if (rotation != 0) {
                                val mt = android.graphics.Matrix().apply {
                                    postRotate(rotation.toFloat())
                                }
                                bmp = android.graphics.Bitmap.createBitmap(
                                    bmp, 0, 0, bmp.width, bmp.height, mt, true,
                                )
                            }
                            if (poster == null) poster = bmp
                            val encIdx = frameIdx++
                            val encBmp = bmp
                            encSlots.acquire()
                            pool.execute {
                                try {
                                    java.io.File(framesDir, "f%03d.jpg".format(encIdx))
                                        .outputStream().use {
                                            encBmp.compress(
                                                android.graphics.Bitmap.CompressFormat.JPEG,
                                                100, it,
                                            )
                                        }
                                    if (encIdx != 0) {
                                        try { encBmp.recycle() } catch (_: Throwable) {}
                                    }
                                    val d = encDone.incrementAndGet()
                                    reportImportProgress(minOf(99, d * 100 / expectedTotal))
                                } catch (t: Throwable) {
                                    encErr.compareAndSet(null, t)
                                } finally {
                                    encSlots.release()
                                }
                            }
                            if (firstTakenPts < 0) firstTakenPts = pts
                            lastTakenPts = pts
                            if (takeAll) {
                                nextWantedUs = pts + 1
                            } else {
                                while (nextWantedUs <= pts) nextWantedUs += intervalUs
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (eos || pts > usableUs + intervalUs) outputDone = true
                }
            }
            pool.shutdown()
            if (!pool.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS)) {
                return "视频编码超时"
            }
            encErr.get()?.let { return "视频编码失败：${it.message ?: "未知错误"}" }
            // 全帧抽时用实际帧间隔反推帧率（变帧率源也贴近原速）
            val fpsEff = if (takeAll && frameIdx >= 2 && lastTakenPts > firstTakenPts) {
                ((frameIdx - 1) * 1_000_000L / (lastTakenPts - firstTakenPts))
                    .toInt().coerceIn(1, 120)
            } else {
                fps
            }
            return finishVideoFrames(framesDir, frameIdx, fpsEff, poster)
        } catch (t: Throwable) {
            return "视频解码失败：${t.message ?: "未知错误"}"
        } finally {
            try { encPool?.shutdownNow() } catch (_: Throwable) {}
            try { codec?.stop() } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
            try { extractor.release() } catch (_: Throwable) {}
        }
    }

    /** 把一个平面的裁剪区去步长拷成紧凑字节数组（pixelStride=1 时整行 bulk 拷贝）。 */
    private fun copyPlane(
        plane: android.media.Image.Plane,
        x0: Int,
        y0: Int,
        w: Int,
        h: Int,
    ): ByteArray {
        val out = ByteArray(w * h)
        val buf = plane.buffer.duplicate()
        val rs = plane.rowStride
        val ps = plane.pixelStride
        if (ps == 1) {
            for (row in 0 until h) {
                buf.position((y0 + row) * rs + x0)
                buf.get(out, row * w, w)
            }
        } else {
            for (row in 0 until h) {
                val base = (y0 + row) * rs + x0 * ps
                var dst = row * w
                for (col in 0 until w) {
                    out[dst++] = buf.get(base + col * ps)
                }
            }
        }
        return out
    }

    // YUV→RGB 查表：与逐像素整数公式逐位一致，省掉每像素的乘法，转换快数倍
    private val yuvTabY = IntArray(256) { 1192 * (it - 16).coerceAtLeast(0) }
    private val yuvTabRv = IntArray(256) { 1634 * (it - 128) }
    private val yuvTabGv = IntArray(256) { -833 * (it - 128) }
    private val yuvTabGu = IntArray(256) { -400 * (it - 128) }
    private val yuvTabBu = IntArray(256) { 2066 * (it - 128) }

    /**
     * MediaCodec 输出的 YUV_420_888 转 Bitmap（整数 BT.601，颜色公式与旧版一致）。
     * 长边封顶 1600 采样转换：弹窗卡片只有 1440 宽，再大的帧肉眼无差别，却让帧包
     * 体积、传输与弹窗端解码慢好几倍（掉帧主因之一）；整行 bulk 拷贝 + 数组内循环。
     */
    private fun yuvImageToBitmap(image: android.media.Image): android.graphics.Bitmap {
        val crop = image.cropRect
        val w = crop.width()
        val h = crop.height()
        val step = maxOf(1, (maxOf(w, h) + 1599) / 1600)
        val outW = (w + step - 1) / step
        val outH = (h + step - 1) / step
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        val yArr = copyPlane(image.planes[0], crop.left, crop.top, w, h)
        val uArr = copyPlane(image.planes[1], crop.left / 2, crop.top / 2, cw, ch)
        val vArr = copyPlane(image.planes[2], crop.left / 2, crop.top / 2, cw, ch)
        val pixels = IntArray(outW * outH)
        for (oy in 0 until outH) {
            val sy = oy * step
            val yRow = sy * w
            val cRow = (sy / 2) * cw
            var dst = oy * outW
            var sx = 0
            for (ox in 0 until outW) {
                val yv = yArr[yRow + sx].toInt() and 0xFF
                val ci = cRow + sx / 2
                val ur = uArr[ci].toInt() and 0xFF
                val vr = vArr[ci].toInt() and 0xFF
                val y1192 = yuvTabY[yv]
                val r = ((y1192 + yuvTabRv[vr]) shr 10).coerceIn(0, 255)
                val g = ((y1192 + yuvTabGv[vr] + yuvTabGu[ur]) shr 10).coerceIn(0, 255)
                val b = ((y1192 + yuvTabBu[ur]) shr 10).coerceIn(0, 255)
                pixels[dst++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                sx += step
            }
        }
        val bmp = android.graphics.Bitmap.createBitmap(
            outW, outH, android.graphics.Bitmap.Config.ARGB_8888,
        )
        bmp.setPixels(pixels, 0, outW, 0, 0, outW, outH)
        return bmp
    }

    /** retriever 已设好数据源后的抽帧打包主体（硬解失败时的回退路径）。成功返回 null。 */
    private fun extractVideoFrames(retriever: android.media.MediaMetadataRetriever): String? {
        try {
            val durationMs = retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_DURATION,
            )?.toLongOrNull() ?: return "读不到这个视频的信息"
            if (durationMs <= 0) return "读不到这个视频的信息"
            val rotation = retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION,
            )?.toIntOrNull() ?: 0
            val vw = retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH,
            )?.toIntOrNull() ?: 0
            val vh = retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT,
            )?.toIntOrNull() ?: 0
            val swapped = rotation == 90 || rotation == 270
            val estW = if (swapped) vh else vw
            val estH = if (swapped) vw else vh
            val estPx = if (estW > 0 && estH > 0) estW * estH else 1280 * 720
            val usableMs = minOf(durationMs, 8000L)
            var fps = 8
            for (cand in listOf(60, 48, 30, 24, 20, 15, 12, 10, 8)) {
                val n = (usableMs * cand / 1000).toInt().coerceAtLeast(1)
                if (n <= 300 && n.toLong() * estPx <= 500_000_000L) {
                    fps = cand
                    break
                }
            }
            val frameCount = (usableMs * fps / 1000).toInt().coerceAtLeast(1)
            val framesDir = java.io.File(cacheDir, "bg_frames_tmp").apply {
                try { deleteRecursively() } catch (_: Throwable) {}
                mkdirs()
            }
            var taken = 0
            var poster: android.graphics.Bitmap? = null
            for (i in 0 until frameCount) {
                val timeUs = i * 1_000_000L / fps
                var bmp = retriever.getFrameAtTime(
                    timeUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST,
                ) ?: continue
                if (rotation != 0) {
                    val m = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
                    bmp = android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                }
                val longEdge = maxOf(bmp.width, bmp.height)
                if (longEdge > 1600) {
                    val sc = 1600f / longEdge
                    val scaled = android.graphics.Bitmap.createScaledBitmap(
                        bmp,
                        (bmp.width * sc).toInt().coerceAtLeast(1),
                        (bmp.height * sc).toInt().coerceAtLeast(1),
                        true,
                    )
                    if (scaled !== bmp) {
                        try { bmp.recycle() } catch (_: Throwable) {}
                        bmp = scaled
                    }
                }
                if (poster == null) poster = bmp
                java.io.File(framesDir, "f%03d.jpg".format(taken)).outputStream().use {
                    bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 100, it)
                }
                if (bmp !== poster) {
                    try { bmp.recycle() } catch (_: Throwable) {}
                }
                taken++
                reportImportProgress(minOf(99, taken * 100 / frameCount))
            }
            return finishVideoFrames(framesDir, taken, fps, poster)
        } catch (t: Throwable) {
            return "视频处理失败：${t.message ?: "未知错误"}"
        }
    }

    /** 视频帧包的第一帧（缩略图/预览用）。 */
    private fun firstFrameOfBundle(file: java.io.File): android.graphics.Bitmap? = try {
        java.util.zip.ZipFile(file).use { z ->
            val e = z.entries().asSequence()
                .filter { it.name.endsWith(".jpg") }
                .minByOrNull { it.name } ?: return null
            z.getInputStream(e).use { android.graphics.BitmapFactory.decodeStream(it) }
        }
    } catch (_: Throwable) {
        null
    }

    private fun clearBgImage(keys: List<String>) {
        MiuixUi.showDialog(
            this,
            title = "删除这张背景？",
            message = "「${BgStore.labelFor(this, keys.first())}」的弹窗背景将被删除，恢复原来的样子。",
            positiveText = "删除",
            onPositive = {
                for (key in keys) BgStore.clear(this, key)
                refreshBgUi()
                syncAsync(showError = true)
                toast("已删除")
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
                val label = HookApps.LABELS[pkg] ?: pkg
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
            .append(" battery_sync=").append(v.batterySync)
            .append(" battery_keep=").append(v.batteryKeep)
            .append(" debug=").append(v.debug)
            .append(" bg_map=").append(FilterConfig.renderBgMap(v.bgMap))
            .append(" bg_keys=").append(BgStore.keys(this).joinToString("+"))
            .append(" whitelist=").append(v.whitelist.sorted().joinToString(","))
            .append(" bypass_until=").append(v.bypassUntilMs)
            .append(" now=").append(System.currentTimeMillis()).append("\n\n")
        val installedList = HookApps.PACKAGES.sorted().joinToString(", ") { pkg ->
            val ok = try { packageManager.getPackageInfo(pkg, 0); true } catch (_: Throwable) { false }
            "$pkg=${if (ok) "installed" else "missing"}"
        }
        sb.append("targets: ").append(installedList).append("\n\n")
        val (_, conf) = RootUtil.execSu("cat ${ConfSync.CONF_PATH} 2>/dev/null; ls -l ${ConfSync.CONF_PATH} 2>&1")
        sb.append("== conf file ==\n").append(conf).append("\n\n")
        val (_, ps) = RootUtil.execSu("ps -A -o PID,NAME 2>/dev/null | grep -E 'bluetooth|settings|earphone|milink|mi_connect' | head -30")
        sb.append("== processes ==\n").append(ps).append("\n\n")
        val (_, lc) = RootUtil.execSu("logcat -d -s MiConnectFilter:* 2>/dev/null | tail -n 800")
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
            val installed = HookApps.PACKAGES.count { pkg ->
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
                vTargets.text = "已装 $installed/${HookApps.PACKAGES.size}"
            }
        }.start()
    }

    private fun syncAsync(showError: Boolean) {
        Thread {
            val ok = ConfSync.sync(this)
            if (!ok && showError) runOnUiThread { toast("同步失败：需要 Root 授权") }
        }.start()
    }

    // ------------------------------------------------------------ 检查更新（GitHub Releases）

    private fun currentVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (_: Throwable) {
        ""
    }

    /** 点分数字逐段比较：a 比 b 新返回 1，旧返回 -1，一样返回 0。 */
    private fun compareVersions(a: String, b: String): Int {
        val pa = a.split('.').map { it.toIntOrNull() ?: 0 }
        val pb = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return if (x > y) 1 else -1
        }
        return 0
    }

    /**
     * 查 GitHub 仓库最新 Release。auto=true 是打开应用时自动查：1 小时内查过就跳过，
     * 有新版才弹窗，没有或失败都静默；手动点「检查更新」则无论结果都给反馈。
     */
    @Volatile private var updateCheckRunning = false
    @Volatile private var updateCheckManual = false
    private var updateSpinner: android.app.Dialog? = null

    private fun showUpdateSpinner() {
        if (updateSpinner != null) return
        val bar = android.widget.ProgressBar(this).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(8)
                bottomMargin = dp(4)
            }
        }
        updateSpinner = MiuixUi.showDialog(
            this,
            title = "检查更新",
            message = null,
            content = bar,
            positiveText = null,
            negativeText = null,
        ).apply { setCancelable(false) }
    }

    private fun dismissUpdateSpinner() {
        try { updateSpinner?.dismiss() } catch (_: Throwable) {}
        updateSpinner = null
    }

    private fun checkUpdate(auto: Boolean) {
        val now = System.currentTimeMillis()
        if (auto && now - prefs.getLong("update_checked_at", 0L) < 3_600_000L) return
        synchronized(this) {
            if (updateCheckRunning) {
                // 已有检查在跑（多半是打开应用时的自动检查）：手动点击并到那次上，
                // 结果只出一个弹窗，避免两个弹窗叠加闪烁
                if (!auto) {
                    updateCheckManual = true
                    showUpdateSpinner()
                }
                return
            }
            updateCheckRunning = true
            updateCheckManual = !auto
        }
        prefs.edit().putLong("update_checked_at", now).apply()
        if (!auto) showUpdateSpinner()
        Thread {
            var latest: String? = null
            var notes: String? = null
            var pageUrl: String? = null
            try {
                val conn = java.net.URL(
                    "https://api.github.com/repos/Wang2035/MiConnectFilter/releases/latest",
                ).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("User-Agent", "MiaoXiangKuaiLian-Android")
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                val text = conn.inputStream.bufferedReader().readText()
                val json = org.json.JSONObject(text)
                latest = json.optString("tag_name").trim().removePrefix("v").removePrefix("V")
                notes = json.optString("body")
                pageUrl = json.optString("html_url")
            } catch (_: Throwable) {
                latest = null
            }
            val cur = currentVersionName()
            val lat = latest
            runOnUiThread {
                synchronized(this) { updateCheckRunning = false }
                dismissUpdateSpinner()
                val manual = updateCheckManual
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (lat.isNullOrBlank()) {
                    if (manual) toast("检查更新失败，请稍后再试")
                } else if (compareVersions(lat, cur) > 0) {
                    val msg = StringBuilder("最新版本 v$lat（当前 v$cur）")
                    val n = notes?.trim().orEmpty()
                    if (n.isNotEmpty()) {
                        msg.append("\n\n").append(if (n.length > 600) n.substring(0, 600) + "…" else n)
                    }
                    val url = pageUrl
                        ?: "https://github.com/Wang2035/MiConnectFilter/releases/latest"
                    window.decorView.post {
                        if (isFinishing || isDestroyed) return@post
                        val dlg = MiuixUi.showDialog(
                            this,
                            title = "发现新版本",
                            message = msg.toString(),
                            positiveText = "去下载",
                            negativeText = "以后再说",
                            onPositive = {
                                try {
                                    startActivity(
                                        android.content.Intent(
                                            android.content.Intent.ACTION_VIEW,
                                            android.net.Uri.parse(url),
                                        ),
                                    )
                                } catch (_: Throwable) {
                                    toast("没有可用的浏览器")
                                }
                            },
                        )
                        dlg.setCanceledOnTouchOutside(false)
                    }
                } else if (manual) {
                    toast("已是最新版本（v$cur）")
                }
            }
        }.start()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
