package com.exapps.mangaworld.viewmodel

import android.content.Context
import com.exapps.mangaworld.R
import com.exapps.mangaworld.core.data.local.dao.FavoriteDao
import com.exapps.mangaworld.core.data.local.dao.ReadChapterDao
import com.exapps.mangaworld.core.data.local.dao.ReadingHistoryDao
import com.exapps.mangaworld.core.firebase.CloudinaryUploader
import com.exapps.mangaworld.core.firebase.FirebaseSessionManager
import com.exapps.mangaworld.domain.model.AppSettings
import com.exapps.mangaworld.domain.model.CommunityProfile
import com.exapps.mangaworld.domain.model.CustomUserList
import com.exapps.mangaworld.domain.model.CustomUserListItem
import com.exapps.mangaworld.domain.repository.CommunityRepository
import com.exapps.mangaworld.domain.repository.SecurityRepository
import com.exapps.mangaworld.domain.repository.SettingsRepository
import com.exapps.mangaworld.presentation.profile.ProfileSettingsViewModel
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2 (settings rework) VM tests: profile location/birthday forwarding,
 * granular notification toggles, security-centre actions and lists
 * export/import validation. Repos are relaxed mockk; JSON uses real org.json.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhaseTwoSettingsTest {

    private fun newDispatcher() = StandardTestDispatcher()

    private val communityRepo = mockk<CommunityRepository>(relaxed = true)
    private val settingsRepo = mockk<SettingsRepository>(relaxed = true)
    private val sessionManager = mockk<FirebaseSessionManager>(relaxed = true)
    private val securityRepo = mockk<SecurityRepository>(relaxed = true)
    private val favoriteDao = mockk<FavoriteDao>(relaxed = true)
    private val historyDao = mockk<ReadingHistoryDao>(relaxed = true)
    private val readChapterDao = mockk<ReadChapterDao>(relaxed = true)
    private val cloudinaryUploader = mockk<CloudinaryUploader>(relaxed = true)
    private val auth = mockk<FirebaseAuth>(relaxed = true)
    private val firestore = mockk<FirebaseFirestore>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun stubBase() {
        every { auth.currentUser } returns null
        coEvery { communityRepo.getCurrentProfile() } returns CommunityProfile(uid = "u1", username = "old")
        every { settingsRepo.getAppSettings() } returns flowOf(AppSettings())
        every { settingsRepo.getFavoriteGenres() } returns flowOf(emptyList())
        every { communityRepo.getBlockedUsers() } returns flowOf(emptySet())
        every { sessionManager.authState } returns flowOf(null)
        every { sessionManager.currentUserId() } returns null
        every { sessionManager.linkedProviderIds() } returns emptySet()
        every { securityRepo.observeLoginLogs() } returns flowOf(emptyList())
        every { securityRepo.observeDevices() } returns flowOf(emptyList())
        every { securityRepo.observeSessions() } returns flowOf(emptyList())
        coEvery { favoriteDao.getFavoritesList() } returns emptyList()
        coEvery { historyDao.getAll() } returns emptyList()
        coEvery { readChapterDao.getTotalReadCount() } returns 0
        // Delete-account wipe chain (A-9): every collection reads back one
        // empty page so wipeUserData completes instead of suspending on mock
        // Tasks. Shared mock — stubbed here so results never depend on order.
        every { context.getString(R.string.settings_delete_account_reauth) } returns "E-reauth"
        every { context.getString(R.string.str_215) } returns "E-net"
        every { context.getString(R.string.settings_security_action_failed) } returns "E-action-failed"
        every { context.getString(R.string.auth_error_password_weak) } returns "E-weak"
        every { context.getString(R.string.settings_password_changed) } returns "E-pw-changed"
        val userDoc = mockk<DocumentReference>(relaxed = true)
        val topCol = mockk<CollectionReference>(relaxed = true)
        val subCol = mockk<CollectionReference>(relaxed = true)
        val subQuery = mockk<Query>(relaxed = true)
        val emptySnap = mockk<QuerySnapshot>()
        every { emptySnap.isEmpty } returns true
        every { firestore.collection(any()) } returns topCol
        every { topCol.document(any()) } returns userDoc
        every { userDoc.collection(any()) } returns subCol
        every { subCol.limit(any()) } returns subQuery
        every { subQuery.get() } returns com.google.android.gms.tasks.Tasks.forResult(emptySnap)
        every { firestore.collectionGroup(any()) } returns subCol
    }

    private fun createVm() = ProfileSettingsViewModel(
        communityRepository = communityRepo,
        settingsRepository = settingsRepo,
        sessionManager = sessionManager,
        securityRepository = securityRepo,
        favoriteDao = favoriteDao,
        historyDao = historyDao,
        readChapterDao = readChapterDao,
        cloudinaryUploader = cloudinaryUploader,
        auth = auth,
        firestore = firestore,
        context = context
    )

    @Test
    fun updateProfile_forwardsLocationAndBirthday() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            stubBase()
            val vm = createVm()
            advanceUntilIdle()
            vm.updateProfile("newname", "bio", "Disp", "Cairo", 946_684_800_000L)
            advanceUntilIdle()
            coVerify {
                communityRepo.upsertProfile(
                    username = "newname",
                    bio = "bio",
                    isPublic = true,
                    avatarUrl = null,
                    bannerUrl = null,
                    displayName = "Disp",
                    location = "Cairo",
                    birthday = 946_684_800_000L
                )
            }
        }
    }

    @Test
    fun notificationToggles_forwardToSettingsRepo() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            stubBase()
            val vm = createVm()
            advanceUntilIdle()
            vm.toggleNotifyComments(false)
            vm.toggleNotifyLikes(false)
            vm.toggleNotifyFollowers(true)
            advanceUntilIdle()
            coVerify { settingsRepo.setNotifyCommentsEnabled(false) }
            coVerify { settingsRepo.setNotifyLikesEnabled(false) }
            coVerify { settingsRepo.setNotifyFollowersEnabled(true) }
        }
    }

    @Test
    fun securityActions_forwardToSecurityRepo() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            stubBase()
            val vm = createVm()
            advanceUntilIdle()
            vm.deleteLoginLog("log-1")
            vm.clearLoginLogs()
            vm.removeDevice("dev-1")
            vm.revokeSession("sess-1")
            advanceUntilIdle()
            coVerify { securityRepo.deleteLoginLog("log-1") }
            coVerify { securityRepo.clearLoginLogs() }
            coVerify { securityRepo.removeDevice("dev-1") }
            coVerify { securityRepo.revokeSession("sess-1") }
        }
    }

    private fun testList() = CustomUserList(id = "l1", name = "Summer")

    private fun testItem(mangaId: String = "azora_x", sourceId: String = "azora") =
        CustomUserListItem(mangaId = mangaId, sourceId = sourceId, slug = "x", title = "T")

    @Test
    fun exportListsJson_producesVersionedDocument() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            stubBase()
            every { communityRepo.observeUserLists() } returns flowOf(listOf(testList()))
            every { communityRepo.observeListItems("l1") } returns flowOf(listOf(testItem()))
            val vm = createVm()
            advanceUntilIdle()
            val raw = vm.exportListsJson()
            val root = JSONObject(raw)
            assertEquals(1, root.getInt("version"))
            assertEquals(1, root.getJSONArray("lists").length())
            val list = root.getJSONArray("lists").getJSONObject(0)
            assertEquals("Summer", list.getString("name"))
            assertEquals(1, list.getJSONArray("items").length())
        }
    }

    @Test
    fun importListsJson_importsValidSkipsLocalRejectsGarbage() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            stubBase()
            coEvery {
                communityRepo.createOrUpdateList(null, "Summer", "", "", 0f, emptyList(), false)
            } returns "new-id"
            val vm = createVm()
            advanceUntilIdle()
            val valid = JSONObject()
                .put("version", 1)
                .put("lists", org.json.JSONArray().put(
                    JSONObject()
                        .put("name", "Summer")
                        .put("items", org.json.JSONArray().put(
                            JSONObject().put("mangaId", "azora_x").put("slug", "x").put("sourceId", "azora")
                        ).put(
                            JSONObject().put("mangaId", "lekmanga_y").put("slug", "y").put("sourceId", "lekmanga")
                        ).put(
                            // Disk-only ids must never enter cloud lists.
                            JSONObject().put("mangaId", "imported_z").put("slug", "imported_z").put("sourceId", "imported")
                        ))
                )).toString()
            vm.importListsJson(valid)
            advanceUntilIdle()
            coVerify(exactly = 2) { communityRepo.addMangaToList(any(), any()) }

            vm.importListsJson("{not json")
            advanceUntilIdle()
            coVerify(exactly = 1) {
                communityRepo.createOrUpdateList(null, "Summer", "", "", 0f, emptyList(), false)
            }
            assertTrue(vm.listsMessage.value != null)
        }
    }

    // ─── Profile-save hardening (denial must surface, never crash) ────────

    @Test
    fun updateProfile_repoThrow_setsSaveErrorAndSurvives() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            stubBase()
            // Any repo throw (e.g. Firestore PERMISSION_DENIED) must surface,
            // never escape the launch (plain RuntimeException: the Firestore
            // SDK exception class cannot initialize on JVM unit tests).
            coEvery { communityRepo.upsertProfile(any(), any(), any(), any(), any(), any(), any(), any()) } throws
                RuntimeException("denied")
            val vm = createVm()
            advanceUntilIdle()
            // Must not throw out of the launch (used to crash the app).
            vm.updateProfile("newname", "bio", "Disp")
            advanceUntilIdle()
            assertNotNull(vm.saveError.value)
        }
    }

    @Test
    fun deleteAccount_recentLoginRequired_keepsSessionAndReports() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            stubBase()
            val user = mockk<FirebaseUser>(relaxed = true)
            every { auth.currentUser } returns user
            every { user.delete() } returns com.google.android.gms.tasks.Tasks.forException(
                mockk<FirebaseAuthRecentLoginRequiredException>()
            )
            val vm = createVm()
            advanceUntilIdle()
            var deleted = false
            vm.deleteAccount(onDeleted = { deleted = true })
            advanceUntilIdle()
            assertFalse(deleted)
            // A-9: stale auth reports re-login (not a generic/network error).
            assertEquals("E-reauth", vm.saveError.value)
            coVerify(exactly = 0) { sessionManager.signOut() }
        }
    }

    @Test
    fun deleteAccount_networkFailure_reportsConnectivityNotReauth() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            stubBase()
            val user = mockk<FirebaseUser>(relaxed = true)
            every { auth.currentUser } returns user
            every { user.uid } returns "u1"
            every { user.delete() } returns com.google.android.gms.tasks.Tasks.forException(
                mockk<com.google.firebase.FirebaseNetworkException>()
            )
            val vm = createVm()
            advanceUntilIdle()
            var deleted = false
            vm.deleteAccount(onDeleted = { deleted = true })
            advanceUntilIdle()
            assertFalse(deleted)
            // A-9: a network failure must never masquerade as a re-login demand.
            assertEquals("E-net", vm.saveError.value)
            coVerify(exactly = 0) { sessionManager.signOut() }
        }
    }

    @Test
    fun deleteAccount_success_wipesFullScopeSignsOutAndCallbacks() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            stubBase()
            val user = mockk<FirebaseUser>(relaxed = true)
            every { auth.currentUser } returns user
            every { user.uid } returns "u1"
            every { user.delete() } returns com.google.android.gms.tasks.Tasks.forResult(null)
            val docRef = mockk<DocumentReference>(relaxed = true)
            val colRef = mockk<CollectionReference>()
            every { firestore.collection(any()) } returns colRef
            every { colRef.document(any()) } returns docRef
            every { docRef.delete() } returns com.google.android.gms.tasks.Tasks.forResult(null)
            // A-9 wipe chain on this test's own doc mocks (empty pages).
            val wipeCol = mockk<CollectionReference>(relaxed = true)
            val wipeQuery = mockk<Query>(relaxed = true)
            val emptySnap = mockk<QuerySnapshot>()
            every { emptySnap.isEmpty } returns true
            every { docRef.collection(any()) } returns wipeCol
            every { wipeCol.limit(any()) } returns wipeQuery
            every { wipeQuery.get() } returns com.google.android.gms.tasks.Tasks.forResult(emptySnap)
            val vm = createVm()
            advanceUntilIdle()
            var deleted = false
            vm.deleteAccount(onDeleted = { deleted = true })
            advanceUntilIdle()
            assertTrue(deleted)
            coVerify { sessionManager.signOut() }
            coVerify { docRef.delete() }
            // A-9: the whole users/{uid} subtree is wiped, not just identity.
            verify { docRef.collection("favorites") }
            verify { docRef.collection("readingHistory") }
            verify { docRef.collection("loginLogs") }
            verify { docRef.collection("sessions") }
            verify { docRef.collection("syncTombstones") }
        }
    }

    // ─── A-11 password change ─────────────────────────────────────────────

    @Test
    fun changePassword_shortPassword_setsWeakErrorWithoutBackendCall() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            stubBase()
            val vm = createVm()
            advanceUntilIdle()
            vm.changePassword("12345")
            advanceUntilIdle()
            assertEquals("E-weak", vm.securityError.value)
            coVerify(exactly = 0) { sessionManager.updatePassword(any()) }
        }
    }

    @Test
    fun changePassword_success_setsConfirmation() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            stubBase()
            coEvery { sessionManager.updatePassword(any()) } returns Unit
            val vm = createVm()
            advanceUntilIdle()
            vm.changePassword("newpass123")
            advanceUntilIdle()
            assertEquals("E-pw-changed", vm.passwordMessage.value)
            assertNull(vm.securityError.value)
            coVerify(exactly = 1) { sessionManager.updatePassword("newpass123") }
        }
    }

    @Test
    fun changePassword_recentLoginRequired_setsReauthError() {
        val dispatcher = newDispatcher()
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            stubBase()
            coEvery { sessionManager.updatePassword(any()) } throws
                mockk<FirebaseAuthRecentLoginRequiredException>()
            val vm = createVm()
            advanceUntilIdle()
            vm.changePassword("newpass123")
            advanceUntilIdle()
            assertEquals("E-reauth", vm.securityError.value)
            assertNull(vm.passwordMessage.value)
        }
    }
}
