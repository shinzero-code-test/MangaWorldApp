package com.exapps.mangaworld.presentation.auth
import com.exapps.mangaworld.R
import androidx.compose.ui.res.stringResource

import com.exapps.mangaworld.core.firebase.AccountMergeReason
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuthException

internal fun accountMergeMessage(reason: AccountMergeReason): String =
    when (reason) {
        AccountMergeReason.ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL ->
            stringResource(R.string.str_456)
        AccountMergeReason.CREDENTIAL_ALREADY_IN_USE ->
            stringResource(R.string.str_209)
        AccountMergeReason.EMAIL_ALREADY_IN_USE ->
            stringResource(R.string.str_433)
        AccountMergeReason.UNKNOWN ->
            stringResource(R.string.auth_error_provider_linked)
    }

internal fun firebaseAuthErrorMessage(error: Exception): String =
    when (error) {
        is FirebaseNetworkException -> firebaseAuthErrorMessageForCode(ERROR_NETWORK_REQUEST_FAILED)
        is FirebaseTooManyRequestsException -> firebaseAuthErrorMessageForCode(ERROR_TOO_MANY_REQUESTS)
        is FirebaseAuthException -> firebaseAuthErrorMessageForCode(error.errorCode)
        else -> firebaseAuthErrorMessageForCode(errorCode = null)
    }

internal fun firebaseAuthErrorMessageForCode(errorCode: String?): String =
    when (errorCode) {
        ERROR_INVALID_LOGIN_CREDENTIALS,
        ERROR_WRONG_PASSWORD,
        ERROR_INVALID_CREDENTIAL -> stringResource(R.string.invalid_credentials)
        ERROR_USER_NOT_FOUND -> stringResource(R.string.no_account_email)
        ERROR_USER_DISABLED -> stringResource(R.string.str_241)
        ERROR_EMAIL_ALREADY_IN_USE -> stringResource(R.string.str_432)
        ERROR_WEAK_PASSWORD -> stringResource(R.string.str_354)
        ERROR_INVALID_EMAIL -> stringResource(R.string.invalid_email)
        ERROR_NETWORK_REQUEST_FAILED -> stringResource(R.string.str_215)
        ERROR_TOO_MANY_REQUESTS -> stringResource(R.string.many_attempts_try_later)
        ERROR_OPERATION_NOT_ALLOWED -> stringResource(R.string.str_307)
        else -> stringResource(R.string.error_retry)
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
