package io.github.wang2035.miconnectfilter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 蓝牙进程里的 hook 读到框架电量（BluetoothDevice.getBatteryLevel）后回传到这里：
 * 落盘到 BatteryStore 并同步给全部进程，断联后各显示端用存的值顶框架读数。
 */
class BatterySyncReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        try {
            if (intent?.action != ConfigBus.BATTERY_ACTION) return
            if (intent.getStringExtra("token") != ConfigBus.TOKEN) return
            val mac = MacUtil.normalize(intent.getStringExtra("mac")) ?: return
            var changed = false
            val triple = intent.getStringExtra("triple")
            if (triple != null && BatteryStore.update3(context, mac, triple)) changed = true
            val level = intent.getIntExtra("level", -1)
            if (level in 0..100 && BatteryStore.update(context, mac, level)) changed = true
            val disc = intent.getBooleanExtra("disc", false)
            if (changed || disc) {
                Thread { try { ConfSync.sync(context, force = disc) } catch (_: Throwable) {} }.start()
            }
        } catch (_: Throwable) {}
    }
}
