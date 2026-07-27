package io.legado.app.help

import io.legado.app.constant.AppConst
import kotlin.concurrent.Volatile

object UserAgentProviders {
    @Volatile var impl: UserAgentProvider? = null
    fun get(): String = impl?.get() ?: AppConst.UA_NAME
}

fun interface UserAgentProvider {
    fun get(): String
}
