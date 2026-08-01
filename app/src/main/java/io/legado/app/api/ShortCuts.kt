package io.legado.app.api

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import io.legado.app.R
import io.legado.app.ui.main.MainActivity

object ShortCuts {

    private inline fun <reified T> buildIntent(
        context: Context,
        configIntent: Intent.() -> Unit = {},
    ): Intent {
        val intent = Intent(context, T::class.java)
        intent.action = Intent.ACTION_VIEW
        return intent.apply(configIntent)
    }

    private fun buildBookShelfShortCutInfo(context: Context): ShortcutInfoCompat {
        val bookShelfIntent = buildIntent<MainActivity>(context)
        return ShortcutInfoCompat.Builder(context, "bookshelf")
            .setShortLabel(context.getString(R.string.bookshelf))
            .setLongLabel(context.getString(R.string.bookshelf))
            .setIcon(IconCompat.createWithResource(context, R.drawable.icon_read_book))
            .setIntent(bookShelfIntent)
            .build()
    }

    private fun buildReadBookShortCutInfo(context: Context): ShortcutInfoCompat {
        val bookShelfIntent = buildIntent<MainActivity>(context)
        // 路由 extra 经 MainActivity → NavigateTo("last_read") 打开最近阅读书籍
        val readBookIntent = buildIntent<MainActivity>(context) {
            putExtra("route", "last_read")
        }
        return ShortcutInfoCompat.Builder(context, "lastRead")
            .setShortLabel(context.getString(R.string.last_read))
            .setLongLabel(context.getString(R.string.last_read))
            .setIcon(IconCompat.createWithResource(context, R.drawable.icon_read_book))
            .setIntents(arrayOf(bookShelfIntent, readBookIntent))
            .build()
    }

    private fun buildReadAloudShortCutInfo(context: Context): ShortcutInfoCompat {
        // AssociationActivity 的 intent-filter 已迁移到 MainActivity
        val readAloudIntent = buildIntent<MainActivity>(context)
        readAloudIntent.putExtra("action", "readAloud")
        return ShortcutInfoCompat.Builder(context, "readAloud")
            .setShortLabel(context.getString(R.string.read_aloud))
            .setLongLabel(context.getString(R.string.read_aloud))
            .setIcon(IconCompat.createWithResource(context, R.drawable.icon_read_book))
            .setIntent(readAloudIntent)
            .build()
    }

    @android.annotation.SuppressLint("ReportShortcutUsage")
    fun buildShortCuts(context: Context) {
        ShortcutManagerCompat.setDynamicShortcuts(
            context, listOf(
                buildReadBookShortCutInfo(context),
                buildReadAloudShortCutInfo(context),
                buildBookShelfShortCutInfo(context)
            )
        )
    }

}