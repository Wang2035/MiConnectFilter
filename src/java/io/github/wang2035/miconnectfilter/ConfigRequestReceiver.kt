package io.github.wang2035.miconnectfilter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 目标进程启动时会发来 CONFIG_REQUEST，这里把当前配置广播回去（解决进程晚启动错过配置）。 */
class ConfigRequestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ConfigBus.REQUEST_ACTION) return
        if (intent.getStringExtra("token") != ConfigBus.TOKEN) return
        val from = intent.getStringExtra("from")
        Thread {
            ConfSync.sendConfigBroadcast(context)
            // 用户自行加进作用域的应用（如设置）不在固定目标名单里，定向补发一份，
            // 它进程里的电量注入才有数据可用。
            if (!from.isNullOrBlank() && from !in Targets.PACKAGES) {
                ConfSync.sendConfigBroadcastTo(context, from)
            }
        }.start()
    }
}
