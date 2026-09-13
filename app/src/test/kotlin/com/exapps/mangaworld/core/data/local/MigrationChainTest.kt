package com.exapps.mangaworld.core.data.local

import androidx.room.Database
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * I-cluster (DB-1) regression guard.
 *
 * Room has no upgrade path below v8 by decision (pre-8 DDL unrecoverable,
 * negligible cohort): a missing migration must crash loudly, never wipe
 * (no `fallbackToDestructiveMigration` on upgrade — see DatabaseModule).
 * That contract only holds while the 8→current chain has no gaps: a version
 * bump without its Migration turns every older install into a startup crash.
 *
 * Pure JVM — only reads Migration endpoints + the @Database version, never
 * opens a database.
 */
class MigrationChainTest {

    private val chain = listOf(
        MangaDatabase.MIGRATION_8_9,
        MangaDatabase.MIGRATION_9_10,
        MangaDatabase.MIGRATION_10_11,
        MangaDatabase.MIGRATION_11_12,
        MangaDatabase.MIGRATION_12_13,
        MangaDatabase.MIGRATION_13_14,
        MangaDatabase.MIGRATION_14_15
    )

    private val dbVersion: Int =
        MangaDatabase::class.java.getAnnotation(Database::class.java)!!.version

    @Test
    fun chainStartsAtV8WithNoGaps() {
        val sorted = chain.sortedBy { it.startVersion }
        assertEquals("chain must start at v8 (no upgrade path below by decision)", 8, sorted.first().startVersion)
        sorted.zipWithNext { prev, next ->
            assertEquals(
                "migration gap: v${prev.endVersion} has no outgoing migration",
                prev.endVersion,
                next.startVersion
            )
        }
    }

    @Test
    fun chainReachesCurrentDbVersion() {
        val sorted = chain.sortedBy { it.startVersion }
        assertEquals(
            "chain ends at v${sorted.last().endVersion} but @Database is v$dbVersion — add the missing Migration",
            dbVersion,
            sorted.last().endVersion
        )
        assertEquals(
            "expected one migration per version step 8..$dbVersion",
            dbVersion - 8,
            chain.size
        )
    }

    @Test
    fun eachMigrationMovesForwardExactlyOneVersion() {
        chain.forEach {
            assertTrue(
                "migration v${it.startVersion}→v${it.endVersion} must advance exactly one version",
                it.endVersion == it.startVersion + 1
            )
        }
    }
}
