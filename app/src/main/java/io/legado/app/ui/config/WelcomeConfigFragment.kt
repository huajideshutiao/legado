package io.legado.app.ui.config

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.View
import android.view.WindowManager
import androidx.preference.Preference
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.prefs.fragment.PreferenceFragment
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.file.registerHandleFile
import io.legado.app.ui.widget.number.showNumberPicker
import io.legado.app.utils.FileUtils
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import io.legado.app.utils.readUri
import io.legado.app.utils.removePref
import io.legado.app.utils.resizeAndRecycle
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx
import java.io.ByteArrayOutputStream
import java.io.File

class WelcomeConfigFragment : PreferenceFragment(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private val requestWelcomeImage = 221
    private val requestWelcomeImageDark = 222
    private val selectImage by lazy {
        registerHandleFile { result ->
            result.uri?.let { uri ->
                when (result.requestCode) {
                requestWelcomeImage -> setCoverFromUri(PreferKey.welcomeImage, uri)
                requestWelcomeImageDark -> setCoverFromUri(PreferKey.welcomeImageDark, uri)
            }
        }
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_config_welcome)
        upPreferenceSummary(PreferKey.welcomeImage, AppConfig.welcomeImage)
        upPreferenceSummary(PreferKey.welcomeImageDark, AppConfig.welcomeImageDark)
        upPreferenceSummary(PreferKey.welcomeDelay, AppConfig.welcomeDelay.toString())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.setTitle(R.string.welcome_style)
        listView.setEdgeEffectColor(primaryColor)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        sharedPreferences ?: return
        key ?: return
        if (key == PreferKey.welcomeImage || key == PreferKey.welcomeImageDark)
            upPreferenceSummary(key, getPrefString(key))
    }

    @SuppressLint("PrivateResource")
    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        when (val key = preference.key) {
            PreferKey.welcomeDelay -> {
                showNumberPicker(
                    requireContext(),
                    titleResId = R.string.welcome_delay,
                    max = 3000, min = 0, value = AppConfig.welcomeDelay
                ) {
                    AppConfig.welcomeDelay = it
                    upPreferenceSummary(PreferKey.welcomeDelay, it.toString())
                }
                return true
            }

            PreferKey.welcomeImage,
            PreferKey.welcomeImageDark -> {
                val currentPath = getPrefString(key)
                if (currentPath.isNullOrEmpty()) {
                    selectImage(key)
                } else {
                    context?.selector(
                        items = arrayListOf(
                            getString(R.string.delete),
                            getString(R.string.select_image)
                        )
                    ) { _, i ->
                        if (i == 0) {
                            removePref(key)
                            val file = File(currentPath)
                            if (file.exists()) file.delete()
                        } else {
                            selectImage(key)
                        }
                    }
                }
                return true
            }
        }
        return super.onPreferenceTreeClick(preference)
    }

    private fun selectImage(key: String) {
        selectImage.launch {
            requestCode = if (key == PreferKey.welcomeImageDark) requestWelcomeImageDark
            else requestWelcomeImage
            mode = HandleFileContract.IMAGE
        }
    }

    private fun upPreferenceSummary(preferenceKey: String, value: String?) {
        val preference = findPreference<Preference>(preferenceKey) ?: return
        when (preferenceKey) {
            PreferKey.welcomeImage,
            PreferKey.welcomeImageDark -> preference.summary = if (value.isNullOrBlank()) {
                getString(R.string.select_image)
            } else {
                value
            }
            PreferKey.welcomeDelay -> preference.summary = "${value}ms"

            else -> preference.summary = value
        }
    }

    private fun setCoverFromUri(preferenceKey: String, uri: Uri) {
        // 删除旧图片
        getPrefString(preferenceKey)?.let {
            val file = File(it)
            if (file.exists()) file.delete()
        }
        readUri(uri) { _, inputStream ->
            runCatching {
                val windowManager =
                    requireContext().getSystemService(Context.WINDOW_SERVICE) as WindowManager
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
                readUri(uri) { _, newInputStream ->
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
                            requireContext().externalFiles, "covers", fileName
                        )
                        file.outputStream().use {
                            it.write(finalBytes)
                        }
                        putPrefString(preferenceKey, file.absolutePath)
                    }
                    scaledBitmap.recycle()
                }
            }.onFailure {
                it.printStackTrace()
                appCtx.toastOnUi(it.localizedMessage)
            }
        }
    }
}