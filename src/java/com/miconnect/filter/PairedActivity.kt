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
        val sorted = devices.sortedBy { try { DeviceNames.displayName(it) ?: it.address } catch (_: Throwable) { "" } }
        for (dev in sorted) {
            val name = try { DeviceNames.displayName(dev) } catch (_: Throwable) { null }
            val mac = MacUtil.normalize(try { dev.address } catch (_: Throwable) { null }) ?: continue
            val rowView = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(12), 0, dp(12))
                addView(TextView(this@PairedActivity).apply {
                    text = if (name.isNullOrBlank()) "未命名设备" else name
                    textSize = 16f
                    setTextColor(Color.parseColor(titleC))
                })
                addView(TextView(this@PairedActivity).apply {
                    text = mac
                    textSize = 13f
                    setTextColor(Color.parseColor(subC))
                })
                setOnClickListener {
                    if (ConfSync.addToWhitelist(this@PairedActivity, mac, name ?: "")) {
                        Thread { ConfSync.sync(this@PairedActivity) }.start()
                        toast("已加入白名单：${name ?: mac}")
                    } else {
                        toast("该设备已在白名单里")
                    }
                    finish()
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
