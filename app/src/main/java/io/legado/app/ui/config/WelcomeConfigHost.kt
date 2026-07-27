package io.legado.app.ui.config

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.net.Uri
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.dialogs.selector
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.file.registerHandleFile
import io.legado.app.ui.widget.number.showNumberPicker
import io.legado.app.utils.FileUtils
import io.legado.app.utils.externalFiles
import io.legado.app.utils.readUri
import io.legado.app.utils.resizeAndRecycle
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 启动界面设置宿主（原 WelcomeConfigFragment 壳上浮）。
 * 图片裁剪/落盘与选择器逻辑逐字保留；动态 summary（启动时长、背景图路径）用 Compose state 承接。
 */
class WelcomeConfigHost(activity: ConfigActivity) : ConfigHost(activity) {

    private val requestWelcomeImage = 221
    private val requestWelcomeImageDark = 222

    private var showTimeSummary by mutableStateOf("")
    private var imageSummary by mutableStateOf("")
    private var imageDarkSummary by mutableStateOf("")

    private val selectImage by lazy {
        activity.registerHandleFile { result ->
            result.uri?.let { uri ->
                when (result.requestCode) {
                    requestWelcomeImage -> setCoverFromUri(PreferKey.welcomeImage, uri)
                    requestWelcomeImageDark -> setCoverFromUri(PreferKey.welcomeImageDark, uri)
                }
            }
        }
    }

    init {
        showTimeSummary = "${AppConfig.welcomeShowTime}ms"
        imageSummary = imagePathSummary(AppConfig.welcomeImage)
        imageDarkSummary = imagePathSummary(AppConfig.welcomeImageDark)
    }

    @Composable
    override fun Content() {
        Column(Modifier.fillMaxSize()) {
            AppTitleBar(
                title = stringResource(R.string.welcome_style),
                onBack = { activity.finish() },
            )
            Box(Modifier.weight(1f)) {
                WelcomeConfigScreen(
                    onShowTime = ::pickShowTime,
                    onPickImage = ::onPickImage,
                    showTimeSummary = showTimeSummary,
                    imageSummary = imageSummary,
                    imageDarkSummary = imageDarkSummary,
                )
            }
        }
    }

    private fun pickShowTime() {
        showNumberPicker(
            activity,
            titleResId = R.string.welcome_show_time,
            max = 3000, min = 0, value = AppConfig.welcomeShowTime
        ) {
            AppConfig.welcomeShowTime = it
            showTimeSummary = "${it}ms"
        }
    }

    @SuppressLint("PrivateResource")
    private fun onPickImage(isNight: Boolean) {
        val key = if (isNight) PreferKey.welcomeImageDark else PreferKey.welcomeImage
        val currentPath = getImagePref(key)
        if (currentPath.isNullOrEmpty()) {
            selectImage(key)
        } else {
            activity.selector(
                items = arrayListOf(
                    activity.getString(R.string.delete),
                    activity.getString(R.string.select_image)
                )
            ) { _, i ->
                if (i == 0) {
                    putImagePref(key, null)
                    val file = File(currentPath)
                    if (file.exists()) file.delete()
                    upImageSummary(key)
                } else {
                    selectImage(key)
                }
            }
        }
    }

    private fun selectImage(key: String) {
        selectImage.launch {
            requestCode = if (key == PreferKey.welcomeImageDark) requestWelcomeImageDark
            else requestWelcomeImage
            mode = HandleFileContract.IMAGE
        }
    }

    private fun getImagePref(key: String): String? =
        if (key == PreferKey.welcomeImageDark) AppConfig.welcomeImageDark else AppConfig.welcomeImage

    private fun putImagePref(key: String, value: String?) {
        if (key == PreferKey.welcomeImageDark) AppConfig.welcomeImageDark = value
        else AppConfig.welcomeImage = value
    }

    private fun imagePathSummary(value: String?): String {
        return if (value.isNullOrBlank()) activity.getString(R.string.select_image) else value
    }

    private fun upImageSummary(key: String) {
        val summary = imagePathSummary(getImagePref(key))
        if (key == PreferKey.welcomeImageDark) imageDarkSummary = summary
        else imageSummary = summary
    }

    private fun setCoverFromUri(preferenceKey: String, uri: Uri) {
        // 删除旧图片
        getImagePref(preferenceKey)?.let {
            val file = File(it)
            if (file.exists()) file.delete()
        }
        activity.readUri(uri) { _, inputStream ->
            runCatching {
                val windowManager =
                    activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val displayMetrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealMetrics(displayMetrics)
                val screenWidth: Int = displayMetrics.widthPixels
                val screenHeight: Int = displayMetrics.heightPixels

                // 使用BitmapFactory.Options来获取图片尺寸
                val op = BitmapFactory.Options()
                op.inJustDecodeBounds = true
                BitmapFactory.decodeStream(inputStream, null, op)

                val originalWidth = op.outWidth
                val originalHeight = op.outHeight
                val originalRatio = originalWidth.toFloat() / originalHeight
                val screenRatio = screenWidth.toFloat() / screenHeight
                val cropW: Int
                val cropH: Int
                if (originalRatio > screenRatio) {
                    cropH = originalHeight
                    cropW = (originalHeight * screenRatio).toInt()
                } else {
                    cropW = originalWidth
                    cropH = (originalWidth / screenRatio).toInt()
                }

                val startX = (originalWidth - cropW) / 2
                val startY = (originalHeight - cropH) / 2

                // 重新打开流来解码图片
                activity.readUri(uri) { _, newInputStream ->
                    @Suppress("DEPRECATION")
                    val decoder = BitmapRegionDecoder.newInstance(newInputStream, false)
                        ?: throw Exception("Failed to create BitmapRegionDecoder")
                    val rect = Rect(startX, startY, startX + cropW, startY + cropH)
                    val decodeOptions = BitmapFactory.Options()
                    var inSampleSize = 1
                    while (cropW / (inSampleSize * 2) >= screenWidth && cropH / (inSampleSize * 2) >= screenHeight) {
                        inSampleSize *= 2
                    }
                    decodeOptions.inSampleSize = inSampleSize
                    val bitmap = decoder.decodeRegion(rect, decodeOptions)
                        ?: throw Exception("Failed to decode region")
                    val scaledBitmap = bitmap.resizeAndRecycle(screenWidth, screenHeight)
                    ByteArrayOutputStream().use { webpData ->
                        val format =
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                                Bitmap.CompressFormat.WEBP_LOSSY
                            } else {
                                @Suppress("DEPRECATION")
                                Bitmap.CompressFormat.WEBP
                            }
                        scaledBitmap.compress(format, 80, webpData)
                        val finalBytes = webpData.toByteArray()
                        val fileName = "${System.currentTimeMillis()}.webp"
                        val file = FileUtils.createFileIfNotExist(
                            activity.externalFiles, "covers", fileName
                        )
                        file.outputStream().use {
                            it.write(finalBytes)
                        }
                        putImagePref(preferenceKey, file.absolutePath)
                    }
                    scaledBitmap.recycle()
                }
            }.onFailure {
                it.printStackTrace()
                appCtx.toastOnUi(it.localizedMessage)
            }
            // 落盘/删除后刷新 summary（复刻 onSharedPreferenceChanged）
            upImageSummary(preferenceKey)
        }
    }
}
