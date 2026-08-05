package io.legado.app.ui.book.video

/**
 * 视频手势"相对调节"状态机 (对照 origin/quickjs VideoPlayActivity.VideoGestureListener.onScroll
 * 的亮度/音量两段逻辑, 逐行等价下沉共享):
 *
 * 1. 进模式: [onGestureStart] 记录手势起点 + 读一次当前值 (各端平台实现经 [readCurrent] 注入:
 *    Android=window.screenBrightness / AudioManager, desktop=WMI 亮度 / WASAPI 系统音量)
 * 2. 每帧: `(currentValue + (startY - y) / height * max)` 相对增量, coerceIn(0, max)
 *    (对照原版 `(currentVolume + (startY - e2.y) / screenHeight * maxVolume)` 逐行等价)
 * 3. 碰顶/底 (结果 == 0 或 == max): 重置 startY 与 currentValue 为当前值
 *    (对照原版 `if (deltaVolume == 0 || deltaVolume == maxVolume) { startY = e2.y; currentVolume = deltaVolume }`,
 *    边界夹住回调: 拖过边界后反向拖动立即生效)
 *
 * 值域: 以 [max] 为上限的"原始单位"浮点 (亮度=0..1, 桌面音量=0..1, Android 音量=0..maxVolume),
 * 平台在调用侧做单位换算与落盘 (setStreamVolume 的 toInt / WMI 的 *100 / 窗口属性直写)。
 *
 * @param max 调节量上限 (原始单位)
 */
class VideoGestureAdjuster(
    private val max: Float = 1f,
) {
    private var startY = 0f
    private var currentValue = 0f

    /**
     * 进模式: 记录起点 + 读当前值。
     *
     * @param startY 手势起点 y (onDown 值)
     * @param readCurrent 读当前值 (0..max 原始单位); 返回 null 表示读取失败, 按 0 兜底
     *   (调用方可在 lambda 内自行回落, 如桌面系统音量失败回退 mediamp 音量)
     */
    fun onGestureStart(startY: Float, readCurrent: () -> Float?) {
        this.startY = startY
        this.currentValue = (readCurrent() ?: 0f).coerceIn(0f, max)
    }

    /**
     * 每帧相对调节: `(currentValue + (startY - y) / height * max)` 增量, 夹到 [0, max];
     * 碰顶/底时重置起点与当前值 (边界夹住, 对照原版)。
     *
     * @param y 当前 y (手势坐标)
     * @param height 手势区域高度 (对照原版 screenHeight)
     * @param step 可选量化粒度: >0 时结果先按 step 向下截断再夹取, 用于 Android 音量
     *   (AudioManager 整数步进, 对照原版 `.toInt().coerceIn(0, maxVolume)` 的截断+边界
     *   判定语义, 保证行为逐行等价); 默认 0 = 不量化 (亮度/桌面连续值)
     * @return 本次应落盘的值 (0..max 原始单位)
     */
    fun onGestureMove(y: Float, height: Float, step: Float = 0f): Float {
        var delta = currentValue + (startY - y) / height * max
        if (step > 0f) {
            // 对照原版 toInt() 截断 (向零): 先量化再夹取, 与 toInt 后 coerce 等价
            delta = (delta / step).toInt() * step
        }
        delta = delta.coerceIn(0f, max)
        if (delta == 0f || delta == max) {
            startY = y
            currentValue = delta
        }
        return delta
    }
}
