package io.legado.app.constant

@Suppress("ConstPropertyName")
object IntentAction {
    const val start = "start"
    const val play = "play"
    const val playData = "playData"
    const val loadPlayUrl = "loadPlayUrl"
    const val lrc = "lrc"
    const val playNew = "playNew"
    const val stop = "stop"
    const val resume = "resume"
    const val pause = "pause"
    const val addTimer = "addTimer"
    const val setTimer = "setTimer"
    const val prevParagraph = "prevParagraph"
    const val nextParagraph = "nextParagraph"
    const val upTtsSpeechRate = "upTtsSpeechRate"
    const val adjustProgress = "adjustProgress"
    const val adjustSpeed = "adjustSpeed"
    const val prev = "prev"
    const val next = "next"
    const val init = "init"
    const val remove = "remove"
    const val stopPlay = "stopPlay"

    // ===== MainActivity 通知 contentIntent 身份 action (与 route extra 配套) =====
    // 通知点击统一走 MainActivity → toLaunchRequest → NavigateTo(route); 各通知用不同 action
    // 保证 PendingIntent 身份不同 (FLAG_UPDATE_CURRENT 下 extras 不会被互相覆盖, 对齐 origin
    // 音频/朗读分别指向 AudioPlayActivity/ReadBookActivity 的天然隔离)
    const val activityAudioPlay = "activity_audio_play"
    const val activityReadAloud = "activity_read_aloud"
    const val activityCheckSource = "activity_check_source"
    const val activityBookshelfManage = "activity_bookshelf_manage"
}