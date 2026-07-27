package io.legado.app.ui.book.read

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

// ReadBookEvents object 已下沉到 shared/commonMain (包名一致), 本文件直接引用, import 不变。
// ReadConfigChange enum 亦在 shared/commonMain (同包)。

/** 生命周期感知收集，语义对齐 FlowBus.observe（CREATED 起持续收集） */
fun <T> SharedFlow<T>.observe(owner: LifecycleOwner, observer: (T) -> Unit) {
    owner.lifecycleScope.launch {
        owner.repeatOnLifecycle(Lifecycle.State.CREATED) {
            collect { observer(it) }
        }
    }
}
