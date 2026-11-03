package dev.kitchenapp.core.domain.identifiers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class IdsTest {

    @Test
    fun `EntityId wraps and renders the uuid`() {
        val uuid = Uuid.random()
        val id = EntityId(uuid)
        assertEquals(uuid, id.value)
        assertEquals(uuid.toString(), id.toString())
    }

    @Test
    fun `EntityId equality follows uuid value`() {
        val uuid = Uuid.random()
        assertEquals(EntityId(uuid), EntityId(uuid))
        assertNotEquals(EntityId(uuid), EntityId(Uuid.random()))
    }

    @Test
    fun `EntityId generate creates unique v4 ids`() {
        assertNotEquals(EntityId.generate(), EntityId.generate())
    }

    @Test
    fun `SketchId wraps and renders the uuid`() {
        val uuid = Uuid.random()
        val id = SketchId(uuid)
        assertEquals(uuid, id.value)
        assertEquals(uuid.toString(), id.toString())
    }

    @Test
    fun `SketchId generate creates unique v4 ids`() {
        assertNotEquals(SketchId.generate(), SketchId.generate())
    }

    @Test
    fun `different id types are never equal even for the same uuid`() {
        val uuid = Uuid.random()
        assertTrue(EntityId(uuid) != SketchId(uuid) as Any)
    }
}
