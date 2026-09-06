package com.exapps.mangaworld.core.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.Runs
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression tests for ParentalControlManager PIN handling.
 *
 * The v2 path once compared hashPin(pin) — which mints a FRESH random salt on
 * every call — against the stored hash, so verification could never succeed.
 * These tests pin the correct behavior: set/verify round-trip with distinct
 * salts per row, wrong-PIN rejection, legacy hashCode upgrade, and
 * fail-closed malformed rows.
 */
class ParentalControlManagerTest {

    private val store = mutableMapOf<String, Any?>()
    private lateinit var manager: ParentalControlManager

    @Before
    fun setUp() {
        store.clear()
        // android.util.Base64 is a framework stub on JVM unit tests — delegate
        // to java.util.Base64 so the real encode/decode logic is exercised.
        mockkStatic(Base64::class)
        every { Base64.encodeToString(any(), any()) } answers {
            java.util.Base64.getEncoder().encodeToString(firstArg())
        }
        every { Base64.decode(any<String>(), any()) } answers {
            java.util.Base64.getDecoder().decode(firstArg<String>())
        }

        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { editor.putString(any(), any()) } answers {
            store[firstArg()] = secondArg<String?>()
            editor
        }
        every { editor.remove(any()) } answers {
            store.remove(firstArg<String>())
            editor
        }
        every { editor.apply() } just Runs

        val prefs = mockk<SharedPreferences>()
        every { prefs.getString(any(), any()) } answers {
            store[it.invocation.args[0] as String] as? String ?: it.invocation.args[1] as String?
        }
        every { prefs.edit() } returns editor

        val context = mockk<Context>()
        every { context.getSharedPreferences(any(), any()) } returns prefs

        manager = ParentalControlManager(context)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `set then verify with correct PIN succeeds`() {
        manager.setPin("1234")

        assertTrue(manager.verifyPin("1234"))
    }

    @Test
    fun `verify with wrong PIN fails`() {
        manager.setPin("1234")

        assertFalse(manager.verifyPin("4321"))
    }

    @Test
    fun `verify with no stored PIN fails`() {
        assertFalse(manager.verifyPin("1234"))
    }

    @Test
    fun `two stored rows use distinct salts`() {
        manager.setPin("1234")
        val first = store["pin_hash"] as String
        manager.setPin("1234")
        val second = store["pin_hash"] as String

        // Distinct salts, yet both rows verify — comparison re-derives with
        // the stored salt instead of comparing fresh hashes.
        assertTrue(first != second)
        assertTrue(manager.verifyPin("1234"))
    }

    @Test
    fun `legacy hashCode row verifies once and upgrades to v2`() {
        store["pin_hash"] = "1234".hashCode().toString()

        assertTrue(manager.verifyPin("1234"))

        val upgraded = store["pin_hash"] as String
        assertTrue(upgraded.startsWith("v2"))
        // Upgraded row keeps verifying (wrong PIN still rejected).
        assertTrue(manager.verifyPin("1234"))
        assertFalse(manager.verifyPin("0000"))
    }

    @Test
    fun `legacy row rejects wrong PIN without upgrading`() {
        store["pin_hash"] = "1234".hashCode().toString()

        assertFalse(manager.verifyPin("0000"))
        assertFalse((store["pin_hash"] as String).startsWith("v2"))
    }

    @Test
    fun `malformed v2 rows fail closed`() {
        store["pin_hash"] = "v2not-a-valid-row"
        assertFalse(manager.verifyPin("1234"))

        store["pin_hash"] = "v2:"
        assertFalse(manager.verifyPin("1234"))

        store["pin_hash"] = "v2:also-no-separator"
        assertFalse(manager.verifyPin("1234"))
    }
}
