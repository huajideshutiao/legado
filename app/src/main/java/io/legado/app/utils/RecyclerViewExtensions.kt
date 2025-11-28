package io.legado.app.utils

import androidx.recyclerview.widget.RecyclerView

fun RecyclerView.findCenterViewPosition(): Int {
    return getChildAdapterPosition(
        findChildViewUnder(width / 2f, height / 2f) ?: return RecyclerView.NO_POSITION
    )
}

