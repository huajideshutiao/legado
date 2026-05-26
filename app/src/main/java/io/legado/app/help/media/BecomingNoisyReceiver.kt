package io.legado.app.help.media

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager

/**
 * 监听拔耳机 / 蓝牙断开 (ACTION_AUDIO_BECOMING_NOISY),触发 [onBecomingNoisy]。
 *
 * 一对 register / unregister 配对调用,内部自带防止重复注册的状态保护。
 */
class BecomingNoisyReceiver(
    private val onBecomingNoisy: () -> Unit
) : BroadcastReceiver() {

    private var registered = false

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
            onBecomingNoisy()
        }
    }

    fun register(context: Context) {
        if (registered) return
        context.registerReceiver(this, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        registered = true
    }

    fun unregister(context: Context) {
        if (!registered) return
        context.unregisterReceiver(this)
        registered = false
    }
}
