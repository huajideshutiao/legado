package io.legado.app.constant

import android.content.pm.PackageManager
import androidx.annotation.Keep
import io.legado.app.BuildConfig
import io.legado.app.help.update.AppVariant
import splitties.init.appCtx
import java.security.MessageDigest

/**
 * AppConst 安卓半区(appCtx/BuildConfig 绑定, 进不了 commonMain)。
 * 以同包扩展属性挂在 [AppConst] 上, 调用处 AppConst.xxx 写法不变。
 */

private const val OFFICIAL_SIGNATURE =
    "9A2D6A3D1FFF3F92885DC7184034B24661E05073ECE0B3D62BAEB51555C1BF94"
private const val BETA_SIGNATURE =
    "93A28468B0F69E8D14C8A99AB45841CEF902BBBA3761BBFEE02E67CBA801563E"

@Keep
data class AppInfo(
    var versionCode: Long = 0L,
    var versionName: String = "",
    var appVariant: AppVariant = AppVariant.UNKNOWN
)

private val appInfoInternal: AppInfo by lazy {
    val appInfo = AppInfo()
    @Suppress("DEPRECATION")
    appCtx.packageManager.getPackageInfo(appCtx.packageName, PackageManager.GET_ACTIVITIES)
        ?.let {
            appInfo.versionName = it.versionName!!
            appInfo.appVariant = when {
                it.packageName.contains("releaseA") -> AppVariant.BETA_RELEASEA
                isBeta -> AppVariant.BETA_RELEASE
                isOfficial -> AppVariant.OFFICIAL
                else -> AppVariant.UNKNOWN
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                appInfo.versionCode = it.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                appInfo.versionCode = it.versionCode.toLong()
            }
        }
    appInfo
}

val AppConst.appInfo: AppInfo get() = appInfoInternal

@Suppress("DEPRECATION")
private val sha256Signature: String by lazy {
    val packageInfo =
        appCtx.packageManager.getPackageInfo(appCtx.packageName, PackageManager.GET_SIGNATURES)
    MessageDigest.getInstance("SHA-256")
        .digest(packageInfo.signatures!![0].toByteArray())
        .joinToString("") { "%02x".format(it) }
        .uppercase()
}

private val isOfficial get() = sha256Signature == OFFICIAL_SIGNATURE

private val isBeta get() = sha256Signature == BETA_SIGNATURE || BuildConfig.DEBUG

/**
 * The authority of a FileProvider defined in a <provider> element in your app's manifest.
 */
val AppConst.authority: String get() = BuildConfig.APPLICATION_ID + ".fileProvider"
