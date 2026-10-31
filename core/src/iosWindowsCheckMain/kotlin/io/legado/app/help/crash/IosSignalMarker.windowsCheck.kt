@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.help.crash

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer

/**
 * Windows klib 校验专用 stub (源根 src/iosWindowsCheckMain, 仅非 mac 主机的
 * iosArm64Main/iosSimulatorArm64Main 编译挂载): 真实实现在 iosMain 的
 * IosSignalMarker.kt, 依赖 signalposix cinterop 的 lg_open_write (需本机 Apple SDK),
 * Windows 上由本 stub 顶替, 使崩溃标记读取侧的消费方通过 klib 语法/签名校验。
 *
 * 成员签名与真实实现一致; 本代码不会在真实设备执行 (mac 上编译真实实现, 本文件不挂载),
 * 故安装期为惰性 (不做任何准备)。
 */
internal object IosSignalMarker {

    /** 标记文件路径 (真实实现里是安装期备好的 C 字符串, 处理器内直接用)。 */
    var markerPaths: List<CPointer<ByteVar>> = emptyList()

    /** 标记内容 "1\n" (与 Android native 侧、[CrashMarkerFile] 同值)。 */
    val markerText: ByteArray = MARKER_TEXT.encodeToByteArray()

    private const val MARKER_TEXT = "1\n"

    /**
     * 安装信号处理器 (宿主启动最早处调用一次, 见 `IosCrashHandler.install`)。
     *
     * @param dirs 标记文件候选目录 (与崩溃日志同目录)。
     */
    fun install(dirs: List<String>) {
        @Suppress("UNUSED_EXPRESSION")
        dirs
    }
}
