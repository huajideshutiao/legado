@file:Suppress("unused")

package io.legado.app.model

/**
 * AudioPlay 跨平台 typealias (app 端)。
 *
 * 主体逻辑 (状态字段 + 章节切换 + Service 派发 + 状态变更) 已下沉到
 * shared commonMain 的 [AudioPlayShared] object。本文件仅提供:
 * - `typealias AudioPlay = AudioPlayShared`: 保持 app 端 100+ 调用点零改动
 *   (AudioPlay.book / AudioPlay.durChapterIndex / AudioPlay.PlayMode / AudioPlay.prev() 等
 *   全部通过 typealias 自动解析到 AudioPlayShared 对应成员)
 * - [PlayMode.iconRes] 扩展属性: R.drawable.* 是 Android 资源 ID, enum 构造参数无法
 *   跨平台, 故 commonMain 的 PlayMode enum 不含 iconRes, 由本文件扩展属性补回
 *
 * # 平台差异
 * - Service 派发: AudioPlayShared 通过 [io.legado.app.model.AudioPlayCommander] 接口抽象,
 *   app 端注册 [AndroidAudioPlayCommander] 实现 (见 AudioPlayProvidersImpl.kt),
 *   内部走 `appCtx.startService<AudioPlayService>` + IntentAction + extras,
 *   与原 `sendAction` 完全等价
 * - Book 操作: AudioPlayShared 通过 [io.legado.app.model.AudioPlayBookBridge] 接口抽象,
 *   app 端注册实现委托 `book.saveRead()` / `book.save()` / `book.getBookSource()` 扩展
 *
 * 注册时机: App.onCreate, 经 `registerAndroidAudioPlayProviders()` (见 AudioPlayProvidersImpl.kt)。
 */
typealias AudioPlay = AudioPlayShared

/**
 * PlayMode 图标资源 ID (Android 专属扩展)。
 *
 * commonMain 的 `AudioPlayShared.PlayMode` enum 不含 `iconRes` 构造参数 (R.drawable.*
 * 是 Android 资源 ID, 无法跨平台引用)。本扩展属性补回图标资源, 调用方
 * `playMode.iconRes` 语法不变 (Kotlin 扩展属性与构造属性访问语法一致)。
 *
 * 调用点: AudioPlayScreen.kt (播放模式切换按钮图标)
 */
val AudioPlayShared.PlayMode.iconRes: Int
    get() = when (this) {
        AudioPlayShared.PlayMode.LIST_END_STOP -> io.legado.shared.R.drawable.ic_play_mode_list_end_stop
        AudioPlayShared.PlayMode.SINGLE_LOOP -> io.legado.shared.R.drawable.ic_play_mode_single_loop
        AudioPlayShared.PlayMode.RANDOM -> io.legado.shared.R.drawable.ic_play_mode_random
        AudioPlayShared.PlayMode.LIST_LOOP -> io.legado.shared.R.drawable.ic_play_mode_list_loop
    }
