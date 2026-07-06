# CODE_REVIEW.md — MangaWorld App

**Reviewed:** 2026-07-04  
**Scope:** Kotlin/Android app + Next.js admin dashboard  
**Files reviewed:** 300+ | **LOC:** ~41,680

---

## 🔴 Critical Issues

### 1. Empty catch blocks silently swallow errors (34 instances)

**Files:** `ProComicScraper.kt`, `ImportMangaScreen.kt`, `SuggestionsScreen.kt`, `LocalStorageScreen.kt`, `AreaScansScraper.kt`, `MadaraBaseScraper.kt`, `ChapterUpdateChecker.kt`, `FavoriteDigestWorker.kt`, `SourceSettingsSheet.kt`

**Impact:** 34 `catch (_: Exception) {}` blocks silently discard errors across scrapers, import screens, and notification workers. When a scraper fails, the user sees an empty result with no indication of what went wrong. When import fails, corrupted files are silently skipped with no feedback.

**Fix:** At minimum, log the exception:
```kotlin
} catch (e: Exception) { 
    android.util.Log.w("Tag", "Error: ${e.message}") 
}
```
For user-facing code (scrapers, import), show an error state or toast.

---

### 2. Firestore security rules allow unauthenticated read of all community content

**File:** `firestore.rules` lines 68-88

```javascript
match /community_manga/{mangaId} {
    allow read: if true;  // ← Anyone can read all comments/reviews
```

**Impact:** All user comments, reviews, and chapter discussions are publicly readable without authentication. While this may be intentional for a public community, it means any scraped data or bot can read all user-generated content.

**Fix:** If community content should be public, this is intentional. If not:
```javascript
allow read: if signedIn();
```

---

### 3. `user_achievements` collection has no security rules

**File:** `firestore.rules`

The `user_achievements` collection (used by `AchievementManager.syncToFirestore()`) has no Firestore rules. This means any authenticated user can read/write any user's achievements data.

**Fix:** Add rules:
```javascript
match /user_achievements/{userId} {
    allow read, write: if owner(userId);
}
```

---

## 🟠 High Severity Issues

### 4. 34 empty catch blocks silently swallow errors in scrapers

**Files:** `ProComicScraper.kt` (6 instances), `MadaraBaseScraper.kt` (2), `AreaScansScraper.kt` (2), `MangaReaderBaseScraper.kt` (1)

**Impact:** When scraping fails (network error, HTML parsing error, Cloudflare block), the error is silently discarded. The user sees empty results or crashes later when null data is used.

**Fix:** Log errors and propagate them:
```kotlin
} catch (e: Exception) {
    android.util.Log.e("Scraper", "Failed to parse: ${e.message}")
    // Return empty or rethrow
}
```

---

### 5. `syncToFirestore()` throttled to 30 minutes — data loss on app crash

**File:** `AchievementManager.kt` line 73

**Impact:** If the app crashes between sync intervals, up to 30 minutes of reading progress, achievements, and goals are lost. For a reading app where progress is critical, this is too long.

**Fix:** Reduce to 5 minutes or sync on significant events (chapter completion, achievement unlock).

---

### 6. No retry logic for Cloudinary uploads

**File:** `CloudinaryUploader.kt`

**Impact:** If the HTTP request to the dashboard fails (network timeout, server error), the avatar upload silently returns null. The user has no feedback that their profile picture wasn't saved.

**Fix:** Add retry with exponential backoff and show a toast on failure.

---

### 7. `FavoriteDigestWorker` scrapes ALL enabled sources every 6 hours

**File:** `FavoriteDigestWorker.kt` / `ChapterUpdateChecker.kt`

**Impact:** Every 6 hours, the worker makes HTTP requests to every enabled source to check for new chapters. With 15+ sources, this is 15+ HTTP requests + HTML parsing in the background, consuming battery and data.

**Fix:** Only check sources that have favorited manga, and cache the last-check timestamp per source.

---

## 🟡 Medium Severity Issues

### 8. `ReadingStatsStore` — DataStore operations not transactional

**File:** `ReadingStatsStore.kt` lines 48-62

The `addReadingTime()` method does a `dataStore.data.first()` read followed by a `dataStore.edit {}` write. Between these two operations, another coroutine could modify the same keys, causing a race condition.

**Fix:** Use a single `dataStore.edit {}` block for both read and write operations.

---

### 9. `DownloadQueueManager` — `syncFileSystemWithDatabase` runs on every app launch

**File:** `DownloadQueueManager.kt` line 306

This scans the entire downloads directory on every app launch, which could be slow with many downloaded manga.

**Fix:** Add a throttle or only run when the LocalStorage screen is opened.

---

### 10. `MangaDetailViewModel` — `checkAchievements()` called on every page read

**File:** `AchievementManager.kt` lines 121-132

`recordPageRead()` calls `checkAchievements()` which reads from DataStore, merges achievements, and potentially syncs to Firestore. This happens on every single page swipe.

**Fix:** Throttle achievement checks to once per minute or per chapter completion.

---

### 11. `NotificationPolicyManager` — channel created in `init` block may race with `MangaWorldApp`

**File:** `NotificationPolicyManager.kt` lines 29-39 vs `MangaWorldApp.kt` lines 112-141

The reminder channel is created in `NotificationPolicyManager.init`, but `MangaWorldApp.createNotificationChannels()` also creates channels. If `NotificationPolicyManager` is injected before `MangaWorldApp.onCreate()`, the channel creation order is unpredictable.

**Fix:** Create all notification channels in `MangaWorldApp.onCreate()`.

---

### 12. `CollectionsScreen` — Local lists (DataStore) vs Firebase lists confusion

**File:** `CollectionsScreen.kt` uses `CollectionManager` (DataStore) while `UserListsScreen.kt` uses `CommunityRepository` (Firestore).

**Impact:** Users have two separate list systems that don't share data. Lists created in one screen don't appear in the other.

**Fix:** Unify into a single list system (either all local or all Firebase).

---

## 🟢 Low Severity Issues

### 13. Unused `apiBase` field in `ProComicScraper`

**File:** `ProComicScraper.kt` line 30

```kotlin
private val apiBase = "${source.baseUrl}/api/public/series/search"
```

Never used — all API calls construct URLs inline.

**Fix:** Remove the field.

---

### 14. `CollectionManager` uses DataStore while community lists use Firestore

**Files:** `CollectionManager.kt`, `FirebaseCommunityRepository.kt`

Two independent list systems with no synchronization. Users see different lists depending on which screen they open.

---

### 15. `UserListsScreen` — `ListEditorDialog` upload runs on `MainScope`

**File:** `UserListsScreen.kt` line 364

```kotlin
kotlinx.coroutines.MainScope().launch { ... }
```

This creates a coroutine scope that's never cancelled, potentially leaking if the dialog is dismissed during upload.

**Fix:** Use `viewModelScope` instead.

---

## Summary

| Severity | Count |
|----------|-------|
| 🔴 Critical | 3 |
| 🟠 High | 4 |
| 🟡 Medium | 5 |
| 🟢 Low | 3 |

**Top priorities:**
1. Add Firestore rules for `user_achievements` collection
2. Add logging to empty catch blocks (especially scrapers)
3. Throttle `checkAchievements()` calls
4. Reduce sync interval from 30min to 5min
5. Fix `MainScope` leak in `UserListsScreen`
