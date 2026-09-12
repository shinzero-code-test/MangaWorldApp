package com.exapps.mangaworld.core.firebase

import android.content.Context
import android.content.SharedPreferences
import com.exapps.mangaworld.core.data.local.AppPreferences
import com.exapps.mangaworld.core.data.local.dao.FavoriteDao
import com.exapps.mangaworld.core.data.local.dao.ReadChapterDao
import com.exapps.mangaworld.core.data.local.dao.ReaderAnnotationDao
import com.exapps.mangaworld.core.data.local.dao.ReadingHistoryDao
import com.exapps.mangaworld.core.data.local.dao.ReadingProgressDao
import com.google.firebase.auth.FirebaseUser
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * FS-3: account-switch isolation. The startup prefs + DAOs are mocked; no
 * Firebase backend is touched (session/sync managers are mocks).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FirebaseStartupCoordinatorTest {

    private data class Harness(
        val coordinator: FirebaseStartupCoordinator,
        val favoriteDao: FavoriteDao,
        val historyDao: ReadingHistoryDao,
        val annotationDao: ReaderAnnotationDao,
        val readChapterDao: ReadChapterDao,
        val progressDao: ReadingProgressDao,
        val syncManager: FirebaseSyncManager,
        val sessionManager: FirebaseSessionManager,
        val editor: SharedPreferences.Editor,
        val prefsStore: SharedPreferences
    )

    private fun newHarness(
        lastUid: String? = null,
        lastNamed: Boolean = false,
        currentUid: String = "uB",
        currentAnonymous: Boolean = false
    ): Harness {
        val context = mockk<Context>(relaxed = true)
        val prefsStore = mockk<SharedPreferences>(relaxed = true)
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefsStore
        every { prefsStore.edit() } returns editor
        every { prefsStore.getString(any(), any()) } returns lastUid
        // Only the account-identity flag reads lastNamed — merge/throttle keys
        // default false/0 so fresh UIDs always merge-then-push in tests.
        every { prefsStore.getBoolean(any(), any()) } answers { firstArg<String>() == "last_sync_named" && lastNamed }
        every { prefsStore.getLong(any(), any()) } returns 0L

        val sessionManager = mockk<FirebaseSessionManager>(relaxed = true)
        val user = mockk<FirebaseUser>(relaxed = true)
        every { user.uid } returns currentUid
        every { user.isAnonymous } returns currentAnonymous
        every { sessionManager.currentUser() } returns user
        coEvery { sessionManager.ensureFirebaseSession() } returns currentUid

        val favoriteDao = mockk<FavoriteDao>(relaxed = true)
        val historyDao = mockk<ReadingHistoryDao>(relaxed = true)
        val annotationDao = mockk<ReaderAnnotationDao>(relaxed = true)
        val readChapterDao = mockk<ReadChapterDao>(relaxed = true)
        val progressDao = mockk<ReadingProgressDao>(relaxed = true)
        coEvery { favoriteDao.getFavoritesList() } returns emptyList()
        val syncManager = mockk<FirebaseSyncManager>(relaxed = true)

        val coordinator = FirebaseStartupCoordinator(
            context = context,
            remoteConfigManager = mockk(relaxed = true),
            sessionManager = sessionManager,
            syncManager = syncManager,
            favoriteDao = favoriteDao,
            historyDao = historyDao,
            readerAnnotationDao = annotationDao,
            readChapterDao = readChapterDao,
            progressDao = progressDao,
            prefs = mockk<AppPreferences>(relaxed = true),
            topicManager = mockk(relaxed = true),
            messagingRegistrar = mockk(relaxed = true),
            userInsightsCoordinator = mockk(relaxed = true),
            notificationPolicyManager = mockk(relaxed = true),
            telemetry = mockk(relaxed = true)
        )
        return Harness(
            coordinator, favoriteDao, historyDao, annotationDao,
            readChapterDao, progressDao, syncManager, sessionManager, editor, prefsStore
        )
    }

    @Test
    fun namedToNamedSwitch_wipesLibraryAndMergesFirst() = runTest(StandardTestDispatcher()) {
        val h = newHarness(lastUid = "uA", lastNamed = true, currentUid = "uB", currentAnonymous = false)
        h.coordinator.initialize()
        advanceUntilIdle()
        // Previous account's library must not survive into the new account.
        coVerify { h.favoriteDao.clearAll() }
        coVerify { h.historyDao.clearAll() }
        coVerify { h.annotationDao.clearAll() }
        coVerify { h.readChapterDao.clearAll() }
        coVerify { h.progressDao.clearAll() }
        // Fresh UID merges before pushing (per-UID flags, never inherited).
        coVerifyOrder {
            h.syncManager.mergeRemoteSnapshot()
            h.syncManager.pushLocalSnapshot(force = true)
        }
        verify { h.editor.putBoolean("initial_merge_done_uB", true) }
        verify { h.editor.putString("last_sync_uid", "uB") }
    }

    @Test
    fun sameUid_noWipe() = runTest(StandardTestDispatcher()) {
        val h = newHarness(lastUid = "uB", lastNamed = true, currentUid = "uB", currentAnonymous = false)
        h.coordinator.initialize()
        advanceUntilIdle()
        coVerify(exactly = 0) { h.favoriteDao.clearAll() }
        coVerify(exactly = 0) { h.historyDao.clearAll() }
        verify { h.editor.putString("last_sync_uid", "uB") }
    }

    @Test
    fun guestToNamedLogin_keepsLibraryButMergesFirst() = runTest(StandardTestDispatcher()) {
        val h = newHarness(lastUid = "anonX", lastNamed = false, currentUid = "uB", currentAnonymous = false)
        h.coordinator.initialize()
        advanceUntilIdle()
        // Guest library migrates — no wipe on the way into a named account.
        coVerify(exactly = 0) { h.favoriteDao.clearAll() }
        coVerifyOrder {
            h.syncManager.mergeRemoteSnapshot()
            h.syncManager.pushLocalSnapshot(force = true)
        }
    }

    @Test
    fun signOutToGuest_keepsLibrary() = runTest(StandardTestDispatcher()) {
        val h = newHarness(lastUid = "uA", lastNamed = true, currentUid = "anonY", currentAnonymous = true)
        h.coordinator.initialize()
        advanceUntilIdle()
        coVerify(exactly = 0) { h.favoriteDao.clearAll() }
    }
}
