package pl.iqtech.abyss.store.yugabyte

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.AsyncResultSet
import com.datastax.oss.driver.api.core.cql.SimpleStatement
import com.datastax.oss.driver.api.core.metadata.TokenMap
import com.datastax.oss.driver.api.core.metadata.token.TokenRange
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.CompletionStage
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Feasibility spike for TODO 1.23 / ai-scripts/StoreScanCapabilityRFC.md's YCQL section: neither
// ephemeral_nodes nor ephemeral_edges can carry a secondary index (transactions=true, needed for
// one, is incompatible with per-row USING TTL, needed for the other — YugabyteDB #10992), so an
// unfiltered full scan needs token-range partitioning instead of an index. This proves the
// mechanics actually work against the real driver (com.yugabyte:java-driver-core, a YugabyteDB
// fork, not vanilla Cassandra) and the real container, per the RFC's explicit ask not to assume
// 1:1 Cassandra token-map compatibility. Not production code — doesn't belong on AbyssStoreLike yet.
class TokenRangeScanFeasibilityTest {

    private val keyspace = "abyss_test_graph"

    private fun session(): CqlSession = CqlSession.builder()
        .addContactPoint(InetSocketAddress("localhost", 9042))
        .withLocalDatacenter("datacenter1")
        .build()

    @Test fun `db is alive`() {
        session().use { s ->
            val row = s.execute("SELECT release_version FROM system.local").one()
            assertTrue(row?.getString("release_version") != null, "expected a release_version from system.local")
        }
    }

    @Test fun `token-range scan enumerates every planted row in ephemeral_nodes exactly once, no ALLOW FILTERING`() {
        session().use { s ->
            // Distinct marker per run isolates this test's rows from anything else LoadTest.kt (or a
            // prior run) has left in this shared keyspace/table.
            val markerType = "token_scan_spike_${UUID.randomUUID()}"
            val plantedIds = List(500) { UUID.randomUUID() }
            val insert = s.prepare(
                "INSERT INTO $keyspace.ephemeral_nodes (id, type, data, tags, created_at, updated_at, ttl_expiration) " +
                "VALUES (?, ?, 'x', {}, toTimestamp(now()), toTimestamp(now()), toTimestamp(now())) USING TTL 300"
            )
            plantedIds.forEach { id -> s.execute(insert.bind(idBuf(id), markerType)) }

            val tokenMap = s.metadata.tokenMap.orElseThrow { IllegalStateException("driver returned no TokenMap for this session") }
            val ranges = tokenMap.tokenRanges.flatMap { it.unwrap() }
            assertTrue(ranges.isNotEmpty(), "expected at least one token range")

            val found = mutableListOf<UUID>()
            for (range in ranges) {
                val startTok = tokenMap.format(range.start)
                val endTok = tokenMap.format(range.end)
                val rs = s.execute(
                    SimpleStatement.newInstance(
                        "SELECT id, type FROM $keyspace.ephemeral_nodes WHERE token(id) > $startTok AND token(id) <= $endTok"
                    )
                )
                for (row in rs) {
                    if (row.getString("type") == markerType) found += uuidFrom(row.getByteBuffer("id")!!)
                }
            }

            assertEquals(plantedIds.size, found.size, "duplicate or missing rows across token ranges")
            assertEquals(plantedIds.toSet(), found.toSet())
        }
    }

    // Production scanNodeIds() would be Flow-based and driven by executeAsync (blocking `execute()`
    // ties up a thread per in-flight page; the real design needs to fan out many ranges at once
    // without one thread per range). This proves the same token-range mechanics hold when consumed
    // that way: executeAsync -> AsyncResultSet paging (currentPage/hasMorePages/fetchNextPage) ->
    // emitted into a Flow, one range at a time via flatMapConcat.
    @Test fun `token-range scan via executeAsync consumed as a Flow enumerates every planted row exactly once`() = runBlocking {
        session().use { s ->
            val markerType = "token_scan_spike_async_${UUID.randomUUID()}"
            val plantedIds = List(500) { UUID.randomUUID() }
            val insert = s.prepare(
                "INSERT INTO $keyspace.ephemeral_nodes (id, type, data, tags, created_at, updated_at, ttl_expiration) " +
                "VALUES (?, ?, 'x', {}, toTimestamp(now()), toTimestamp(now()), toTimestamp(now())) USING TTL 300"
            )
            plantedIds.forEach { id -> s.execute(insert.bind(idBuf(id), markerType)) }

            val tokenMap = s.metadata.tokenMap.orElseThrow { IllegalStateException("driver returned no TokenMap for this session") }
            val ranges = tokenMap.tokenRanges.flatMap { it.unwrap() }
            assertTrue(ranges.isNotEmpty(), "expected at least one token range")

            val found = ranges.asFlow()
                .flatMapConcat { range -> scanRangeAsync(s, tokenMap, range, markerType) }
                .toList()

            assertEquals(plantedIds.size, found.size, "duplicate or missing rows across token ranges")
            assertEquals(plantedIds.toSet(), found.toSet())
        }
    }

