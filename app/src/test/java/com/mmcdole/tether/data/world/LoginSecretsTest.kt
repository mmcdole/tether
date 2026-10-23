package com.mmcdole.tether.data.world

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LoginSecretsTest {
    private val secrets = testSecrets()

    @Test
    fun roundTrips() {
        assertEquals("hunter2 ünïcode", secrets.decrypt(secrets.encrypt("hunter2 ünïcode")))
    }

    @Test
    fun ciphertextHidesPasswordAndUsesFreshIv() {
        val a = secrets.encrypt("hunter2")
        val b = secrets.encrypt("hunter2")
        assertFalse("hunter2" in a)
        assertNotEquals(a, b)
    }

    @Test
    fun wrongKeyOrGarbageDecryptsToNull() {
        assertNull(testSecrets().decrypt(secrets.encrypt("hunter2")))
        assertNull(secrets.decrypt("not base64!"))
        assertNull(secrets.decrypt("AAAA"))
    }
}
