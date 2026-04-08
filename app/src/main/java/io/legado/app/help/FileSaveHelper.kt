package io.legado.app.help

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.legado.app.help.DirectLinkUpload
import io.legado.app.utils.DocumentUtils
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.writeBytes
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object FileSaveHelper {

    fun saveFile(
        context: Context,
        uri: Uri,
        fileName: String,
        data: Any
    ): Uri {
        val bytes = when (data) {
            is File -> data.readBytes()
            is ByteArray -> data
            is String -> data.toByteArray()
            is Bitmap -> {
                val stream = ByteArrayOutputStream()
                data.compress(Bitmap.CompressFormat.PNG, 100, stream)
                stream.toByteArray()
            }
            else -> GSON.toJson(data).toByteArray()
        }
        return if (uri.isContentScheme()) {
            val doc = DocumentFile.fromTreeUri(context, uri)!!
            doc.findFile(fileName)?.delete()
            val newDoc = doc.createFile("", fileName)
            newDoc!!.writeBytes(context, bytes)
            newDoc.uri
        } else {
            val file = File(uri.path ?: uri.toString())
            val newFile = FileUtils.createFileIfNotExist(file, fileName)
            newFile.writeBytes(bytes)
            Uri.fromFile(newFile)
        }
    }

    fun saveFileFromStream(
        context: Context,
        uri: Uri,
        fileName: String,
        input: FileInputStream
    ): Uri {
        return if (uri.isContentScheme()) {
            DocumentFile.fromTreeUri(context, uri)?.let { doc ->
                val imageDoc = DocumentUtils.createFileIfNotExist(doc, fileName)!!
                context.contentResolver.openOutputStream(imageDoc.uri)!!.use { output ->
                    input.copyTo(output)
                }
                imageDoc.uri
            } ?: throw Exception("Failed to get DocumentFile from URI")
        } else {
            val dir = File(uri.path ?: uri.toString())
            val file = FileUtils.createFileIfNotExist(dir, fileName)
            FileOutputStream(file).use { output ->
                input.copyTo(output)
            }
            Uri.fromFile(file)
        }
    }

    suspend fun uploadFile(
        fileName: String,
        file: Any,
        contentType: String
    ): String {
        return DirectLinkUpload.upLoad(fileName, file, contentType)
    }

    suspend fun saveAndUpload(
        context: Context,
        saveUri: Uri?,
        fileName: String,
        data: Any,
        contentType: String,
        upload: Boolean = false
    ): Pair<Uri?, String?> {
        var savedUri: Uri? = null
        var uploadedUrl: String? = null

        if (saveUri != null) {
            savedUri = saveFile(context, saveUri, fileName, data)
        }

        if (upload) {
            uploadedUrl = uploadFile(fileName, data, contentType)
        }

        return Pair(savedUri, uploadedUrl)
    }

}