    // 4-way concurrent fan-out: the whole point of doing this via executeAsync instead of blocking
    // execute() is to scan many ranges at once without pinning a thread per range. splitEvenly(4)
    // per unwrapped range guarantees a clean multiple of 4 total ranges regardless of this
    // container's real (likely single-node/single-tablet) topology, so the "quarter of the whole
    // partition set" split is meaningful even here, not just on a real multi-tablet cluster.
    // channelFlow (not plain flow{}) is required, not a style choice: multiple launched coroutines
    // send() into it concurrently, which plain flow{}'s emit() forbids across coroutines (context-
    // preservation) — channelFlow is backed by a Channel precisely so concurrent producers are safe.
    @Test fun `4 coroutines scan disjoint quarters of the token ranges, merged via channelFlow`() = runBlocking {
        session().use { s ->
            val markerType = "token_scan_spike_quarters_${UUID.randomUUID()}"
            val plantedIds = List(500) { UUID.randomUUID() }
            val insert = s.prepare(
                "INSERT INTO $keyspace.ephemeral_nodes (id, type, data, tags, created_at, updated_at, ttl_expiration) " +
                "VALUES (?, ?, 'x', {}, toTimestamp(now()), toTimestamp(now()), toTimestamp(now())) USING TTL 300"
            )
            plantedIds.forEach { id -> s.execute(insert.bind(idBuf(id), markerType)) }

            val tokenMap = s.metadata.tokenMap.orElseThrow { IllegalStateException("driver returned no TokenMap for this session") }
            val ranges = tokenMap.tokenRanges.flatMap { it.unwrap() }.flatMap { it.splitEvenly(4) }
            assertTrue(ranges.size >= 4, "expected at least 4 ranges after splitEvenly(4), got ${ranges.size}")

            val quarters = ranges.withIndex().groupBy { (i, _) -> i % 4 }.values.map { chunk -> chunk.map { it.value } }
            assertEquals(4, quarters.size, "expected exactly 4 non-empty quarters")

            val found = channelFlow {
                quarters.forEach { quarter ->
                    launch {
                        quarter.forEach { range -> scanRangeAsync(s, tokenMap, range, markerType).collect { send(it) } }
                    }
                }
            }.toList()

            assertEquals(plantedIds.size, found.size, "duplicate or missing rows across concurrent quarter scans")
            assertEquals(plantedIds.toSet(), found.toSet())
        }
    }

    private fun scanRangeAsync(s: CqlSession, tokenMap: TokenMap, range: TokenRange, markerType: String): Flow<UUID> = flow {
        val startTok = tokenMap.format(range.start)
        val endTok = tokenMap.format(range.end)
        var page: AsyncResultSet = s.executeAsync(
            SimpleStatement.newInstance("SELECT id, type FROM $keyspace.ephemeral_nodes WHERE token(id) > $startTok AND token(id) <= $endTok")
        ).asDeferred().await()
        while (true) {
            for (row in page.currentPage()) {
                if (row.getString("type") == markerType) emit(uuidFrom(row.getByteBuffer("id")!!))
            }
            if (!page.hasMorePages()) break
            page = page.fetchNextPage().asDeferred().await()
        }
    }

    // ponytail: bridges CompletionStage -> Deferred without pulling in kotlinx-coroutines-jdk8 —
    // same idiom already used in AbyssSchemaWorker.kt.
    private fun <T> CompletionStage<T>.asDeferred(): Deferred<T> = CompletableDeferred<T>().also { d ->
        whenComplete { v, ex -> if (ex != null) d.completeExceptionally(ex) else d.complete(v) }
    }

    private fun idBuf(id: UUID): ByteBuffer {
        val buf = ByteBuffer.allocate(16)
        buf.putLong(id.mostSignificantBits)
        buf.putLong(id.leastSignificantBits)
        buf.flip()
        return buf
    }

    private fun uuidFrom(buf: ByteBuffer): UUID {
        val b = buf.duplicate()
        return UUID(b.long, b.long)
    }
}
