package io.legado.app.ui.widget

import android.graphics.Rect
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import io.legado.app.lib.dialogs.SelectItem

class PopupAction() {

    var onActionClick: ((action: String) -> Unit)? = null
    private var actionMode: ActionMode? = null
    private val contentRect = Rect()
    private var items = emptyList<SelectItem<String>>()

    private val actionModeCallback = object : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            items.forEachIndexed { index, item ->
                menu.add(Menu.NONE, index, index, item.title)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            }
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            return true
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            val action = items.getOrNull(item.itemId)?.value
            if (action != null) {
                onActionClick?.invoke(action)
            }
            mode.finish()
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            actionMode = null
        }

        override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
            outRect.set(contentRect)
        }
    }

    fun setItems(items: List<SelectItem<String>>) {
        this.items = items
    }

    fun show(view: View, x: Int, y: Int) {
        val size = 20
        contentRect.set(x - size, y - size, x + size, y + size)

        if (actionMode == null) {
            actionMode =
                view.startActionMode(actionModeCallback, ActionMode.TYPE_FLOATING)
        } else {
            actionMode?.invalidateContentRect()
        }
    }

    fun dismiss() {
        actionMode?.finish()
        actionMode = null
    }
}
