package com.exapps.mangaworld.core.firebase.work

import com.google.firebase.firestore.FirebaseFirestoreException

/**
 * U-18e: how a failed community write must be treated.
 *
 * The distinction matters because the write is queued BEFORE the first
 * attempt: dropping a write that could still succeed loses the user's text,
 * while retrying a write that can never succeed burns the retry budget and
 * hides the failure.
 */
enum class CommunityWriteFailureKind {
    /** The write may still succeed later (offline, timeout, server busy) → retry. */
    RETRYABLE,

    /** The write can never succeed as-is (rules rejected it, signed out) → record + drop. */
    TERMINAL
}

/**
 * Classifies a failed community write.
 *
 * Pure on purpose: the same rule must govern the foreground path and the
 * worker, and it has to be unit-testable (no Firestore, no Android).
 *
 * - PERMISSION_DENIED / UNAUTHENTICATED / INVALID_ARGUMENT / NOT_FOUND: the
 *   server refused the write → it would be refused again → terminal.
 * - FAILED_PRECONDITION: usually "the index is not ready yet" → retryable.
 * - our own guards (IllegalArgument/IllegalState): terminal.
 * - everything else (IO, timeouts, 5xx): the request may have reached the
 *   server → retryable. The replay is idempotent, so a retry cannot double
 *   post.
 */
internal fun classifyCommunityWriteFailure(t: Throwable): CommunityWriteFailureKind {
    val firestore = t as? FirebaseFirestoreException
    if (firestore != null) {
        return when (firestore.code) {
            FirebaseFirestoreException.Code.PERMISSION_DENIED,
            FirebaseFirestoreException.Code.UNAUTHENTICATED,
            FirebaseFirestoreException.Code.INVALID_ARGUMENT,
            FirebaseFirestoreException.Code.NOT_FOUND -> CommunityWriteFailureKind.TERMINAL
            else -> CommunityWriteFailureKind.RETRYABLE
        }
    }
    if (t is IllegalArgumentException || t is IllegalStateException || t is SecurityException) {
        return CommunityWriteFailureKind.TERMINAL
    }
    return CommunityWriteFailureKind.RETRYABLE
}

/** A short, log-safe description of a failure (never user-facing text). */
internal fun describeCommunityWriteFailure(t: Throwable): String =
    "${t::class.java.simpleName}: ${t.message ?: "no message"}".take(200)
