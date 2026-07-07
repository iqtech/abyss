package pl.iqtech.abyss.graph

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import pl.iqtech.abyss.dsl.outEdges
import pl.iqtech.abyss.store.api.HeaderlessKeyAdapter
import pl.iqtech.abyss.store.api.IntKeyAdapter
import pl.iqtech.abyss.store.api.LongKeyAdapter
import pl.iqtech.abyss.store.api.StringKeyAdapter
import pl.iqtech.abyss.store.api.UuidKeyAdapter
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

// TODO 1.19 zero-overhead check: SingleSchemaGraph's NodeId is raw adapter-encoded bytes, no 1.15
// header byte at all. Proven two ways: (1) interop between two independently-constructed
// SingleSchemaGraph handles over the same maps (a wire-format mismatch would make the read miss),
// (2) NodeId.bytes.size matches the adapter's raw payload size exactly, with no +1 for a header.
class SingleSchemaTest {

    @BeforeTest fun clear() {
        listOf("ss-nodes", "ss-edges", "ss-edges-adjacency").forEach { longTestHz.getMap<Any, Any>(it).clear() }
    }

    @Test fun singleSchemaGraphHandlesInteroperateOverSameMaps() = runBlocking {
        val a = SingleSchemaGraph(LongKeyAdapter, longTestHz, "ss-nodes", "ss-edges", module = graphTestModule)
        val b = SingleSchemaGraph(LongKeyAdapter, longTestHz, "ss-nodes", "ss-edges", module = graphTestModule)

        a.transaction {
            addNode(LongTestNode(1L)); addNode(LongTestNode(2L)); addEdge(LongTestEdge(1L, 2L))
        }

        val out = b.outEdges<LongTestEdge>(1L).toList()
        assertEquals(1, out.size)
        assertEquals(2L, out.single().toId)
    }

    @Test fun headerlessNodeIdHasNoHeaderByte() {
        assertEquals(8, HeaderlessKeyAdapter(LongKeyAdapter).toNodeId(1L).bytes.size)
        assertEquals(16, HeaderlessKeyAdapter(UuidKeyAdapter).toNodeId(Uuid.random()).bytes.size)
        assertEquals(4, HeaderlessKeyAdapter(IntKeyAdapter).toNodeId(1).bytes.size)
        assertEquals("hi".toByteArray().size, HeaderlessKeyAdapter(StringKeyAdapter).toNodeId("hi").bytes.size)
    }
}
