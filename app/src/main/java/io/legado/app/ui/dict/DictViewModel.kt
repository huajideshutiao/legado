package io.legado.app.ui.dict

import android.app.Application
import androidx.lifecycle.viewModelScope
import io.legado.app.base.BaseViewModel
import io.legado.app.data.entities.DictRule

class DictViewModel(application: Application) : BaseViewModel(application) {

    // 委托给 commonMain 的 DictViewModelShared, 业务逻辑下沉供多端复用
    // (Android=viewModelScope / desktop=应用 scope), 见 DictViewModelShared.kt
    // 暴露 shared 供 app 端 DictDialog 注入到 shared/sharedUiMain 的 DictDialogContent
    val shared: DictViewModelShared =
        DictViewModelShared(viewModelScope)

    fun initData(onSuccess: (List<DictRule>) -> Unit) {
        shared.initData(onSuccess)
    }

    fun dict(
        dictRule: DictRule,
        word: String,
        onFinally: (String) -> Unit
    ) {
        shared.dict(dictRule, word, onFinally)
    }


}
