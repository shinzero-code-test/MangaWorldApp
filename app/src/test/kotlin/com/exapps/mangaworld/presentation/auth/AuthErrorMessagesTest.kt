package com.exapps.mangaworld.presentation.auth

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class AuthErrorMessagesTest {

    private val context = mockk<Context>(relaxed = true)

    private fun getString(resId: Int, vararg args: Any): String = context.getString(resId, *args)

    init {
        // Map R.string IDs to their Arabic values for testing
        val testStrings = mapOf(
            com.exapps.mangaworld.R.string.invalid_credentials to "بيانات الدخول غير صحيحة",
            com.exapps.mangaworld.R.string.str_354 to "كلمة المرور لا تفي بمتطلبات الأمان. استخدم كلمة مرور أقوى.",
            com.exapps.mangaworld.R.string.error_retry to "حدث خطأ. حاول مرة أخرى"
        )
        testStrings.forEach { (resId, value) ->
            every { context.getString(resId) } returns value
            every { context.getString(resId, *anyVararg()) } returns value
        }
    }

    @Test
    fun invalidCredentialCodesShowTheSameSignInGuidance() {
        listOf(
            "ERROR_INVALID_LOGIN_CREDENTIALS",
            "ERROR_WRONG_PASSWORD",
            "ERROR_INVALID_CREDENTIAL"
        ).forEach { errorCode ->
            assertEquals("بيانات الدخول غير صحيحة", firebaseAuthErrorMessageForCode(context, errorCode))
        }
    }

    @Test
    fun weakPasswordCodeDoesNotPromiseAProjectSpecificMinimumLength() {
        assertEquals(
            "كلمة المرور لا تفي بمتطلبات الأمان. استخدم كلمة مرور أقوى.",
            firebaseAuthErrorMessageForCode(context, "ERROR_WEAK_PASSWORD")
        )
    }

    @Test
    fun unknownAuthCodeUsesSafeFallbackGuidance() {
        assertEquals("حدث خطأ. حاول مرة أخرى", firebaseAuthErrorMessageForCode(context, "ERROR_NEW_CODE"))
    }
}
