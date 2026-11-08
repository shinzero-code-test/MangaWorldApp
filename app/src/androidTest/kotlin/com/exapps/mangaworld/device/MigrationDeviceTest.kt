package com.exapps.mangaworld.device

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.migration.MigrationTestHelper
import com.exapps.mangaworld.core.data.local.MangaDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * U-08 regression net (Stage 0): executed Room migrations over schema files —
 * the gap that let MW-DB-001 (MIGRATION_8_9/9_10 crash-loop on v8/v9 devices)
 * ship. The inherited crash path is guarded in `addAllIndices` (indices only
 * where table+column exist), so 14→16 must succeed on a bare v14 database;
 * rows and the 13_14-era `batchId`/`download_batches` data must survive.
 *
 * Schema files live in `app/schemas/.../` and are wired into androidTest
 * assets via sourceSets (see `app/build.gradle.kts`). This suite only runs on
 * device (Firebase Test Lab) — it opens real SQLite.
 */
@RunWith(AndroidJUnit4::class)
class MigrationDeviceTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MangaDatabase::class.java
    )

    @Test
    fun migrate15to16_preservesRowsAndCreatesPluginIndex() {
        helper.createDatabase("mig15", 15).apply {
            execSQL("INSERT INTO favorites (mangaId, slug, title, coverUrl, sourceId, addedAt, readChapters, totalChapters, isFavorite) VALUES ('m1','s1','T1','https://example.com/a.jpg','hijala',1,0,0,1)")
            execSQL("INSERT INTO download_batches (id, mangaId, mangaTitle, totalChapters, completedChapters, failedChapters, completionNotified, createdAt, updatedAt) VALUES ('b1','m1','T1',3,1,0,0,1,1)")
            close()
        }
        val db = helper.runMigrationsAndValidate("mig15", 16, true, MangaDatabase.MIGRATION_15_16)
        db.query("SELECT COUNT(*) FROM favorites").use { cur ->
            cur.moveToFirst()
            assertEquals(1, cur.getInt(0))
        }
        db.query("PRAGMA table_info(plugin_index)").use { cur ->
            val cols = mutableListOf<String>()
            while (cur.moveToNext()) cols.add(cur.getString(cur.getColumnIndexOrThrow("name")))
            assertEquals(
                listOf("id", "activeVersion", "previousVersion", "origin", "status", "manifestJson", "updatedAt"),
                cols
            )
        }
        db.close()
    }

    @Test
    fun migrate14to16_doesNotCrashAndPreservesBatchData() {
        // v14-era database: no batchId column, no download_batches table, no
        // home_cache, no plugin_index. Running 14→15→16 exercises the full
        // current chain on the smallest schema the repo actually commits.
        helper.createDatabase("mig14", 14).apply {
            execSQL("INSERT INTO favorites (mangaId, slug, title, coverUrl, sourceId, addedAt, readChapters, totalChapters, isFavorite) VALUES ('m2','s2','T2','https://example.com/b.jpg','hijala',2,0,0,1)")
            close()
        }
        val db = helper.runMigrationsAndValidate(
            "mig14", 16, true,
            MangaDatabase.MIGRATION_14_15,
            MangaDatabase.MIGRATION_15_16
        )
        db.query("SELECT COUNT(*) FROM favorites").use { cur ->
            cur.moveToFirst()
            assertEquals(1, cur.getInt(0))
        }
        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name IN ('home_cache','plugin_index')").use { cur ->
            val tables = mutableListOf<String>()
            while (cur.moveToNext()) tables.add(cur.getString(0))
            assertEquals(listOf("home_cache", "plugin_index"), tables.sorted())
        }
        db.close()
    }

    @Test
    fun migration8to9_indexGuardDoesNotCrashOnPreBatchSchema() {
        // Direct guard check: a v8 database predates batchId/download_batches,
        // so addAllIndices must skip those indices instead of throwing
        // "no such column: batchId" (MW-DB-001).
        val factory = FrameworkSQLiteOpenHelperFactory()
        val cfg = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("mig8guard").callback(
                object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(8) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE reading_history (mangaId TEXT NOT NULL PRIMARY KEY, slug TEXT NOT NULL, title TEXT NOT NULL, coverUrl TEXT NOT NULL, sourceId TEXT NOT NULL, lastChapterNumber REAL NOT NULL, lastChapterUrl TEXT NOT NULL DEFAULT '', lastReadAt INTEGER NOT NULL, readChapters INTEGER NOT NULL DEFAULT 0, totalChapters INTEGER NOT NULL DEFAULT 0)")
                        db.execSQL("CREATE TABLE read_chapters (mangaId TEXT NOT NULL, chapterNumber REAL NOT NULL, readAt INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(mangaId, chapterNumber))")
                        db.execSQL("CREATE TABLE download_tasks (mangaId TEXT NOT NULL, chapterUrl TEXT NOT NULL, status TEXT NOT NULL, updatedAt INTEGER NOT NULL DEFAULT 0)")
                        db.execSQL("CREATE TABLE reading_progress (mangaId TEXT NOT NULL PRIMARY KEY, chapterNumber REAL NOT NULL DEFAULT 0, pageNumber INTEGER NOT NULL DEFAULT 0, updatedAt INTEGER NOT NULL DEFAULT 0)")
                        db.execSQL("CREATE TABLE manga_cache (mangaId TEXT NOT NULL PRIMARY KEY, sourceId TEXT NOT NULL, chaptersJson TEXT NOT NULL DEFAULT '', cachedAt INTEGER NOT NULL DEFAULT 0)")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
                }
            ).build()
        val helper8 = factory.create(cfg)
        val db8 = helper8.writableDatabase
        MangaDatabase.MIGRATION_8_9.migrate(db8)
        // If we reach here, the guard held — pre-fix this threw SQLiteException.
        db8.query("SELECT name FROM sqlite_master WHERE type='index' AND name='index_download_tasks_batchId'").use { cur ->
            assertFalse("batchId index must be skipped on pre-batch schema", cur.moveToFirst())
        }
        db8.query("SELECT name FROM sqlite_master WHERE type='index' AND name='index_download_tasks_mangaId'").use { cur ->
            assertEquals(true, cur.moveToFirst())
        }
        db8.close()
        helper8.close()
    }
}
