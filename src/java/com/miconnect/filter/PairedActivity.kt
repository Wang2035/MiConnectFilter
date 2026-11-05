package com.miconnect.filter

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** 从系统已配对蓝牙设备里挑耳机加入白名单（点一下即添加）。Miuix 风格同主页。 */
class PairedActivity : Activity() {

    private var dark = false
    private lateinit var bg: String
    private lateinit var cardBg: String
    private lateinit var titleC: String
    private lateinit var subC: String
    private lateinit var listBox: LinearLayout

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        bg = if (dark) "#000000" else "#F5F5F7"
        cardBg = if (dark) "#1C1C1E" else "#FFFFFF"
        titleC = if (dark) "#F5F5F5" else "#1A1A1A"
        subC = if (dark) "#98989F" else "#8E8E93"
        window.statusBarColor = Color.parseColor(bg)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), statusBarHeight() + dp(14), dp(16), dp(20))
            setBackgroundColor(Color.parseColor(bg))
        }
        root.addView(TextView(this).apply {
            text = "已配对设备"
            textSize = 28f
            setTextColor(Color.parseColor(titleC))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "点一台加入白名单"
            textSize = 13f
            setTextColor(Color.parseColor(subC))
        })
        val cardView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.parseColor(cardBg))
                cornerRadius = dp(24).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(14) }
        }
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cardView.addView(listBox)
        root.addView(cardView)
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(bg))
            addView(root)
        })

        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission("android.permission.BLUETOOTH_CONNECT") != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf("android.permission.BLUETOOTH_CONNECT"), 42)
        } else {
            loadDevices()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) loadDevices()
        else {
            toast("没有蓝牙权限，改用主页手动添加 MAC")
            finish()
        }
    }

    private fun loadDevices() {
        listBox.removeAllViews()
        val devices: List<BluetoothDevice> = try {
            BluetoothAdapter.getDefaultAdapter()?.bondedDevices?.toList() ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
        if (devices.isEmpty()) {
            listBox.addView(hint("没有读到已配对设备（蓝牙没开，或没有配对记录）。"))
            return
        }
        val entries = devices.mapNotNull { dev ->
            val name = try { DeviceNames.displayName(dev) } catch (_: Throwable) { null }
            val mac = MacUtil.normalize(try { dev.address } catch (_: Throwable) { null }) ?: return@mapNotNull null
            PairedEntry(name, mac)
        }.sortedBy { it.name ?: it.mac }
        val whitelist = try {
            ConfSync.readWhitelist(ConfSync.prefs(this)).map { it.first }.toSet()
        } catch (_: Throwable) { emptySet() }
        val accentC = "#3482FF"
        val greenC = if (dark) "#30D158" else "#34C759"
        val orangeC = if (dark) "#FF9F0A" else "#FF9500"
        for (group in PairedGrouping.group(entries)) {
            val name = group.first().name
            val macs = group.map { it.mac }
            val status = PairedGrouping.status(macs, whitelist)
            val rowView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(12), 0, dp(12))
                val texts = LinearLayout(this@PairedActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    addView(TextView(this@PairedActivity).apply {
                        text = if (name.isNullOrBlank()) "未命名设备" else name
                        textSize = 16f
                        setTextColor(Color.parseColor(
                            if (status == PairedGrouping.WlStatus.ALL) subC else titleC))
                    })
                    addView(TextView(this@PairedActivity).apply {
                        text = if (macs.size == 1) macs.single()
                            else macs.joinToString(" · ") + "\n同一设备的 ${macs.size} 个地址，点一次全部加入"
                        textSize = 13f
                        setTextColor(Color.parseColor(subC))
                    })
                }
                addView(texts)
                addView(TextView(this@PairedActivity).apply {
                    text = when (status) {
                        PairedGrouping.WlStatus.ALL -> "✓ 已加入"
                        PairedGrouping.WlStatus.PARTIAL -> "差 ${macs.size - macs.count { it in whitelist }} 个"
                        PairedGrouping.WlStatus.NONE -> "加入"
                    }
                    textSize = 14f
                    setTextColor(Color.parseColor(when (status) {
                        PairedGrouping.WlStatus.ALL -> greenC
                        PairedGrouping.WlStatus.PARTIAL -> orangeC
                        PairedGrouping.WlStatus.NONE -> accentC
                    }))
                    setPadding(dp(10), 0, 0, 0)
                })
                setOnClickListener {
                    if (status == PairedGrouping.WlStatus.ALL) {
                        MiuixUi.showDialog(
                            this@PairedActivity,
                            title = "从白名单移除？",
                            message = (name ?: "") + "\n" + macs.joinToString(" · "),
                            positiveText = "移除",
                            onPositive = {
                                if (ConfSync.removeFromWhitelist(this@PairedActivity, macs) > 0) {
                                    Thread { ConfSync.sync(this@PairedActivity) }.start()
                                    toast("已移除：${name ?: ""}")
                                    loadDevices()
                                }
                            },
                            negativeText = "取消",
                        )
                        return@setOnClickListener
                    }
                    var added = 0
                    for (m in macs) {
                        if (ConfSync.addToWhitelist(this@PairedActivity, m, name ?: "")) added++
                    }
                    if (added > 0) {
                        Thread { ConfSync.sync(this@PairedActivity) }.start()
                        toast(
                            if (macs.size > 1) "已加入白名单：${name ?: ""}（补齐 ${macs.size} 个地址）"
                            else "已加入白名单：${name ?: macs.single()}"
                        )
                        loadDevices()
                    } else {
                        toast("该设备已在白名单里")
                    }
                }
            }
            listBox.addView(rowView)
        }
        listBox.addView(hint("没有你的耳机？先回主页暂停拦截，完成配对再来添加。"))
    }

    private fun hint(s: String): TextView = TextView(this).apply {
        text = s
        textSize = 13f
        setTextColor(Color.parseColor(subC))
        setPadding(0, dp(10), 0, dp(10))
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
