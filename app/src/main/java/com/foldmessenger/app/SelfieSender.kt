package com.foldmessenger.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.Log
import com.foldmessenger.app.Ntfy.withAuth
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors

/**
 * Publishes this phone's selfie so the other handsets can show it in the closing
 * question. Titled with the table and seat, which is how the others file it.
 */
object SelfieSender {

    private const val TAG = "SelfieSender"
    private const val MAX_EDGE = 900
    private const val JPEG_QUALITY = 85

    private val executor = Executors.newSingleThreadExecutor()
    private val client = OkHttpClient()

    /** Fire-and-forget; [onResult] is called on a background thread. */
    fun publish(ctx: Context, photo: File, onResult: (Boolean) -> Unit) {
        val table = MessageStore.getActiveTable(ctx)
        val seat = MessageStore.getPhoneId(ctx)
        executor.execute {
            val ok = try {
                val jpeg = downscale(photo) ?: return@execute onResult(false)
                // keep our own copy immediately, so the phone isn't waiting on
                // its own message coming back around
                Faces.save(ctx, table, seat, jpeg)
                val request = Request.Builder()
                    .url("${Config.NTFY_SERVER}/${Config.facesTopic()}")
                    .put(jpeg.toRequestBody())
                    .header("X-Filename", "face_t${table}_p$seat.jpg")
                    .header("X-Title", "face:$table:$seat")
                    .withAuth()
                    .build()
                client.newCall(request).execute().use { it.isSuccessful }
            } catch (e: Exception) {
                Log.w(TAG, "Selfie publish failed: ${e.message}")
                false
            }
            onResult(ok)
        }
    }

    /**
     * Camera photos are far bigger than a small round avatar needs, and the
     * camera records which way up the phone was held as EXIF metadata rather
     * than by rotating the pixels. Re-encoding drops that metadata, so the
     * rotation has to be applied to the pixels here or every selfie arrives on
     * its side.
     */
    private fun applyExifRotation(photo: File, bitmap: Bitmap): Bitmap {
        val degrees = try {
            when (ExifInterface(photo.absolutePath)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read photo orientation: ${e.message}")
            0f
        }
        Log.i(TAG, "Selfie orientation: ${degrees.toInt()}deg")
        if (degrees == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun downscale(photo: File): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(photo.absolutePath, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > MAX_EDGE * 2 || bounds.outHeight / sample > MAX_EDGE * 2) {
            sample *= 2
        }
        val decoded = BitmapFactory.decodeFile(
            photo.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return null
        val scale = MAX_EDGE.toFloat() / maxOf(decoded.width, decoded.height)
        val sized = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true
            )
        } else {
            decoded
        }
        val bitmap = applyExifRotation(photo, sized)
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.toByteArray()
        }
    }
}
