package io.legado.app.base

import android.os.Bundle
import android.view.View


abstract class BasePrefDialogFragment(
) : BaseDialogFragment(0) {

    override val applyFilletBackground: Boolean = false

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {}

}
