package pl.iqtech.abyss.graph

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import pl.iqtech.abyss.dsl.outEdges
import pl.iqtech.abyss.store.api.LongKeyAdapter
import pl.iqtech.abyss.store.api.SchemaTagWidth
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// Phase A zero-overhead check: AbyssGraph(NONE).singleSchema must hold the raw adapter directly, so
// the EdgeKey Compact encoding is byte-identical to a standalone AbyssGraphSchema on the same maps.
// Proven by interop: write through the container, read through a directly-constructed schema on the
// SAME maps — a hex-vs-native key mismatch would make the read miss.
class SingleSchemaTest {

    @BeforeTest fun clear() {
        listOf("ss-nodes", "ss-edges", "ss-edges-reverse").forEach { longTestHz.getMap<Any, Any>(it).clear() }
    }

    @Test fun singleSchemaKeysAreByteIdenticalToStandalone() = runBlocking {
        val container = AbyssGraph(longTestHz, SchemaTagWidth.NONE, "ss-nodes", "ss-edges").singleSchema(LongKeyAdapter)
        val standalone = AbyssGraphSchema(LongKeyAdapter, longTestHz, "ss-nodes", "ss-edges")

        container.transaction {
            addNode(LongTestNode(1L)); addNode(LongTestNode(2L)); addEdge(LongTestEdge(1L, 2L))
        }

        // Read back through the standalone schema on the same maps: only succeeds if the container
        // wrote the EdgeKey with LongKeyAdapter's native Int64 encoding (no SchemaKeyAdapter hex).
        val out = standalone.outEdges<LongTestEdge>(1L).toList()
        assertEquals(1, out.size)
        assertEquals(2L, out.single().toId)
    }

    @Test fun singleSchemaRejectsSecondRegistrationAndTaggedRegister() {
        val container = AbyssGraph(longTestHz, SchemaTagWidth.NONE, "ss-nodes", "ss-edges")
        container.singleSchema(LongKeyAdapter)
        assertFailsWith<IllegalArgumentException> { container.singleSchema(LongKeyAdapter) }
        assertFailsWith<IllegalArgumentException> { container.register(1L, LongKeyAdapter) }
    }
}
