package com.exapps.mangaworld.presentation.auth

import com.exapps.mangaworld.core.firebase.AccountMergeReason
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuthException

internal fun accountMergeMessage(reason: AccountMergeReason): String =
    when (reason) {
        AccountMergeReason.ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL ->
            "يوجد حساب بنفس البريد الإلكتروني يستخدم طريقة تسجيل دخول مختلفة. سجّل الدخول بالطريقة الأصلية ثم اربط هذا المزوّد من الإعدادات."
        AccountMergeReason.CREDENTIAL_ALREADY_IN_USE ->
            "بيانات تسجيل الدخول هذه مرتبطة بحساب آخر. سجّل الدخول بالحساب الأصلي لإدارة المزوّد."
        AccountMergeReason.EMAIL_ALREADY_IN_USE ->
            "هذا البريد الإلكتروني مستخدم بالفعل في حساب آخر. سجّل الدخول بالحساب الحالي أو استخدم بريداً آخر."
        AccountMergeReason.UNKNOWN ->
            "هذا المزوّد مرتبط بحساب آخر. لا يمكن دمج الحسابات تلقائياً."
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
        ERROR_INVALID_CREDENTIAL -> "بيانات الدخول غير صحيحة"
        ERROR_USER_NOT_FOUND -> "لا يوجد حساب بهذا البريد الإلكتروني"
        ERROR_USER_DISABLED -> "تم تعطيل هذا الحساب. تواصل مع الدعم."
        ERROR_EMAIL_ALREADY_IN_USE -> "هذا البريد الإلكتروني مستخدم بالفعل"
        ERROR_WEAK_PASSWORD -> "كلمة المرور لا تفي بمتطلبات الأمان. استخدم كلمة مرور أقوى."
        ERROR_INVALID_EMAIL -> "البريد الإلكتروني غير صالح"
        ERROR_NETWORK_REQUEST_FAILED -> "تحقق من اتصال الإنترنت"
        ERROR_TOO_MANY_REQUESTS -> "محاولات كثيرة. حاول مرة أخرى بعد قليل"
        ERROR_OPERATION_NOT_ALLOWED -> "طريقة تسجيل الدخول هذه غير مفعّلة حالياً."
        else -> "حدث خطأ. حاول مرة أخرى"
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
