package com.mmcdole.tether.data.world

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test

class WorldRepositoryTest {
    private val dao = FakeWorldDao()
    private val clock = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC)
    private val repository = WorldRepository(dao, testSecrets(), clock, Dispatchers.Unconfined)
    private val viking = World(name = "Viking – Thorin", host = "host.example", port = 2001)

    @Test
    fun passwordIsStoredEncryptedAndDecryptedOnDemand() = runTest {
        val id = repository.save(viking, PasswordEdit.Set("hunter2"))
        val stored = dao.get(id)!!.encryptedPassword!!
        assertNotEquals("hunter2", stored)
        assertTrue(repository.get(id)!!.hasPassword)
        assertEquals("hunter2", repository.password(id))
    }

    @Test
    fun editKeepsPasswordCreatedAtAndLastConnected() = runTest {
        val id = repository.save(viking, PasswordEdit.Set("hunter2"))
        dao.setLastConnected(id, 500)
        repository.save(viking.copy(id = id, name = "Viking – Alt", port = 2002))

        val row = dao.get(id)!!
        assertEquals("Viking – Alt", row.name)
        assertEquals(2002, row.port)
        assertEquals(1_000L, row.createdAt)
        assertEquals(500L, row.lastConnectedAt)
        assertEquals("hunter2", repository.password(id))
    }

    @Test
    fun clearRemovesPassword() = runTest {
        val id = repository.save(viking, PasswordEdit.Set("hunter2"))
        repository.save(viking.copy(id = id), PasswordEdit.Clear)
        assertNull(dao.get(id)!!.encryptedPassword)
        assertNull(repository.password(id))
    }

    @Test
    fun listsInCreationOrderAndDeletes() = runTest {
        val first = repository.save(viking)
        repository.save(viking.copy(name = "Test MUD"))
        repository.delete(first)
        assertEquals(listOf("Test MUD"), repository.worlds.first().map { it.name })
    }
}
