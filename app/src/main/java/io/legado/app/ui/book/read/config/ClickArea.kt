package io.legado.app.ui.book.read.config

import android.graphics.RectF
import io.legado.app.help.config.AppConfig

class ClickArea {
    private val tlRect = RectF()
    private val tcRect = RectF()
    private val trRect = RectF()
    private val mlRect = RectF()
    private val mcRect = RectF()
    private val mrRect = RectF()
    private val blRect = RectF()
    private val bcRect = RectF()
    private val brRect = RectF()

    fun setRect(width: Int, height: Int) {
        tlRect.set(0f, 0f, width * 0.33f, height * 0.33f)
        tcRect.set(width * 0.33f, 0f, width * 0.66f, height * 0.33f)
        trRect.set(width * 0.66f, 0f, width.toFloat(), height * 0.33f)
        mlRect.set(0f, height * 0.33f, width * 0.33f, height * 0.66f)
        mcRect.set(width * 0.33f, height * 0.33f, width * 0.66f, height * 0.66f)
        mrRect.set(width * 0.66f, height * 0.33f, width.toFloat(), height * 0.66f)
        blRect.set(0f, height * 0.66f, width * 0.33f, height.toFloat())
        bcRect.set(width * 0.33f, height * 0.66f, width * 0.66f, height.toFloat())
        brRect.set(width * 0.66f, height * 0.66f, width.toFloat(), height.toFloat())
    }

    fun isCenter(x: Float, y: Float): Boolean {
        return mcRect.contains(x, y)
    }

    fun getAction(x: Float, y: Float): Int {
        return when {
            mcRect.contains(x, y) -> AppConfig.clickActionMC
            bcRect.contains(x, y) -> AppConfig.clickActionBC
            blRect.contains(x, y) -> AppConfig.clickActionBL
            brRect.contains(x, y) -> AppConfig.clickActionBR
            mlRect.contains(x, y) -> AppConfig.clickActionML
            mrRect.contains(x, y) -> AppConfig.clickActionMR
            tlRect.contains(x, y) -> AppConfig.clickActionTL
            tcRect.contains(x, y) -> AppConfig.clickActionTC
            trRect.contains(x, y) -> AppConfig.clickActionTR
            else -> -1
        }
    }
}
