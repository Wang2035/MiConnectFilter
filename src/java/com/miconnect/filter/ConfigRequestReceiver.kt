package com.miconnect.filter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 目标进程启动时会发来 CONFIG_REQUEST，这里把当前配置广播回去（解决进程晚启动错过配置）。 */
class ConfigRequestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ConfigBus.REQUEST_ACTION) return
        if (intent.getStringExtra("token") != ConfigBus.TOKEN) return
        Thread { ConfSync.sendConfigBroadcast(context) }.start()
    }
}
