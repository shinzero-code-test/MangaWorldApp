package com.exapps.mangaworld.core.firebase

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "CloudinaryUploader"

/**
 * Handles image uploads and deletions via the admin dashboard's Cloudinary API.
 * Uses injected OkHttpClient for connection pooling, caching, and interceptor support.
 *
 * Dashboard API: POST https://mangaworld-admin.vercel.app/api/cloudinary/app-upload
 * Dashboard API: POST https://mangaworld-admin.vercel.app/api/cloudinary/app-delete
 */
@Singleton
class CloudinaryUploader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionManager: FirebaseSessionManager,
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val DASHBOARD_URL = "https://mangaworld-admin.vercel.app"
        private const val MAX_IMAGE_BYTES = 5 * 1024 * 1024
    }

    data class UploadResult(val url: String, val publicId: String)

    /**
     * Upload an image URI to Cloudinary via the dashboard API.
     * Returns [UploadResult] with the Cloudinary URL and publicId, or null on failure.
     */
    suspend fun uploadImage(uri: Uri, assetType: String = "avatar"): UploadResult? {
        return withContext(Dispatchers.IO) {
            try {
                val base64 = uriToBase64(uri) ?: return@withContext null
                val token = sessionManager.currentIdToken() ?: return@withContext null
                val dataUrl = "data:image/jpeg;base64,$base64"

                val payload = JSONObject().apply {
                    put("image", dataUrl)
                    put("assetType", assetType)
                }

                val request = Request.Builder()
                    .url("$DASHBOARD_URL/api/cloudinary/app-upload")
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .addHeader("Authorization", "Bearer $token")
                    .build()

                val response = okHttpClient.newCall(request).execute()
                response.use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty()
                        val json = JSONObject(body)
                        UploadResult(
                            url = json.getString("url"),
                            publicId = json.optString("publicId", "")
                        )
                    } else {
                        Log.w(TAG, "Upload failed: ${resp.code}")
                        null
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Upload error: ${e.message}")
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
                val token = sessionManager.currentIdToken() ?: return@withContext false
                val payload = JSONObject().apply { put("publicId", publicId) }

                val request = Request.Builder()
                    .url("$DASHBOARD_URL/api/cloudinary/app-delete")
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .addHeader("Authorization", "Bearer $token")
                    .build()

                okHttpClient.newCall(request).execute().use { it.isSuccessful }
            } catch (e: Exception) {
                Log.w(TAG, "Delete error: ${e.message}")
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
            val bitmap = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                android.graphics.BitmapFactory.decodeStream(inputStream)
            } ?: return null

            val maxSize = 800
            val scale = minOf(maxSize.toFloat() / bitmap.width, maxSize.toFloat() / bitmap.height, 1f)
            val resized = if (scale < 1f) Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt(),
                (bitmap.height * scale).toInt(),
                true
            ) else bitmap
            try {
                val bytes = ByteArrayOutputStream().use { outputStream ->
                    resized.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
                    outputStream.toByteArray()
                }
                if (bytes.size > MAX_IMAGE_BYTES) null else Base64.encodeToString(bytes, Base64.NO_WRAP)
            } finally {
                if (resized !== bitmap) resized.recycle()
                bitmap.recycle()
            }
        } catch (e: Exception) {
            null
        }
    }
}
