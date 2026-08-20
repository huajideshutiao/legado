@file:Suppress("unused")

package io.legado.app.utils

import android.content.Context
import android.os.Build
import android.view.View
import android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
import android.view.inputmethod.InputMethodManager

fun View.hideSoftInput() {
    (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
        ?.hideSoftInputFromWindow(this.windowToken, 0)
}

fun View.disableAutoFill() {
    // setImportantForAutofill 是 API 26+ (minSdk 24), 低版本无此能力, 直接跳过
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }
}
