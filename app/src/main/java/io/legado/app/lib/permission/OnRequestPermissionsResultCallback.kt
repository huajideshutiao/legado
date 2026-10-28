package io.legado.app.lib.permission

interface OnRequestPermissionsResultCallback {

    /** 发起本次权限请求的唯一标识: 回传按它绑定原请求。 */
    val id: Long

    fun onRequestPermissionsResult(permissions: Array<String>, grantResults: IntArray)

    fun onSettingActivityResult()

    fun onError(e: Exception)
}
