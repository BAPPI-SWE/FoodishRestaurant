package com.yumzy.restaurant.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.ktx.storage
import kotlinx.coroutines.tasks.await
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.*

object ImageUploadHelper {

    private val storage = Firebase.storage

    private const val COMPRESSION_QUALITY = 80
    private const val MAX_SUBCATEGORY_SIZE = 150
    private const val MAX_STORE_ITEM_SIZE = 600
    private const val MAX_RESTAURANT_SIZE = 400

    suspend fun uploadImage(context: Context, uri: Uri, folder: String, imageType: ImageType): String {
        val filename = "${UUID.randomUUID()}.jpg"
        val storageRef = storage.reference.child("$folder/$filename")

        val resizedBitmap = when (imageType) {
            ImageType.SUBCATEGORY -> resizeImage(context, uri, MAX_SUBCATEGORY_SIZE)
            ImageType.STORE_ITEM -> resizeImage(context, uri, MAX_STORE_ITEM_SIZE)
            ImageType.RESTAURANT -> resizeImage(context, uri, MAX_RESTAURANT_SIZE)
        }

        val byteArrayOutputStream = ByteArrayOutputStream()
        resizedBitmap.compress(Bitmap.CompressFormat.JPEG, COMPRESSION_QUALITY, byteArrayOutputStream)
        val imageData = byteArrayOutputStream.toByteArray()

        storageRef.putBytes(imageData).await()
        return storageRef.downloadUrl.await().toString()
    }

    suspend fun replaceImage(
        context: Context,
        oldImageUrl: String?,
        newImageUri: Uri,
        folder: String,
        imageType: ImageType
    ): String {
        oldImageUrl?.let { deleteImage(it) }
        return uploadImage(context, newImageUri, folder, imageType)
    }

    suspend fun deleteImage(imageUrl: String) {
        try {
            if (imageUrl.isNotBlank() && imageUrl.contains("firebase")) {
                val storageRef = storage.getReferenceFromUrl(imageUrl)
                storageRef.delete().await()
            }
        } catch (e: Exception) {
            // Ignore errors
        }
    }

    private fun resizeImage(context: Context, uri: Uri, maxSize: Int): Bitmap {
        var inputStream: InputStream? = null
        try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }

            inputStream = context.contentResolver.openInputStream(uri)
            BitmapFactory.decodeStream(inputStream, null, options)
            inputStream?.close()

            options.inSampleSize = calculateInSampleSize(options, maxSize, maxSize)
            options.inJustDecodeBounds = false

            inputStream = context.contentResolver.openInputStream(uri)
            var bitmap = BitmapFactory.decodeStream(inputStream, null, options)
            inputStream?.close()

            bitmap = rotateImageIfRequired(context, bitmap!!, uri)
            return scaleBitmap(bitmap, maxSize)

        } catch (e: Exception) {
            throw RuntimeException("Error resizing image: ${e.message}")
        } finally {
            inputStream?.close()
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.run { outHeight to outWidth }
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun scaleBitmap(bitmap: Bitmap, maxSize: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= maxSize && height <= maxSize) {
            return bitmap
        }
        val scale = if (width > height) {
            maxSize.toFloat() / width
        } else {
            maxSize.toFloat() / height
        }
        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    private fun rotateImageIfRequired(context: Context, bitmap: Bitmap, uri: Uri): Bitmap {
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
            val exif = inputStream?.let { ExifInterface(it) }
            val orientation = exif?.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            return when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> rotateBitmap(bitmap, 90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> rotateBitmap(bitmap, 180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> rotateBitmap(bitmap, 270f)
                else -> bitmap
            }
        } catch (e: Exception) {
            return bitmap
        }
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    enum class ImageType {
        SUBCATEGORY,
        STORE_ITEM,
        RESTAURANT
    }
}