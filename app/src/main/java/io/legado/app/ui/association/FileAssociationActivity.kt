package io.legado.app.ui.association

import android.os.Bundle
import io.legado.app.base.BaseActivity
import io.legado.app.constant.Theme
import io.legado.app.databinding.ActivityTranslucenceBinding
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.viewbindingdelegate.viewBinding

class FileAssociationActivity :
    BaseActivity<ActivityTranslucenceBinding>(
        theme = Theme.Transparent,
        imageBg = false
    ) {

    override val binding by viewBinding(ActivityTranslucenceBinding::inflate)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        intent.data?.let { data ->
            if (savedInstanceState == null) {
                showDialogFragment(FileAssociationDialog(data))
            }
        } ?: finish()
    }
}