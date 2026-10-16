package com.knowapp.android.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.util.UUID

// Copies a picked photo into app storage, shrunk and upright, ready to upload
class PhotoStore(context: Context) {
    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, "photos").apply { mkdirs() }

    fun import(uri: Uri): String? {
        return try {
            val resolver = appContext.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / sample > 3200 || bounds.outHeight / sample > 3200) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
            val rotation = resolver.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
            val upright = if (rotation == 0f) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(rotation) }, true)
            val longest = maxOf(upright.width, upright.height)
            val scaled = if (longest > 1600) Bitmap.createScaledBitmap(upright, upright.width * 1600 / longest, upright.height * 1600 / longest, true) else upright
            val target = File(dir, "${UUID.randomUUID()}.jpg")
            target.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 78, it) }
            target.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    fun delete(path: String?) {
        if (path != null) File(path).delete()
    }
}
