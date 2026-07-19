package com.exapps.mangaworld.presentation.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class AuthErrorMessagesTest {

    @Test
    fun invalidCredentialCodesShowTheSameSignInGuidance() {
        // Regression: Firebase SDK messages are mutable, but its machine error codes are stable.
        listOf(
            "ERROR_INVALID_LOGIN_CREDENTIALS",
            "ERROR_WRONG_PASSWORD",
            "ERROR_INVALID_CREDENTIAL"
        ).forEach { errorCode ->
            assertEquals("بيانات الدخول غير صحيحة", firebaseAuthErrorMessageForCode(errorCode))
        }
    }

    @Test
    fun weakPasswordCodeDoesNotPromiseAProjectSpecificMinimumLength() {
        assertEquals(
            "كلمة المرور لا تفي بمتطلبات الأمان. استخدم كلمة مرور أقوى.",
            firebaseAuthErrorMessageForCode("ERROR_WEAK_PASSWORD")
        )
    }

    @Test
    fun unknownAuthCodeUsesSafeFallbackGuidance() {
        assertEquals("حدث خطأ. حاول مرة أخرى", firebaseAuthErrorMessageForCode("ERROR_NEW_CODE"))
    }
}
