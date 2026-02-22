package com.script.rhino

import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.lc.type.TypeInfo

fun interface JavaObjectWrapFactory {

    fun wrap(scope: Scriptable?, javaObject: Any, staticType: TypeInfo?): Scriptable

}
