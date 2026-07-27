package io.legado.app.help.media

/**
 * 跨平台媒体通知控制接口。
 *
 * 现状: 朗读 / 音频播放的通知栏控制全部在 app/service/ 下
 * (BaseReadAloudService / AudioPlayService), 直接依赖 Android Service +
 * NotificationManager + MediaSessionCompat, 无法跨平台。本接口把"对外暴露的
 * 媒体通知控制 API"抽象到 commonMain, iOS / HarmonyOS 各自提供 actual 实现。
 *
 * 设计原则:
 * 1. 调用方只关心"展示什么内容 / 当前是什么状态", 不关心通知渠道/PendingIntent/
 *    Bitmap 等平台细节; 这些由 actual 实现内部装配。
 * 2. 元数据中的封面统一用 [MediaMetadata.artworkUrl] (跨平台可用 URL 表达),
 *    app 端 actual 自行下载转 Bitmap 后塞进 NotificationCompat / MediaMetadataCompat。
 * 3. 播放状态用 [PlaybackState.Status] 枚举, 与 PlaybackStateCompat 状态码一一映射,
 *    actual 实现内部完成转换, 不向 commonMain 泄露 Android 类型。
 *
 * 模式参考 [io.legado.app.data.AppDbProviders] / PasswordProviders。
 */
interface MediaNotificationController {

    /**
     * 启动前台通知并初始化媒体会话。
     *
     * 由 Service.startForegroundNotification 调用, 第一次展示通知时使用。
     * 调用后 actual 实现应进入"前台服务"状态并发出首条通知。
     *
     * @param notificationId 通知 ID (跨平台用 Int 表达, app 端映射到 NotificationId.*)
     * @param channelId      通知渠道 ID
     * @param metadata       媒体元数据 (标题/副标题/封面 URL 等)
     * @param state          播放状态
     * @param actions        通知栏可点击的动作集合 (actual 端负责翻译成平台按钮)
     */
    fun startForeground(
        notificationId: Int,
        channelId: String,
        metadata: MediaMetadata,
        state: PlaybackState,
        actions: List<MediaAction> = emptyList(),
    )

    /**
     * 刷新通知(元数据 + 状态 + 动作)。无变化时 actual 实现可自行短路。
     */
    fun updateNotification(
        metadata: MediaMetadata,
        state: PlaybackState,
        actions: List<MediaAction> = emptyList(),
    )

    /** 仅更新元数据(切歌/换章时调用, 状态保持不变)。 */
    fun updateMetadata(metadata: MediaMetadata)

    /** 仅更新播放状态(播放/暂停/停止切换时调用, 元数据保持不变)。 */
    fun updatePlaybackState(state: PlaybackState)

    /**
     * 设置媒体会话是否激活。
     *
     * 锁屏媒体控件在会话激活时才显示, 停止/释放时调用 [release] 前应 deactivate。
     */
    fun setSessionActive(active: Boolean)

    /** 取消通知(不释放会话)。 */
    fun cancelNotification()

    /** 释放资源: 取消通知 + 反激活会话 + 释放 MediaSession。 */
    fun release()
}

/**
 * 媒体通知控制器 provider 容器。宿主启动早期注册一次。
 *
 * shared 内访问点用 `MediaNotificationProviders.get()` 替代直接构造 Android 实现,
 * 行为完全一致, 仅多一层 provider 间接。
 *
 * 未注册时 [get] 抛出 IllegalStateException, 提示宿主尚未注入 actual 实现。
 */
object MediaNotificationProviders {

    @Volatile
    private var impl: MediaNotificationController? = null

    /** 宿主启动早期注册一次(任何媒体通知调用之前)。 */
    fun register(impl: MediaNotificationController) {
        this.impl = impl
    }

    /** 获取已注册实现, 未注册抛出 IllegalStateException。 */
    fun get(): MediaNotificationController =
        impl ?: error("MediaNotificationProviders not registered")

    /** 是否已注册(供调试/测试判断, 业务代码应直接用 [get])。 */
    fun isRegistered(): Boolean = impl != null
}
