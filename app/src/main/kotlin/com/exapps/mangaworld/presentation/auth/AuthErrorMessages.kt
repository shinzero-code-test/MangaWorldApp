package com.exapps.mangaworld.presentation.auth
import android.content.Context
import com.exapps.mangaworld.R

import com.exapps.mangaworld.core.firebase.AccountMergeReason
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuthException

internal fun accountMergeMessage(context: Context, reason: AccountMergeReason): String =
    when (reason) {
        AccountMergeReason.ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL ->
            context.getString(R.string.str_456)
        AccountMergeReason.CREDENTIAL_ALREADY_IN_USE ->
            context.getString(R.string.str_209)
        AccountMergeReason.EMAIL_ALREADY_IN_USE ->
            context.getString(R.string.str_433)
        AccountMergeReason.UNKNOWN ->
            context.getString(R.string.auth_error_provider_linked)
    }

internal fun firebaseAuthErrorMessage(context: Context, error: Exception): String =
    when (error) {
        is FirebaseNetworkException -> firebaseAuthErrorMessageForCode(context, ERROR_NETWORK_REQUEST_FAILED)
        is FirebaseTooManyRequestsException -> firebaseAuthErrorMessageForCode(context, ERROR_TOO_MANY_REQUESTS)
        is FirebaseAuthException -> firebaseAuthErrorMessageForCode(context, error.errorCode)
        else -> firebaseAuthErrorMessageForCode(context, errorCode = null)
    }

internal fun firebaseAuthErrorMessageForCode(context: Context, errorCode: String?): String =
    when (errorCode) {
        ERROR_INVALID_LOGIN_CREDENTIALS,
        ERROR_WRONG_PASSWORD,
        ERROR_INVALID_CREDENTIAL -> context.getString(R.string.invalid_credentials)
        ERROR_USER_NOT_FOUND -> context.getString(R.string.no_account_email)
        ERROR_USER_DISABLED -> context.getString(R.string.str_241)
        ERROR_EMAIL_ALREADY_IN_USE -> context.getString(R.string.str_432)
        ERROR_WEAK_PASSWORD -> context.getString(R.string.str_354)
        ERROR_INVALID_EMAIL -> context.getString(R.string.invalid_email)
        ERROR_NETWORK_REQUEST_FAILED -> context.getString(R.string.str_215)
        ERROR_TOO_MANY_REQUESTS -> context.getString(R.string.many_attempts_try_later)
        ERROR_OPERATION_NOT_ALLOWED -> context.getString(R.string.str_307)
        else -> context.getString(R.string.error_retry)
    }

private const val ERROR_INVALID_LOGIN_CREDENTIALS = "ERROR_INVALID_LOGIN_CREDENTIALS"
private const val ERROR_WRONG_PASSWORD = "ERROR_WRONG_PASSWORD"
private const val ERROR_INVALID_CREDENTIAL = "ERROR_INVALID_CREDENTIAL"
private const val ERROR_USER_NOT_FOUND = "ERROR_USER_NOT_FOUND"
private const val ERROR_USER_DISABLED = "ERROR_USER_DISABLED"
private const val ERROR_EMAIL_ALREADY_IN_USE = "ERROR_EMAIL_ALREADY_IN_USE"
private const val ERROR_WEAK_PASSWORD = "ERROR_WEAK_PASSWORD"
private const val ERROR_INVALID_EMAIL = "ERROR_INVALID_EMAIL"
private const val ERROR_NETWORK_REQUEST_FAILED = "ERROR_NETWORK_REQUEST_FAILED"
private const val ERROR_TOO_MANY_REQUESTS = "ERROR_TOO_MANY_REQUESTS"
private const val ERROR_OPERATION_NOT_ALLOWED = "ERROR_OPERATION_NOT_ALLOWED"
