package io.legado.app.help.tts

/**
 * iOS 端朗读宿主: 实现全在 [ReadAloudHostShared], 无 iOS 专属部分。
 *
 * 播控卡片 (NowPlayingInfoCenter) 由 `IosMediaNotificationController` 注册本对象接管。
 */
object IosReadAloudHost : ReadAloudHostShared()
