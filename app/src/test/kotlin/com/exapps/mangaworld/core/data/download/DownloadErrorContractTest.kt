package com.exapps.mangaworld.core.data.download

import com.exapps.mangaworld.domain.model.MangaStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Download error-code + notification-ID contract (#1).
 *
 * Room persists stable, language-neutral tokens (`cancelled`,
 * `download_error`, `retry_unavailable`); localized text is rendered only.
 * A refactor that persists a localized string or lets notification bands
 * overlap must fail here — these are the highest-LOC untested paths
 * (queue manager, chapter worker, reader render).
 */
class DownloadErrorContractTest {

    @Test
    fun `error tokens are distinct and language neutral`() {
        val tokens = setOf(
            ChapterDownloadWorker.ERROR_CANCELLED,
            ChapterDownloadWorker.ERROR_DOWNLOAD_FAILED,
            ChapterDownloadWorker.ERROR_RETRY_UNAVAILABLE
        )
        assertEquals(3, tokens.size)
        for (token in tokens) {
            // Persisted to Room: ASCII, no spaces, never localized Arabic.
            assertTrue(token, token.all { it.isAsciiLetterOrDigit() || it == '_' })
            assertTrue(token, token.none { it in '؀'..'ۿ' })
        }
    }

    @Test
    fun `error tokens never collide with lifecycle statuses`() {
        val lifecycle = setOf("queued", "running", "paused", "completed", "failed")
        assertTrue(lifecycle.none { it == ChapterDownloadWorker.ERROR_CANCELLED })
        assertTrue(lifecycle.none { it == ChapterDownloadWorker.ERROR_DOWNLOAD_FAILED })
        assertTrue(lifecycle.none { it == ChapterDownloadWorker.ERROR_RETRY_UNAVAILABLE })
    }

    @Test
    fun `notification bands are disjoint across adversarial keys`() {
        // Keys chosen for hostile hashCodes: empty, Arabic, very long, and
        // near-Int.MIN_HASH cases via varied content.
        val keys = listOf(
            "", "a", "olympus_solohttps://olympustaff.com/series/solo/12",
            "فصل", "x".repeat(500), "manga: chapter?",
            "12", "-2147483648", "😀", "a/b?c=d&e=f"
        ) + (0 until 200).map { "manga_${it}https://source.example/ch/$it" }
        val complete = keys.map { DownloadNotifIds.stableId(DownloadNotifIds.COMPLETE_BASE, it) }
        val fail = keys.map { DownloadNotifIds.stableId(DownloadNotifIds.FAIL_BASE, it) }
        val progress = keys.map { DownloadNotifIds.stableId(DownloadNotifIds.PROGRESS_BASE, it) }
        val batch = keys.map { DownloadNotifIds.batchId(it) }

        assertTrue(complete.all { it in 20000..28999 })
        assertTrue(fail.all { it in 30000..38999 })
        assertTrue(progress.all { it in 1001..10000 })
        assertTrue(batch.all { it in 40000..40999 })

        val all = complete + fail + progress + batch
        assertEquals(all.size, all.toSet().size)
    }

    @Test
    fun `stable ids are deterministic`() {
        val key = "azora_one-piecehttps://azorafly.com/ch/12"
        assertEquals(
            DownloadNotifIds.stableId(DownloadNotifIds.COMPLETE_BASE, key),
            DownloadNotifIds.stableId(DownloadNotifIds.COMPLETE_BASE, key)
        )
    }

    @Test
    fun `status mapping used by api mappers covers arabic labels`() {
        assertEquals(MangaStatus.ONGOING, MangaStatus.from("مستمر"))
        assertEquals(MangaStatus.COMPLETED, MangaStatus.from("مكتمل"))
        assertEquals(MangaStatus.CANCELLED, MangaStatus.from("ملغي"))
        assertEquals(MangaStatus.ONGOING, MangaStatus.from("Ongoing"))
        assertEquals(MangaStatus.UNKNOWN, MangaStatus.from(null))
        assertEquals(MangaStatus.UNKNOWN, MangaStatus.from("قريباً"))
    }

    private fun Char.isAsciiLetterOrDigit(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
}
