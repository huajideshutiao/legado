package io.legado.app.help.media

import splitties.init.appCtx

/**
 * 安卓宿主启动早期注册 [MediaNotificationController] 的 actual 实现。
 *
 * 注册一个默认 [AndroidMediaNotificationController] 实例 (基于 [appCtx], sessionTag
 * 为 "legadoMedia"), 让 [MediaNotificationProviders.get] 在 shared 侧可用。
 *
 * 调用时机: App.onCreate, 在任何 commonMain 代码调用
 * `MediaNotificationProviders.get()` 之前。
 *
 * 注意:
 * 1. 默认实例的注入点 (actionIntentFactory / artworkLoader / iconResolver /
 *    contentIntent / foregroundService / mediaButtonReceiverIntent) 均为 null。
 *    具体业务场景(如 ReadAloud/AudioPlay)需要完整功能时, 应直接构造
 *    [AndroidMediaNotificationController] 并配置注入点; 或从
 *    `MediaNotificationProviders.get() as AndroidMediaNotificationController`
 *    拿到默认实例后配置注入点。
 * 2. 本注册不修改 app/service/ 下现有 Service 代码。现有 BaseReadAloudService /
 *    AudioPlayService 保持原状, 未来切换到 controller 时再迁移。
 *
 * 模式参考 `registerAndroidPasswordProvider` / `registerAndroidAppStringProvider`。
 */
fun registerAndroidMediaNotificationProvider() {
    MediaNotificationProviders.register(
        AndroidMediaNotificationController(appCtx, "legadoMedia")
    )
}
