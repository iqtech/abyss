package pl.iqtech.abyss.graph

import com.hazelcast.internal.serialization.Data
import com.hazelcast.internal.serialization.SerializationService
import com.hazelcast.spi.impl.SerializationServiceSupport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

// Round-trips AdjacencyMutationProcessor through the real Compact path the clustered EntryProcessor
// takes. Guards the nodeTypeTag=null case (unresolvable neighbor at write time) that a stray `!!` on
// the read side used to NPE on — a bug invisible to single-embedded-member tests, which never
// serialize the processor. See fable.md 1.1. Uses graphTestHz's serialization service because the
// nested writeCompact(NodeId) needs a compact schema service a bare builder doesn't have.
class AdjacencyMutationProcessorSerdeTest {

    private val ss = (graphTestHz as SerializationServiceSupport).serializationService as SerializationService

    private fun roundtrip(m: AdjacencyMutation): AdjacencyEntry {
        val back = ss.toObject<AdjacencyMutationProcessor>(ss.toData<Data>(AdjacencyMutationProcessor(m)))
        return (back.mutation as AdjacencyMutation.Add).entry
    }

    @Test fun `Add with null nodeTypeTag round-trips`() {
        val neighbor = huid.toNodeId(Uuid.random())
        val entry = roundtrip(AdjacencyMutation.Add(AdjacencyEntry(neighbor, nodeTypeTag = null, edgeTypeTag = 7)))
        assertNull(entry.nodeTypeTag)
        assertEquals(7.toShort(), entry.edgeTypeTag)
        assertEquals(neighbor, entry.neighborId)
    }

    @Test fun `Add with present nodeTypeTag round-trips`() {
        val neighbor = huid.toNodeId(Uuid.random())
        val entry = roundtrip(AdjacencyMutation.Add(AdjacencyEntry(neighbor, nodeTypeTag = 3, edgeTypeTag = 7)))
        assertEquals(3.toShort(), entry.nodeTypeTag)
    }
}
