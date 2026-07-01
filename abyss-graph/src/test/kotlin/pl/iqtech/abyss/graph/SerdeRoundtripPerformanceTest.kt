package pl.iqtech.abyss.graph

import com.hazelcast.config.Config
import com.hazelcast.internal.serialization.Data
import com.hazelcast.internal.serialization.SerializationService
import com.hazelcast.internal.serialization.impl.DefaultSerializationServiceBuilder
import pl.iqtech.abyss.store.api.EdgeAdapter
import pl.iqtech.abyss.store.api.LongKeyAdapter
import pl.iqtech.abyss.store.api.StringKeyAdapter
import pl.iqtech.abyss.store.api.UuidKeyAdapter
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.measureTime
import kotlin.uuid.Uuid

// Isolates value-payload serde cost (NodeLikeHzSerializer/EdgeLikeHzSerializer) from everything
// else a 3-hop traversal also pays for: IMap lookups, partition routing, frontier expansion, Flow
// collection. No HazelcastInstance/IMap here — just Object -> Data -> Object via the same
// SerializerConfig registerAbyssSerializers wires into a real graph. See TODO.md 3.5.
class SerdeRoundtripPerformanceTest {

    private fun serviceFor(adapter: EdgeAdapter): SerializationService =
        DefaultSerializationServiceBuilder()
            .setConfig(Config().registerAbyssSerializers(adapter, graphTestModule).serializationConfig)
            .build()

    private fun roundtripNanosEach(ss: SerializationService, obj: Any, n: Int): Double {
        repeat(2_000) { ss.toObject<Any>(ss.toData<Data>(obj)) } // warm-up
        val elapsed = measureTime { repeat(n) { ss.toObject<Any>(ss.toData<Data>(obj)) } }
        return elapsed.inWholeNanoseconds.toDouble() / n
    }

    @Test fun `edge and node value serde roundtrip cost per ID type`() {
        if (System.getProperty("perf") == null) return
        val n = 200_000
        val randomStrId = (10 + Random.nextInt(41)).let { len ->
            (1..len).map { ('a'..'z').plus('0'..'9').random() }.joinToString("")
        }

        val uuidSs = serviceFor(UuidKeyAdapter)
        val longSs = serviceFor(LongKeyAdapter)
        val strSs = serviceFor(StringKeyAdapter)

        val uuidEdgeNs = roundtripNanosEach(uuidSs, TestEdge(fromId = Uuid.random(), toId = Uuid.random(), label = ""), n)
        val longEdgeNs = roundtripNanosEach(longSs, LongTestEdge(fromId = 1L, toId = 2L), n)
        val strEdgeNs = roundtripNanosEach(strSs, StrTestEdge(fromId = randomStrId, toId = randomStrId), n)

        val uuidNodeNs = roundtripNanosEach(uuidSs, TestNode(id = Uuid.random(), name = "n"), n)
        val longNodeNs = roundtripNanosEach(longSs, LongTestNode(id = 1L, name = "n"), n)
        val strNodeNs = roundtripNanosEach(strSs, StrTestNode(id = randomStrId, name = "n"), n)

        println("\nValue serde roundtrip, isolated from IMap/partitioning ($n roundtrips each):")
        println("  edge  Uuid: ${"%.0f".format(uuidEdgeNs)} ns/op   Long: ${"%.0f".format(longEdgeNs)} ns/op   String: ${"%.0f".format(strEdgeNs)} ns/op")
        println("  node  Uuid: ${"%.0f".format(uuidNodeNs)} ns/op   Long: ${"%.0f".format(longNodeNs)} ns/op   String: ${"%.0f".format(strNodeNs)} ns/op")
        println("  Uuid/Long ratio  edge: ${"%.1f".format(uuidEdgeNs / longEdgeNs)}x   node: ${"%.1f".format(uuidNodeNs / longNodeNs)}x")
        println("  Uuid/String ratio  edge: ${"%.1f".format(uuidEdgeNs / strEdgeNs)}x   node: ${"%.1f".format(uuidNodeNs / strNodeNs)}x")

        assertTrue(uuidEdgeNs > longEdgeNs, "expected Uuid edge serde to cost more than Long edge serde")
        assertTrue(uuidNodeNs > longNodeNs, "expected Uuid node serde to cost more than Long node serde")
    }
}
