package io.legado.app.utils

import android.app.Activity
import android.content.Intent
import androidx.fragment.app.Fragment

inline fun <reified T : Activity> Fragment.startActivity(
    configIntent: Intent.() -> Unit = {}
) {
    startActivity(Intent(requireContext(), T::class.java).apply(configIntent))
}
