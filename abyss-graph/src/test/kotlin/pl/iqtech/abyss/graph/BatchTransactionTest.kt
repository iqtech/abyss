package pl.iqtech.abyss.graph

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import kotlinx.coroutines.runBlocking
import pl.iqtech.abyss.store.api.AbyssError
import pl.iqtech.abyss.store.api.AbyssStoreLike
import pl.iqtech.abyss.store.api.AbyssStoreTransactionLike
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.NodeId
import pl.iqtech.abyss.store.api.NodeLike
import pl.iqtech.abyss.store.api.UuidKeyAdapter
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.uuid.Uuid

// TODO 1.21: batchTransaction is a store-throughput concern — chunking itself only happens inside
// YugabytePersistentStore.commitYsqlBatched (covered by LoadTest against a live YugabyteDB). At the
// worker/AbyssGraphSchema level, what's testable without a real DB is the wiring: batchTransaction
// routes to persistentStore.batchTransaction (not .transaction), threads batchSize through, and
// still gets cascade-delete/integrity-check/graph-correctness right, same as transaction{} does.
class BatchTransactionTest {

    @AfterTest fun clear() {
        graphTestHz.getMap<Any, Any>("bt-nodes").clear()
        graphTestHz.getMap<Any, Any>("bt-edges").clear()
        graphTestHz.getMap<Any, Any>("bt-edges-adjacency").clear()
    }

    @Test fun `batchTransaction commits adds and produces correct final graph state`() {
        runBlocking {
            val fake = RecordingBatchStore()
            val g = AbyssGraphSchema(UuidKeyAdapter, graphTestHz, "bt-nodes", "bt-edges", persistentStore = fake, module = graphTestModule)
            val a = TestNode(id = Uuid.random(), name = "a")
            val b = TestNode(id = Uuid.random(), name = "b")
            val edge = TestEdge(fromId = a.id, toId = b.id, label = "knows")

            val result = g.batchTransaction { addNode(a); addNode(b); addEdge(edge) }

            assertTrue(result.isRight())
            assertIs<Either.Right<NodeLike<*>>>(g.node(a.id))
            assertIs<Either.Right<EdgeLike<*, *>>>(g.edge(a.id, b.id, "test_edge"))
        }
    }

    @Test fun `batchTransaction routes through store batchTransaction, not transaction`() {
        runBlocking {
            val fake = RecordingBatchStore()
            val g = AbyssGraphSchema(UuidKeyAdapter, graphTestHz, "bt-nodes", "bt-edges", persistentStore = fake, module = graphTestModule)

            g.batchTransaction { addNode(TestNode(id = Uuid.random(), name = "x")) }

            assertEquals(1, fake.batchCallCount)
            assertEquals(0, fake.transactionCallCount)
        }
    }

    @Test fun `batchTransaction threads batchSize through to the store`() {
        runBlocking {
            val fake = RecordingBatchStore()
            val g = AbyssGraphSchema(UuidKeyAdapter, graphTestHz, "bt-nodes", "bt-edges", persistentStore = fake, module = graphTestModule)

            g.batchTransaction(batchSize = 250) { addNode(TestNode(id = Uuid.random(), name = "x")) }

            assertEquals(250, fake.batchSizeSeen)
        }
    }

    @Test fun `batchTransaction defaults batchSize to 1000`() {
        runBlocking {
            val fake = RecordingBatchStore()
            val g = AbyssGraphSchema(UuidKeyAdapter, graphTestHz, "bt-nodes", "bt-edges", persistentStore = fake, module = graphTestModule)

            g.batchTransaction { addNode(TestNode(id = Uuid.random(), name = "x")) }

            assertEquals(1000, fake.batchSizeSeen)
        }
    }

    @Test fun `batchTransaction removeNode cascades to connected edges`() {
        runBlocking {
            val fake = RecordingBatchStore()
            val g = AbyssGraphSchema(UuidKeyAdapter, graphTestHz, "bt-nodes", "bt-edges", persistentStore = fake, module = graphTestModule)
            val hub = TestNode(id = Uuid.random(), name = "hub")
            val other = TestNode(id = Uuid.random(), name = "other")
            val out = TestEdge(fromId = hub.id, toId = other.id, label = "out")
            val inc = TestEdge(fromId = other.id, toId = hub.id, label = "in")
            g.batchTransaction { addNode(hub); addNode(other); addEdge(out); addEdge(inc) }

            g.batchTransaction { removeNode(hub.id) }

            assertIs<Either.Left<AbyssError>>(g.node(hub.id))
            assertIs<Either.Left<AbyssError>>(g.edge(hub.id, other.id, "test_edge"))
            assertIs<Either.Left<AbyssError>>(g.edge(other.id, hub.id, "test_edge"))
        }
    }

