package io.legado.app.ui.main

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import io.legado.app.ui.root.AppNavigator

/**
 * AppNavigator 的 rememberSaveable Saver: Activity recreate()/进程重建后从
 * savedInstanceState 恢复导航栈, 用户停留在原页面。
 *
 * 对照原版: 主题变更/备份恢复等 postEvent(EventBus.RECREATE) → BaseActivity.recreate(),
 * FragmentManager 从 savedInstanceState 恢复 Fragment 栈, 用户仍在原页。
 * 迁移版 Compose 导航栈若用 remember 持有会在 recreate 后清空, 弹回主界面;
 * 复用 AppNavigator 自带的 NavigationSnapshot 序列化 (encodeSnapshot/decodeSnapshot),
 * 与页面级 rememberSaveable (SaveableStateHolder) 同走 Activity savedInstanceState。
 */
object AppNavigatorSaver : Saver<AppNavigator, String> {
    override fun SaverScope.save(value: AppNavigator): String = value.encodeSnapshot()

    override fun restore(value: String): AppNavigator = AppNavigator(
        restoredSnapshot = AppNavigator.decodeSnapshot(value)
    )
}
