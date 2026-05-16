package io.legado.app.ui.config

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import java.io.FileOutputStream
import java.util.Date

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
        val key = preference.key
        val tmp = {
            selectImage.launch {
                requestCode =
                    if (preference.key == PreferKey.welcomeImageDark) requestWelcomeImageDark
                    else requestWelcomeImage
                mode = HandleFileContract.IMAGE
            }
        }
        if (preference.key != PreferKey.welcomeImageDark && preference.key != PreferKey.welcomeImage)
            return super.onPreferenceTreeClick(preference)
        if (getPrefString(key).isNullOrEmpty()) tmp()
        else {
            context?.selector(
                items = arrayListOf(getString(R.string.delete), getString(R.string.select_image))
            ) { _, i ->
                if (i == 0) {
                    removePref(key)
                    val file = File(FileUtils.getPath(appCtx.externalFiles, key))
                    if (file.exists()) file.delete()
                } else tmp()
            }
        }
        return super.onPreferenceTreeClick(preference)
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

            else -> preference.summary = value
        }
    }

    private fun setCoverFromUri(preferenceKey: String, uri: Uri) {
        readUri(uri) { _, inputStream ->
            runCatching {
                val windowManager =
                    requireContext().getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val displayMetrics = DisplayMetrics()
                windowManager.defaultDisplay.getRealMetrics(displayMetrics)
                val screenWidth: Int = displayMetrics.widthPixels
                val screenHeight: Int = displayMetrics.heightPixels

                // 使用BitmapFactory.Options来优化图片解码，避免加载整个图片
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

                // 重新打开流来解码图片
                readUri(uri) { _, newInputStream ->
                    op.inJustDecodeBounds = false
                    val originalBitmap = BitmapFactory.decodeStream(newInputStream, null, op)
                        ?: throw IllegalArgumentException("Failed to decode image from Uri")
                    val startX = (originalWidth - cropW) / 2
                    val startY = (originalHeight - cropH) / 2
                    val croppedBitmap =
                        Bitmap.createBitmap(originalBitmap, startX, startY, cropW, cropH)
                    val scaledBitmap = croppedBitmap.resizeAndRecycle(screenWidth, screenHeight)
                    if (originalBitmap != croppedBitmap) originalBitmap.recycle()
                    ByteArrayOutputStream().use { webpData ->
                        scaledBitmap.compress(Bitmap.CompressFormat.WEBP, 80, webpData)
                        val finalBytes = webpData.toByteArray()
                        val fileName = Date().time.toString() + ".webp"
                        val file = FileUtils.createFileIfNotExist(
                            requireContext().externalFiles, "covers", fileName
                        )
                        FileOutputStream(file).use {
                            it.write(finalBytes)
                        }
                        putPrefString(preferenceKey, file.absolutePath)
                    }
                    scaledBitmap.recycle()
                }
            }.onFailure { appCtx.toastOnUi(it.localizedMessage) }
        }
    }
}