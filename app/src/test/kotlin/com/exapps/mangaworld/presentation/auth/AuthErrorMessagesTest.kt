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
            com.exapps.mangaworld.R.string.no_account_email to "S-no-account",
            com.exapps.mangaworld.R.string.str_241 to "S-disabled",
            com.exapps.mangaworld.R.string.str_432 to "S-in-use",
            com.exapps.mangaworld.R.string.str_354 to "كلمة المرور لا تفي بمتطلبات الأمان. استخدم كلمة مرور أقوى.",
            com.exapps.mangaworld.R.string.invalid_email to "S-bad-email",
            com.exapps.mangaworld.R.string.str_215 to "S-network",
            com.exapps.mangaworld.R.string.many_attempts_try_later to "S-lockout",
            com.exapps.mangaworld.R.string.str_307 to "S-not-allowed",
            com.exapps.mangaworld.R.string.error_retry to "حدث خطأ. حاول مرة أخرى",
            com.exapps.mangaworld.R.string.str_456 to "S-merge-exists",
            com.exapps.mangaworld.R.string.str_209 to "S-merge-used",
            com.exapps.mangaworld.R.string.str_433 to "S-merge-email",
            com.exapps.mangaworld.R.string.auth_error_provider_linked to "S-merge-unknown"
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
        assertEquals("حدث خطأ. حاول مرة أخرى", firebaseAuthErrorMessageForCode(context, null))
    }

    @Test
    fun everyKnownCodeMapsToDistinctGuidance() {
        // Forces each resId to be stubbed above: the relaxed mock returns ""
        // for anything unstubbed, so a missing stub fails here, not silently.
        val codes = mapOf(
            "ERROR_USER_NOT_FOUND" to "S-no-account",
            "ERROR_USER_DISABLED" to "S-disabled",
            "ERROR_EMAIL_ALREADY_IN_USE" to "S-in-use",
            "ERROR_INVALID_EMAIL" to "S-bad-email",
            "ERROR_NETWORK_REQUEST_FAILED" to "S-network",
            "ERROR_TOO_MANY_REQUESTS" to "S-lockout",
            "ERROR_OPERATION_NOT_ALLOWED" to "S-not-allowed"
        )
        for ((code, expected) in codes) {
            assertEquals(code, expected, firebaseAuthErrorMessageForCode(context, code))
        }
    }

    @Test
    fun exceptionOverloadMapsTypesBeforeCodes() {
        assertEquals(
            "S-network",
            firebaseAuthErrorMessage(context, mockk<com.google.firebase.FirebaseNetworkException>())
        )
        assertEquals(
            "S-lockout",
            firebaseAuthErrorMessage(context, mockk<com.google.firebase.FirebaseTooManyRequestsException>())
        )
        assertEquals(
            "حدث خطأ. حاول مرة أخرى",
            firebaseAuthErrorMessage(context, IllegalStateException("boom"))
        )
    }

    @Test
    fun mergeReasonsMapToGuidance() {
        val C = com.exapps.mangaworld.core.firebase.AccountMergeReason
        assertEquals("S-merge-exists", accountMergeMessage(context, C.ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL))
        assertEquals("S-merge-used", accountMergeMessage(context, C.CREDENTIAL_ALREADY_IN_USE))
        assertEquals("S-merge-email", accountMergeMessage(context, C.EMAIL_ALREADY_IN_USE))
        assertEquals("S-merge-unknown", accountMergeMessage(context, C.UNKNOWN))
    }
}
