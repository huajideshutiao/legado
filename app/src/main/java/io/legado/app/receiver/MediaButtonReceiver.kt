package io.legado.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.help.LifecycleHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.model.AudioPlay
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.service.AudioPlayService
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.book.read.ReadBookEvents
import io.legado.app.ui.root.AppNavigatorProviders
import io.legado.app.ui.root.AppRoute
import io.legado.app.utils.LogUtils
import io.legado.app.utils.postEvent
import kotlinx.coroutines.runBlocking


/**
 * Created by GKF on 2018/1/6.
 * 监听耳机键
 */
class MediaButtonReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (handleIntent(context, intent) && isOrderedBroadcast) {
            abortBroadcast()
        }
    }

    companion object {

        private const val TAG = "MediaButtonReceiver"

        fun handleIntent(context: Context, intent: Intent): Boolean {
            val intentAction = intent.action
            if (Intent.ACTION_MEDIA_BUTTON == intentAction) {
                @Suppress("DEPRECATION")
                val keyEvent = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    ?: return false
                val keycode: Int = keyEvent.keyCode
                val action: Int = keyEvent.action
                if (action == KeyEvent.ACTION_DOWN) {
                    LogUtils.d(TAG, "Receive mediaButton event, keycode:$keycode")
                    when (keycode) {
                        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                            when {
                                AudioPlayService.isRun -> AudioPlay.prev()
                                BaseReadAloudService.isRun -> {
                                    if (AppConfig.mediaButtonPerNext) {
                                        ReadAloud.prevChapter(context)
                                    } else {
                                        ReadAloud.prevParagraph(context)
                                    }
                                }
                                else -> readAloud(context)
                            }
                        }

                        KeyEvent.KEYCODE_MEDIA_NEXT -> {
                            when {
                                AudioPlayService.isRun -> AudioPlay.next()
                                BaseReadAloudService.isRun -> {
                                    if (AppConfig.mediaButtonPerNext) {
                                        ReadAloud.nextChapter(context)
                                    } else {
                                        ReadAloud.nextParagraph(context)
                                    }
                                }
                                else -> readAloud(context)
                            }
                        }

                        KeyEvent.KEYCODE_MEDIA_STOP -> {
                            when {
                                AudioPlayService.isRun -> AudioPlay.stop()
                                BaseReadAloudService.isRun -> ReadAloud.stop(context)
                            }
                        }

                        else -> readAloud(context)
                    }
                }
            }
            return true
        }

        fun readAloud(context: Context, isMediaKey: Boolean = true) {
            when {
                BaseReadAloudService.isRun -> {
                    if (BaseReadAloudService.isPlay()) {
                        ReadAloud.pause(context)
                        AudioPlay.pause()
                    } else {
                        ReadAloud.resume(context)
                        AudioPlay.resume()
                    }
                }

                AudioPlayService.isRun -> {
                    if (AudioPlayService.pause) {
                        AudioPlay.resume()
                    } else {
                        AudioPlay.pause()
                    }
                }

                isMediaKey && !AppConfig.readAloudByMediaButton -> {
                    // break
                }

                // AudioPlay 已下沉为共享路由, 栈顶为该路由时由页面响应媒体键事件
                AppNavigatorProviders.getOrNull()?.currentRoute is AppRoute.AudioPlay ->
                    postEvent(EventBus.MEDIA_BUTTON, true)

                // Reader 阅读页在栈顶时由页面响应媒体键事件 (对照原版 ReadBookActivity 分支
                // postEvent(EventBus.MEDIA_BUTTON, true) 语义, 桥接到 shared 阅读层处理)
                AppNavigatorProviders.getOrNull()?.currentRoute is AppRoute.Reader ->
                    ReadBookEvents.postMediaButton(true)

                else -> if (AppConfig.mediaButtonOnExit || LifecycleHelp.activitySize() > 0 || !isMediaKey) {
                    ReadAloud.upReadAloudClass()
                    if (ReadBook.book != null) {
                        ReadBook.readAloud()
                    } else {
                        // Room KMP: lastReadBook 已改为 suspend 方法，readAloud 不能 suspend (BroadcastReceiver 调用)，用 runBlocking 适配
                        runBlocking { appDb.bookDao.lastReadBook() }?.let {
                            ReadBook.initData(it)
                            ReadBook.clearTextChapter()
                            ReadBook.loadContent(false) {
                                ReadBook.readAloud()
                            }
                        }
                    }
                }
            }
        }
    }

}
