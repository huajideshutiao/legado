package io.legado.app.help.http

import kotlinx.coroutines.suspendCancellableCoroutine
import okio.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OkHttp 工具扩展 (commonMain)。
 *
 * KP4 OkHttp 跨平台修复: 原直接 `import okhttp3.*`,
 * iOS/鸿蒙 target 无 OkHttp 变体编译失败; 现改用 [KmpHttpClient]/[KmpRequestBuilder]/[KmpResponse]
 * 等跨平台抽象 (jvmAndAndroidMain 经 typealias 等价 okhttp3.*; iOS/鸿蒙 stub)。
 * jvm/android 行为与原实现完全一致 (零 diff)。
 */

suspend fun KmpHttpClient.newCallResponse(
    retry: Int = 0,
    builder: KmpRequestBuilder.() -> Unit
): KmpResponse {
    val requestBuilder = KmpRequestBuilder()
    requestBuilder.apply(builder)
    var response: KmpResponse? = null
    for (i in 0..retry) {
        response = newCall(requestBuilder.build()).await()
        if (response.isSuccessful) {
            return response
        }
    }
    return response!!
}

suspend fun KmpHttpClient.newCallResponseBody(
    retry: Int = 0,
    builder: KmpRequestBuilder.() -> Unit
): KmpResponseBody {
    return newCallResponse(retry, builder).body
}

suspend fun KmpCall.await(): KmpResponse = suspendCancellableCoroutine { block ->

    block.invokeOnCancellation {
        cancel()
    }

    enqueue(object : KmpCallback {
        override fun onFailure(call: KmpCall, e: IOException) {
            block.resumeWithException(e)
        }

        override fun onResponse(call: KmpCall, response: KmpResponse) {
            block.resume(response)
        }
    })

}

fun KmpRequestBuilder.addHeaders(headers: Map<String, String>) {
    headers.forEach {
        addHeader(it.key, it.value)
    }
}

fun KmpRequestBuilder.get(url: String, queryMap: Map<String, String>, encoded: Boolean = false) {
    val httpBuilder = url.toKmpHttpUrl().newBuilder()
    queryMap.forEach {
        if (encoded) {
            httpBuilder.addEncodedQueryParameter(it.key, it.value)
        } else {
            httpBuilder.addQueryParameter(it.key, it.value)
        }
    }
    url(httpBuilder.build())
}

fun KmpRequestBuilder.get(url: String, encodedQuery: String?) {
    val httpBuilder = url.toKmpHttpUrl().newBuilder()
    httpBuilder.encodedQuery(encodedQuery)
    url(httpBuilder.build())
}

private val formContentType = "application/x-www-form-urlencoded".toKmpMediaType()

fun KmpRequestBuilder.postForm(encodedForm: String) {
    post(encodedForm.toKmpRequestBody(formContentType))
}

@Suppress("unused")
fun KmpRequestBuilder.postForm(form: Map<String, String>, encoded: Boolean = false) {
    val formBody = KmpFormBodyBuilder()
    form.forEach {
        if (encoded) {
            formBody.addEncoded(it.key, it.value)
        } else {
            formBody.add(it.key, it.value)
        }
    }
    // 协变返回类型限制: FormBody.Builder.build() 返回 FormBody (子类),
    // 与 expect class KmpFormBodyBuilder 声明的 KmpRequestBody (父类) 冲突,
    // 改用扩展函数 buildKmpRequestBody() 绕过 (jvmAndAndroidMain 内部 this.build())
    post(formBody.buildKmpRequestBody())
}

fun KmpRequestBuilder.postJson(json: String?) {
    json?.let {
        val requestBody = json.toKmpRequestBody("application/json; charset=UTF-8".toKmpMediaType())
        post(requestBody)
    }
}
