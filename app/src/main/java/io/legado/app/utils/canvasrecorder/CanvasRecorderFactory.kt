package io.legado.app.utils.canvasrecorder

import android.os.Build
import io.legado.app.help.config.AppConfig

object CanvasRecorderFactory {

    private val atLeastApi29 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    val isSupport = true

    fun create(locked: Boolean = false): CanvasRecorder {
        val impl = when {
            !AppConfig.optimizeRender -> CanvasRecorderImpl()
            atLeastApi29 -> CanvasRecorderApi29Impl()
            else -> CanvasRecorderApi23Impl()
        }
        return if (locked) {
            CanvasRecorderLocked(impl)
        } else {
            impl
        }
    }

}
