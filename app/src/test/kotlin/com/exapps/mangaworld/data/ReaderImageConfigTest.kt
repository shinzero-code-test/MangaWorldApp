package com.exapps.mangaworld.data

import com.exapps.mangaworld.core.data.ReaderImageConfig
import com.exapps.mangaworld.domain.model.ReaderImageFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U-26/U-27 shared page-request config: decode cap + software-bitmap
 * gating. Pure values — the cache-key parity itself is covered by the
 * device test (PrefetchDisplayCacheTest) and the macrobenchmark spec.
 */
class ReaderImageConfigTest {

    @Test
    fun decodeCap_isBounded() {
        assertEquals(4096, ReaderImageConfig.DECODE_MAX_SIDE_PX)
    }

    @Test
    fun softwareBitmap_onlyForSmartCrop() {
        assertTrue(ReaderImageConfig.needsSoftwareBitmap(ReaderImageFilter.SMART_CROP))
        ReaderImageFilter.entries
            .filter { it != ReaderImageFilter.SMART_CROP }
            .forEach { assertFalse("filter=$it", ReaderImageConfig.needsSoftwareBitmap(it)) }
    }
}
