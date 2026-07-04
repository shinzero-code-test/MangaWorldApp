package com.exapps.mangaworld.core.firebase

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles image uploads via the admin dashboard's Cloudinary API.
 * 
 * The Cloudinary API keys are stored in the dashboard's .env.local file,
 * NOT in the app code. This class calls the dashboard's API endpoint
 * which handles the actual Cloudinary upload.
 * 
 * Dashboard API: POST /api/cloudinary/upload
 *   Body: { "image": "data:image/jpeg;base64,...", "folder": "avatars" }
 *   Response: { "url": "https://res.cloudinary.com/...", "publicId": "..." }
 */
@Singleton
class CloudinaryUploader @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * Upload an image URI to Cloudinary via the dashboard API.
     * Returns the Cloudinary URL of the uploaded image.
     */
    suspend fun uploadImage(uri: Uri, folder: String = "uploads"): String? {
        return try {
            // Convert URI to base64
            val base64 = uriToBase64(uri) ?: return null
            val dataUrl = "data:image/jpeg;base64,$base64"

            // Call dashboard API
            val functions = FirebaseFunctions.getInstance()
            val result = functions
                .getHttpsCallable("cloudinaryUpload")
                .call(hashMapOf("image" to dataUrl, "folder" to folder))
                .await()
                .data as? Map<*, *>

            result?.get("url") as? String
        } catch (e: Exception) {
            // Fallback: use Firebase Storage directly
            uploadToFirebaseStorage(uri, folder)
        }
    }

    /**
     * Fallback: upload to Firebase Storage if dashboard API fails.
     */
    private suspend fun uploadToFirebaseStorage(uri: Uri, folder: String): String? {
        return try {
            val storageRef = com.google.firebase.storage.FirebaseStorage.getInstance()
                .reference
                .child("$folder/${System.currentTimeMillis()}.jpg")
            storageRef.putFile(uri).await()
            storageRef.downloadUrl.await().toString()
        } catch (e: Exception) {
            null
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
