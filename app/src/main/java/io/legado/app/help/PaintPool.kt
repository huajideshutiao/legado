package io.legado.app.help

import android.graphics.Paint
import io.legado.app.utils.objectpool.BaseSafeObjectPool

/**
 * 阅读页绘制的 Paint 对象池。
 *
 * # 下沉说明
 * 保持 app 端不下沉:
 * - 池基类 [BaseSafeObjectPool] 已下沉 shared/commonMain (io.legado.app.utils.objectpool), 本类只剩
 *   android.graphics.Paint 平台依赖; 桌面 skiko 的 org.jetbrains.skia.Paint 与 android.graphics.Paint
 *   API 不等价 (无 set()/setAntiAlias 同构方法), 两端对象无法共用同一池
 * - shared 侧无任何引用方, 原使用方 (阅读页原生绘制 TextLine/TextPage) 在 KMP 化后已移除,
 *   当前 app 内亦无调用, 下沉无收益; 若未来出现跨端绘制需求, 再以 expect/actual 抽象 Paint 后下沉
 */
object PaintPool : BaseSafeObjectPool<Paint>(8) {

    private val emptyPaint = Paint()

    override fun create(): Paint = Paint()

    override fun recycle(target: Paint) {
        target.set(emptyPaint)
        super.recycle(target)
    }

}
