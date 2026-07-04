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
 * Handles image uploads via the admin dashboard's Cloudinary API.
 * 
 * The Cloudinary API keys are stored in the dashboard's environment,
 * NOT in the app code. This class uploads images to the dashboard's
 * API endpoint which handles the actual Cloudinary upload.
 * 
 * Dashboard API: POST https://mangaworld-admin.vercel.app/api/cloudinary/app-upload
 */
@Singleton
class CloudinaryUploader @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        // Dashboard URL — the app calls this endpoint which proxies to Cloudinary
        private const val DASHBOARD_URL = "https://mangaworld-admin.vercel.app"
    }

    /**
     * Upload an image URI to Cloudinary via the dashboard API.
     * Returns the Cloudinary URL of the uploaded image.
     */
    suspend fun uploadImage(uri: Uri, folder: String = "uploads"): String? {
        return withContext(Dispatchers.IO) {
            try {
                val base64 = uriToBase64(uri) ?: return@withContext null
                val dataUrl = "data:image/jpeg;base64,$base64"

                // Call dashboard API
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
                    json.getString("url")
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun uriToBase64(uri: Uri): String? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
            inputStream.close()

            // Resize if too large (max 800px)
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
