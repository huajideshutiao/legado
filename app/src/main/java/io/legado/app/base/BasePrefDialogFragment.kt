package io.legado.app.base

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import io.legado.app.R


abstract class BasePrefDialogFragment(
) : BaseDialogFragment(0) {

    override val applyFilletBackground: Boolean = false

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {}

    protected fun createPrefContainer(container: ViewGroup?, bgColor: Int): LinearLayout {
        val view = LinearLayout(requireContext())
        view.setBackgroundColor(bgColor)
        view.id = R.id.tag1
        container?.addView(view)
        return view
    }

    protected fun replacePreferenceFragment(
        containerView: View,
        fragmentTag: String,
        createFragment: () -> Fragment
    ) {
        var fragment = childFragmentManager.findFragmentByTag(fragmentTag)
        if (fragment == null) fragment = createFragment()
        childFragmentManager.beginTransaction()
            .replace(containerView.id, fragment, fragmentTag)
            .commit()
    }

}
