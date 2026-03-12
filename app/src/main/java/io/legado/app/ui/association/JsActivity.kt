package io.legado.app.ui.association

import android.os.Bundle
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityTranslucenceBinding
import io.legado.app.help.IntentData
import io.legado.app.utils.viewbindingdelegate.viewBinding

class JsActivity : BaseActivity<ActivityTranslucenceBinding>() {
    override val binding by viewBinding(ActivityTranslucenceBinding::inflate)
    override fun onActivityCreated(savedInstanceState: Bundle?) {
        val waitKey = intent.getStringExtra("waitKey")
        if (waitKey != null) IntentData.get<(JsActivity) -> Unit>(waitKey)?.invoke(this)
        else finish()
    }
}