    @Test fun `batchTransaction integrity check resolves nodes added earlier in the same call`() {
        runBlocking {
            val fake = RecordingBatchStore()
            val g = AbyssGraphSchema(UuidKeyAdapter, graphTestHz, "bt-nodes", "bt-edges", persistentStore = fake, module = graphTestModule)
            val a = TestNode(id = Uuid.random(), name = "a")
            val b = TestNode(id = Uuid.random(), name = "b")

            val result = g.batchTransaction(checkIntegrity = true) {
                addNode(a); addNode(b); addEdge(TestEdge(fromId = a.id, toId = b.id, label = "knows"))
            }

            assertTrue(result.isRight())
        }
    }

    @Test fun `batchTransaction integrity check fails for edge to nonexistent node`() {
        runBlocking {
            val fake = RecordingBatchStore()
            val g = AbyssGraphSchema(UuidKeyAdapter, graphTestHz, "bt-nodes", "bt-edges", persistentStore = fake, module = graphTestModule)
            val a = TestNode(id = Uuid.random(), name = "a")

            val result = g.batchTransaction(checkIntegrity = true) {
                addNode(a); addEdge(TestEdge(fromId = a.id, toId = Uuid.random(), label = "knows"))
            }

            assertIs<Either.Left<AbyssError>>(result)
            assertIs<AbyssError.IntegrityError>(result.value)
        }
    }

    @Test fun `batchTransaction returns Left when store fails`() {
        runBlocking {
            val fake = RecordingBatchStore(failBatch = true)
            val g = AbyssGraphSchema(UuidKeyAdapter, graphTestHz, "bt-nodes", "bt-edges", persistentStore = fake, module = graphTestModule)

            val result = g.batchTransaction { addNode(TestNode(id = Uuid.random(), name = "x")) }

            assertIs<Either.Left<AbyssError>>(result)
        }
    }
}

// Records batchTransaction() vs transaction() call counts and the batchSize it was given, so tests
// can assert batchTransaction actually routes through the store's batch path rather than reusing
// transaction()'s. Real chunking (addBatch()/executeBatch() per batchSize-sized chunk) only exists
// in YugabytePersistentStore and is covered by LoadTest against a live YugabyteDB.
private class RecordingBatchStore(private val failBatch: Boolean = false) : AbyssStoreLike {
    var transactionCallCount = 0
    var batchCallCount = 0
    var batchSizeSeen: Int? = null

    override suspend fun loadNode(id: NodeId): Either<AbyssError, Pair<NodeLike<*>?, Duration?>> = Either.Right(null to null)
    override suspend fun loadEdge(fromId: NodeId, toId: NodeId, type: String): Either<AbyssError, Pair<EdgeLike<*, *>?, Duration?>> = Either.Right(null to null)

    override suspend fun transaction(block: suspend AbyssStoreTransactionLike.() -> Unit): Either<AbyssError, Unit> {
        transactionCallCount++
        applyBlock(block)
        return Unit.right()
    }

    override suspend fun batchTransaction(batchSize: Int, block: suspend AbyssStoreTransactionLike.() -> Unit): Either<AbyssError, Unit> {
        batchCallCount++
        batchSizeSeen = batchSize
        if (failBatch) return AbyssError.Unexpected(RuntimeException("store down")).left()
        applyBlock(block)
        return Unit.right()
    }

    private suspend fun applyBlock(block: suspend AbyssStoreTransactionLike.() -> Unit) {
        val tx = object : AbyssStoreTransactionLike {
            override fun saveNode(id: NodeId, node: NodeLike<*>, tags: Set<String>) {}
            override fun saveEdge(fromId: NodeId, toId: NodeId, edge: EdgeLike<*, *>, tags: Set<String>) {}
            override fun deleteNode(id: NodeId) {}
            override fun deleteEdge(fromId: NodeId, toId: NodeId, type: String) {}
        }
        tx.block()
    }
}
