package com.exapps.mangaworld.core.firebase

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles image uploads and deletions via the admin dashboard's Cloudinary API.
 *
 * Dashboard API: POST https://mangaworld-admin.vercel.app/api/cloudinary/app-upload
 * Dashboard API: POST https://mangaworld-admin.vercel.app/api/cloudinary/app-delete
 */
@Singleton
class CloudinaryUploader @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val DASHBOARD_URL = "https://mangaworld-admin.vercel.app"
    }

    data class UploadResult(val url: String, val publicId: String)

    /**
     * Upload an image URI to Cloudinary via the dashboard API.
     * Returns [UploadResult] with the Cloudinary URL and publicId, or null on failure.
     */
    suspend fun uploadImage(uri: Uri, folder: String = "uploads"): UploadResult? {
        return withContext(Dispatchers.IO) {
            try {
                val base64 = uriToBase64(uri) ?: return@withContext null
                val dataUrl = "data:image/jpeg;base64,$base64"

                val url = URL("$DASHBOARD_URL/api/cloudinary/app-upload")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.doOutput = true

                val payload = JSONObject().apply {
                    put("image", dataUrl)
                    put("folder", folder)
                }

                connection.outputStream.use { os ->
                    os.write(payload.toString().toByteArray())
                }

                val responseCode = connection.responseCode
                val responseBody = connection.inputStream.bufferedReader().use { it.readText() }

                if (responseCode == 200) {
                    val json = JSONObject(responseBody)
                    UploadResult(
                        url = json.getString("url"),
                        publicId = json.optString("publicId", "")
                    )
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * Delete an image from Cloudinary by its publicId.
     * Returns true if deletion succeeded.
     */
    suspend fun deleteImage(publicId: String): Boolean {
        if (publicId.isBlank()) return false
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("$DASHBOARD_URL/api/cloudinary/app-delete")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.doOutput = true

                val payload = JSONObject().apply {
                    put("publicId", publicId)
                }

                connection.outputStream.use { os ->
                    os.write(payload.toString().toByteArray())
                }

                val responseCode = connection.responseCode
                responseCode == 200
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * Extract the Cloudinary publicId from a full Cloudinary URL.
     * E.g. "https://res.cloudinary.com/xxx/image/upload/v123/avatars/abc.jpg"
     * → "avatars/abc"
     *
     * Cloudinary URLs follow: /<cloud_name>/<resource_type>/<action>/[v<version>/]<public_id>.<ext>
     * The publicId starts after the version segment (or after "upload/" if no version).
     */
    fun extractPublicId(cloudinaryUrl: String): String? {
        return try {
            val path = URL(cloudinaryUrl).path
            val segments = path.split("/").filter { it.isNotEmpty() }
            // Find the "upload" segment index
            val uploadIdx = segments.indexOf("upload")
            if (uploadIdx < 0 || uploadIdx + 1 >= segments.size) return null

            // After "upload", there may be a version segment (v12345...)
            val afterUpload = segments.drop(uploadIdx + 1)
            val publicIdSegments = if (afterUpload.isNotEmpty() && afterUpload[0].startsWith("v") && afterUpload[0].drop(1).all { it.isDigit() }) {
                afterUpload.drop(1)
            } else {
                afterUpload
            }

            if (publicIdSegments.isEmpty()) return null
            // Join and strip extension
            publicIdSegments.joinToString("/").substringBeforeLast(".")
        } catch (_: Exception) {
            null
        }
    }

    private fun uriToBase64(uri: Uri): String? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
            inputStream.close()

            val maxSize = 800
            val scale = minOf(maxSize.toFloat() / bitmap.width, maxSize.toFloat() / bitmap.height, 1f)
            val resized = if (scale < 1f) {
                Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
            } else bitmap

            val outputStream = ByteArrayOutputStream()
            resized.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
            val bytes = outputStream.toByteArray()
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }
}
