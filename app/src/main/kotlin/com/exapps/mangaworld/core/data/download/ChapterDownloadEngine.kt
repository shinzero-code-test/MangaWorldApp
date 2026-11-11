package com.exapps.mangaworld.core.data.download

import com.exapps.mangaworld.core.data.resolveCookieForUrl
import com.exapps.mangaworld.core.util.suspendRunCatching
import com.exapps.mangaworld.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * U-33 (TODO 6): the worker-agnostic chapter page engine.
 *
 * `ChapterDownloadWorker.doWork` used to own the page loop inline. That made
 * the loop untestable: a `CoroutineWorker` only runs on a device, and the JVM
 * suite may not drive worker code (the reader-load wedge taught us that).
 * The loop is pure I/O + file work, so it lives here and the worker keeps the
 * Room bookkeeping and the notifications.
 *
 * Contract (identical to the pre-extraction inline loop):
 * - pages are fetched in chunks of [PARALLEL_DOWNLOADS], so one dead page
 *   cannot cancel its siblings through structured concurrency;
 * - a page is written to `<n>.jpg.part` and renamed, so a crash never leaves a
 *   half-written file the reader could serve;
 * - an HTTP-200 HTML challenge page is rejected by the size floor + magic
 *   sniff — the caller turns that into the stable retry path;
 * - the `.completed` marker is written only when every page is on disk, and it
 *   is idempotent (a second run must not fail because the marker exists).
 */
@Singleton
class ChapterDownloadEngine @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val settingsRepository: SettingsRepository
) {

    /**
     * Downloads every page of one chapter into [targetDir] and writes the
     * completion marker.
     *
     * @throws IllegalStateException when the chapter could not be completed
     *   (a dead page). The caller owns the retry policy — the engine never
     *   retries on its own.
     */
    suspend fun downloadChapterPages(
        pages: List<String>,
        referer: String,
        targetDir: File,
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> }
    ): ChapterDownloadOutcome {
        if (!targetDir.exists()) targetDir.mkdirs()
        val total = pages.size
        var done = existingPageCount(targetDir)
        onProgress(done, total)

        val targets = pages.mapIndexed { index, pageUrl -> pageUrl to File(targetDir, "${index + 1}.jpg") }
        targets.chunked(PARALLEL_DOWNLOADS).forEach { chunk ->
            coroutineScope {
                // One bad page must not cancel its 3 siblings via structured
                // concurrency and discard a whole chunk every attempt.
                // Isolate per-page failures; completeness is checked below.
                chunk.map { (pageUrl, outFile) ->
                    async {
                        ensureActive()
                        try {
                            downloadPage(pageUrl, referer, outFile)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            false
                        }
                    }
                }.awaitAll().forEach { downloaded ->
                    if (downloaded) done += 1
                }
            }
            onProgress(done, total)
        }

        // Disk-truthful recount: the increment counter drifts whenever files
        // pre-exist (resumed attempts, concurrent winner) while their
        // downloads report "skipped". What matters is what is on disk now.
        val onDisk = existingPageCount(targetDir)
        onProgress(onDisk, total)
        check(onDisk >= total) { "incomplete download: $onDisk/$total" }

        val pendingMarker = File(targetDir, COMPLETION_MARKER_PART)
        val completionMarker = File(targetDir, COMPLETION_MARKER)
        // Idempotent marker: a previous attempt (or a concurrent winner) may
        // have already completed. renameTo fails when the destination exists —
        // that is success, not failure. The copyTo fallback covers
        // filesystems where renameTo is flaky.
        if (!completionMarker.exists()) {
            pendingMarker.writeText("ok")
            if (!pendingMarker.renameTo(completionMarker) && !completionMarker.exists()) {
                runCatching {
                    pendingMarker.copyTo(completionMarker, overwrite = true)
                    pendingMarker.delete()
                }
                check(completionMarker.exists()) { "completion marker could not be written" }
            }
        }
        return ChapterDownloadOutcome(
            onDisk = onDisk,
            total = total,
            markedComplete = completionMarker.exists()
        )
    }

    /**
     * Downloads one page to [outFile] via a `.part` file + rename.
     *
     * @return true when the page was fetched by this call, false when a
     *   non-empty file was already on disk (the resume path).
     */
    private suspend fun downloadPage(pageUrl: String, referer: String, outFile: File): Boolean {
        if (outFile.exists() && outFile.length() > 0L) return false

        val reqBuilder = Request.Builder()
            .url(pageUrl)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
            )
            .header("Accept", "image/webp,image/apng,image/*,*/*;q=0.8")
        if (referer.isNotBlank()) reqBuilder.header("Referer", referer)
        // U-13: a cancelled cookie lookup must abort the page download, not
        // silently continue with no Cookie header (zombie write after the
        // worker was cancelled).
        suspendRunCatching { resolveCookieForUrl(settingsRepository, pageUrl) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { reqBuilder.header("Cookie", it) }

        val tempFile = File(outFile.absolutePath + ".part")
        if (tempFile.exists()) tempFile.delete()
        okHttpClient.newCall(reqBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code} for $pageUrl")
            val body = response.body ?: error("Empty body for $pageUrl")
            // Per-file cap: a pathological response must not stream unbounded to disk.
            var copied = 0L
            body.byteStream().use { input ->
                tempFile.outputStream().use { out ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        copied += n
                        if (copied > MAX_PAGE_BYTES) error("Page exceeds size cap for $pageUrl")
                        out.write(buf, 0, n)
                    }
                }
            }
        }
        // An HTTP-200 HTML challenge/block page must not complete as N.jpg:
        // size floor + magic sniff engage the existing MAX_DOWNLOAD_ATTEMPTS retry.
        if (tempFile.length() < MIN_PAGE_BYTES || !tempFile.hasImageMagic()) {
            tempFile.delete()
            error("Downloaded non-image body for $pageUrl")
        }
        check(tempFile.renameTo(outFile)) { "page rename failed for $pageUrl" }
        return true
    }

    private fun existingPageCount(dir: File): Int =
        dir.listFiles()?.count { it.isFile && it.extension.lowercase() in PAGE_EXTENSIONS && it.length() > 0L } ?: 0

    companion object {
        /** Marker written only when every page of the chapter is on disk. */
        const val COMPLETION_MARKER = ".completed"

        /** Staging name for the completion marker (atomic rename source). */
        const val COMPLETION_MARKER_PART = ".completed.part"

        private const val PARALLEL_DOWNLOADS = 4
        private const val MIN_PAGE_BYTES = 1024L
        private const val MAX_PAGE_BYTES = 50L * 1024L * 1024L
        private val PAGE_EXTENSIONS = setOf("jpg", "png", "webp")
    }
}

/** Result of one [ChapterDownloadEngine.downloadChapterPages] run. */
data class ChapterDownloadOutcome(
    /** Pages present on disk when the run finished. */
    val onDisk: Int,
    /** Pages the chapter was supposed to have. */
    val total: Int,
    /** True when the completion marker exists (the chapter is readable). */
    val markedComplete: Boolean
)
