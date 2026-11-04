package com.punch.android.gateway

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GatewayCredentialsStoreTest {
    private lateinit var context: Context

    private val prefs get() = context.getSharedPreferences("punch_gateway", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs.edit().clear().commit()
    }

    @Test
    fun passwordIsNotStoredInPlaintext() {
        val store = GatewayCredentialsStore(context, ReversingCipher())
        store.save("http://192.168.1.10:4096", "pi", "hunter2")

        assertEquals("hunter2", store.getPassword())
        assertNull(prefs.getString("password", null))
        prefs.all.values.forEach { assertFalse(it.toString().contains("hunter2")) }
    }

    @Test
    fun migratesLegacyPlaintextPassword() {
        prefs.edit()
            .putString("origin", "http://192.168.1.10:4096")
            .putString("username", "pi")
            .putString("password", "hunter2")
            .commit()

        val store = GatewayCredentialsStore(context, ReversingCipher())

        assertEquals("hunter2", store.getPassword())
        assertNull(prefs.getString("password", null))
        prefs.all.values.forEach { assertFalse(it.toString().contains("hunter2")) }
    }

    @Test
    fun undecryptablePasswordIsDropped() {
        GatewayCredentialsStore(context, ReversingCipher()).save("http://192.168.1.10:4096", "pi", "hunter2")

        val store = GatewayCredentialsStore(context, FailingCipher())

        assertEquals("", store.getPassword())
        assertTrue(store.isConfigured())
        assertNull(prefs.getString("password_enc", null))
    }

    @Test
    fun appDataIsNotBackedUp() {
        val flags = context.applicationInfo.flags
        assertEquals(0, flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }

    private class ReversingCipher : SecretCipher {
        override fun encrypt(plaintext: String) = "enc:" + plaintext.reversed()
        override fun decrypt(encoded: String) = encoded.removePrefix("enc:").reversed()
    }

    private class FailingCipher : SecretCipher {
        override fun encrypt(plaintext: String) = error("unused")
        override fun decrypt(encoded: String): String? = null
    }
}
