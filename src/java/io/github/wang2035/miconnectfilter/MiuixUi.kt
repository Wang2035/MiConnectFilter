package io.github.wang2035.miconnectfilter

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Miuix 风对话框（全应用统一）：白/深大圆角卡片、标题 18 加粗、说明 14、
 * 底部文字按钮（主操作蓝色加粗）。替代系统 AlertDialog——系统弹窗在
 * HyperOS 上是 Material 深灰底 + 青色按钮，与设置页的 Miuix 卡片风不一致。
 */
object MiuixUi {

    class Theme(
        val dark: Boolean,
        val cardBg: String,
        val titleC: String,
        val subC: String,
        val chipBg: String,
    )

    fun theme(ctx: Context): Theme {
        val dark = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return Theme(
            dark = dark,
            cardBg = if (dark) "#2C2C2E" else "#FFFFFF",
            titleC = if (dark) "#F5F5F5" else "#1A1A1A",
            subC = if (dark) "#98989F" else "#6E6E73",
            chipBg = if (dark) "#3A3A3C" else "#F2F2F7",
        )
    }

    private fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    fun showDialog(
        activity: Activity,
        title: String,
        message: String? = null,
        content: View? = null,
        positiveText: String? = "好",
        onPositive: (() -> Unit)? = null,
        negativeText: String? = null,
        onNegative: (() -> Unit)? = null,
        extraText: String? = null,
        onExtra: (() -> Unit)? = null,
        scrollMessage: Boolean = false,
    ): Dialog {
        val t = theme(activity)
        val accent = "#3482FF"
        val dialog = Dialog(activity)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 24), dp(activity, 22), dp(activity, 24), dp(activity, 14))
            background = GradientDrawable().apply {
                setColor(Color.parseColor(t.cardBg))
                cornerRadius = dp(activity, 24).toFloat()
            }
        }
        root.addView(TextView(activity).apply {
            text = title
            textSize = 18f
            setTextColor(Color.parseColor(t.titleC))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        if (!message.isNullOrBlank()) {
            val msgView = TextView(activity).apply {
                text = message
                textSize = 14f
                setTextColor(Color.parseColor(t.subC))
                setLineSpacing(0f, 1.25f)
                setPadding(0, dp(activity, 10), 0, 0)
            }
            if (scrollMessage) {
                // 高度直接定死为屏幕 38%：长日志时滚动区固定，底部按钮永远在屏内
                // （此前用 post 回调改高，内容太长时会把按钮顶出屏幕）。
                val fixedH = (activity.resources.displayMetrics.heightPixels * 0.38).toInt()
                root.addView(ScrollView(activity).apply {
                    addView(msgView)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, fixedH,
                    )
                })
            } else {
                root.addView(msgView)
            }
        }
        if (content != null) root.addView(content)

        val btnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(activity, 14), 0, 0)
        }
        if (extraText != null) {
            btnRow.addView(TextView(activity).apply {
                text = extraText
                textSize = 15f
                setTextColor(Color.parseColor(t.subC))
                setPadding(dp(activity, 14), dp(activity, 8), dp(activity, 14), dp(activity, 8))
                setOnClickListener {
                    dialog.dismiss()
                    onExtra?.invoke()
                }
            })
        }
        if (negativeText != null) {
            btnRow.addView(TextView(activity).apply {
                text = negativeText
                textSize = 15f
                setTextColor(Color.parseColor(t.subC))
                setPadding(dp(activity, 14), dp(activity, 8), dp(activity, 14), dp(activity, 8))
                setOnClickListener {
                    dialog.dismiss()
                    onNegative?.invoke()
                }
            })
        }
        if (positiveText != null) {
            btnRow.addView(TextView(activity).apply {
                text = positiveText
                textSize = 15f
                setTextColor(Color.parseColor(accent))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(dp(activity, 14), dp(activity, 8), dp(activity, 6), dp(activity, 8))
                setOnClickListener {
                    dialog.dismiss()
                    onPositive?.invoke()
                }
            })
        }
        root.addView(btnRow)

        dialog.setContentView(root)
        dialog.window?.setLayout(
            activity.resources.displayMetrics.widthPixels - dp(activity, 56),
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        dialog.show()
        return dialog
    }

    /** 对话框里的输入框（灰底圆角，与设置页 chip 同色）。 */
    fun input(activity: Activity, hintText: String): android.widget.EditText {
        val t = theme(activity)
        return android.widget.EditText(activity).apply {
            hint = hintText
            textSize = 15f
            isSingleLine = true
            setTextColor(Color.parseColor(t.titleC))
            setHintTextColor(Color.parseColor(t.subC))
            setPadding(dp(activity, 14), dp(activity, 11), dp(activity, 14), dp(activity, 11))
            background = GradientDrawable().apply {
                setColor(Color.parseColor(t.chipBg))
                cornerRadius = dp(activity, 12).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(activity, 10) }
        }
    }

    /**
     * 照片同款开关：蓝色胶囊轨道 + 白色圆钮（开时在右），关闭为浅灰轨道。
     * 系统 Switch 在 HyperOS 上渲染成青绿大圆点，与 Miuix 风不符，故自绘。
     */
    class MiuixSwitch(context: Context, initiallyChecked: Boolean) : FrameLayout(context) {
        var isChecked: Boolean = initiallyChecked
            private set
        var onCheckedChange: ((Boolean) -> Unit)? = null

        private val track = View(context)
        private val knob = View(context)
        private val dark = theme(context).dark

        init {
            val w = dp(context, 52)
            val h = dp(context, 32)
            layoutParams = ViewGroup.LayoutParams(w, h)
            isClickable = true
            isFocusable = true
            clipChildren = false

            track.layoutParams = LayoutParams(w, h)
            addView(track)

            val knobSize = dp(context, 26)
            knob.layoutParams = LayoutParams(knobSize, knobSize, Gravity.START or Gravity.CENTER_VERTICAL).apply {
                leftMargin = dp(context, 3)
            }
            knob.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
            }
            knob.elevation = dp(context, 2).toFloat()
            addView(knob)

            applyState(animate = false)
            setOnClickListener { setChecked(!isChecked, animate = true, notify = true) }
        }

        fun setChecked(value: Boolean, animate: Boolean = false, notify: Boolean = false) {
            if (isChecked == value) {
                applyState(animate)
                return
            }
            isChecked = value
            applyState(animate)
            if (notify) onCheckedChange?.invoke(value)
        }

        private fun applyState(animate: Boolean) {
            track.background = GradientDrawable().apply {
                cornerRadius = dp(context, 16).toFloat()
                setColor(
                    Color.parseColor(
                        when {
                            isChecked -> "#0A84FF"
                            dark -> "#39393D"
                            else -> "#E9E9EA"
                        },
                    ),
                )
            }
            val target = if (isChecked) dp(context, 20).toFloat() else 0f
            if (animate) {
                knob.animate().translationX(target).setDuration(160).start()
            } else {
                knob.translationX = target
            }
            contentDescription = if (isChecked) "已开启" else "已关闭"
        }
    }
}
