package io.github.wang2035.miconnectfilter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 开机后把当前配置重发一遍（文件 + 广播），让开机自启的目标进程也拿到白名单/放行状态。 */
class BootResyncReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        Thread {
            try {
                // 稍等目标进程把广播接收器注册好再发，避免开机竞速丢配置
                Thread.sleep(8000)
                ConfSync.sync(context)
            } catch (_: Throwable) {
            } finally {
                pending.finish()
            }
        }.start()
    }
}
