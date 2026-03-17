package io.legado.app.help.http

import io.legado.app.lib.cronet.CronetInterceptor
import io.legado.app.lib.cronet.CronetLoader
import okhttp3.Interceptor

object Cronet {

    val loader: LoaderInterface? by lazy {
        CronetLoader
    }

    fun preDownload(onComplete: ((Boolean) -> Unit)?) {
        loader?.preDownload(onComplete)
    }

    val interceptor: Interceptor? by lazy {
        CronetInterceptor(cookieJar)
    }

    interface LoaderInterface {

        fun install(): Boolean

        fun preDownload(onComplete: ((Boolean) -> Unit)?)

    }

}