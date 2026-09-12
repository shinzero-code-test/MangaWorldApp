package com.exapps.mangaworld.viewmodel

import android.content.Context
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
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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
            assertNotNull(vm.saveError.value)
            coVerify(exactly = 0) { sessionManager.signOut() }
        }
    }

    @Test
    fun deleteAccount_success_cleansIdentitySignsOutAndCallbacks() {
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
            val vm = createVm()
            advanceUntilIdle()
            var deleted = false
            vm.deleteAccount(onDeleted = { deleted = true })
            advanceUntilIdle()
            assertTrue(deleted)
            coVerify { sessionManager.signOut() }
            coVerify { docRef.delete() }
        }
    }
}
