package io.legado.app.ui.book.read.config

import io.legado.app.constant.EventBus
import io.legado.app.ui.widget.DetailSeekBar
import io.legado.app.utils.postEvent

data class SeekBarConfigBinding(
    val seekBar: DetailSeekBar,
    val setter: (Int) -> Unit,
    val events: List<Int>
)

fun bindSeekBarConfigs(bindings: List<SeekBarConfigBinding>) {
    bindings.forEach { (seekBar, setter, events) ->
        seekBar.onChanged = {
            setter(it)
            postEvent(EventBus.UP_CONFIG, ArrayList(events))
        }
    }
}
