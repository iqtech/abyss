package pl.iqtech.abyss.graph

import arrow.core.Either
import kotlinx.coroutines.runBlocking
import pl.iqtech.abyss.store.api.AbyssError
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

// Dedicated to checkIntegrity alone: the referential-integrity (endpoint existence) and
// @EdgeConstraint (schema) checks addEdge/modifyEdge run when checkIntegrity=true, and skip
// entirely when checkIntegrity=false — across both transaction{} and ephemeral{}.
class CheckIntegrityTest {

    @BeforeTest fun clear() {
        graphTestHz.getMap<Any, Any>("g-nodes").clear()
        graphTestHz.getMap<Any, Any>("g-edges").clear()
        graphTestHz.getMap<Any, Any>("g-edges-adjacency").clear()
    }

    // --- Referential integrity (endpoint existence), transaction{} ------------------------------

    @Test fun `addEdge succeeds when both endpoints exist`() {
        runBlocking {
            val a = TestNode(id = Uuid.random(), name = "a")
            val b = TestNode(id = Uuid.random(), name = "b")
            graphTest.transaction { addNode(a); addNode(b) }
            val result = graphTest.transaction { addEdge(TestEdge(fromId = a.id, toId = b.id, label = "x")) }
            assertIs<Either.Right<Unit>>(result)
        }
    }

    @Test fun `addEdge returns IntegrityError when fromId node absent`() {
        runBlocking {
            val b = TestNode(id = Uuid.random(), name = "b")
            graphTest.transaction { addNode(b) }
            val result = graphTest.transaction { addEdge(TestEdge(fromId = Uuid.random(), toId = b.id, label = "x")) }
            assertIs<Either.Left<AbyssError>>(result)
            assertIs<AbyssError.IntegrityError>(result.value)
        }
    }

    @Test fun `addEdge returns IntegrityError when toId node absent`() {
        runBlocking {
            val a = TestNode(id = Uuid.random(), name = "a")
            graphTest.transaction { addNode(a) }
            val result = graphTest.transaction { addEdge(TestEdge(fromId = a.id, toId = Uuid.random(), label = "x")) }
            assertIs<Either.Left<AbyssError>>(result)
            assertIs<AbyssError.IntegrityError>(result.value)
        }
    }

    @Test fun `addEdge with checkIntegrity=false skips the existence check even when both endpoints are absent`() {
        runBlocking {
            val result = graphTest.transaction(checkIntegrity = false) {
                addEdge(TestEdge(fromId = Uuid.random(), toId = Uuid.random(), label = "dangling"))
            }
            assertIs<Either.Right<Unit>>(result)
        }
    }

    @Test fun `addEdge is satisfied by a node added earlier in the same transaction`() {
        runBlocking {
            val a = TestNode(id = Uuid.random(), name = "a")
            val b = TestNode(id = Uuid.random(), name = "b")
            val result = graphTest.transaction {
                addNode(a); addNode(b)
                addEdge(TestEdge(fromId = a.id, toId = b.id, label = "x"))
            }
            assertIs<Either.Right<Unit>>(result)
        }
    }

    // --- Schema constraint (@EdgeConstraint on TypedEdge: both endpoints must be TestNode) --------

    @Test fun `addEdge succeeds when both endpoints satisfy the edge's type constraint`() {
        runBlocking {
            val a = TestNode(id = Uuid.random(), name = "a")
            val b = TestNode(id = Uuid.random(), name = "b")
            graphTest.transaction { addNode(a); addNode(b) }
            val result = graphTest.transaction { addEdge(TypedEdge(fromId = a.id, toId = b.id)) }
            assertIs<Either.Right<Unit>>(result)
        }
    }

    @Test fun `addEdge returns SchemaError when fromId violates the edge's type constraint`() {
        runBlocking {
            val bad = OtherNode(id = Uuid.random())
            val good = TestNode(id = Uuid.random(), name = "good")
            graphTest.transaction(checkIntegrity = false) { addNode(bad); addNode(good) }
            val result = graphTest.transaction { addEdge(TypedEdge(fromId = bad.id, toId = good.id)) }
            assertIs<Either.Left<AbyssError>>(result)
            assertIs<AbyssError.SchemaError>(result.value)
        }
    }

    @Test fun `addEdge returns SchemaError when toId violates the edge's type constraint`() {
        runBlocking {
            val good = TestNode(id = Uuid.random(), name = "good")
            val bad = OtherNode(id = Uuid.random())
            graphTest.transaction(checkIntegrity = false) { addNode(good); addNode(bad) }
            val result = graphTest.transaction { addEdge(TypedEdge(fromId = good.id, toId = bad.id)) }
            assertIs<Either.Left<AbyssError>>(result)
            assertIs<AbyssError.SchemaError>(result.value)
        }
    }

    @Test fun `addEdge with checkIntegrity=false bypasses the schema check too`() {
        runBlocking {
            val bad = OtherNode(id = Uuid.random())
            val good = TestNode(id = Uuid.random(), name = "good")
            graphTest.transaction(checkIntegrity = false) { addNode(bad); addNode(good) }
            val result = graphTest.transaction(checkIntegrity = false) { addEdge(TypedEdge(fromId = bad.id, toId = good.id)) }
            assertIs<Either.Right<Unit>>(result)
        }
    }

    // --- modifyEdge: only the NEW endpoint is integrity-checked, never the old one ------------------

    @Test fun `modifyEdge returns IntegrityError when the new endpoint is absent`() {
        runBlocking {
            val a = TestNode(id = Uuid.random(), name = "a")
            val b = TestNode(id = Uuid.random(), name = "b")
            graphTest.transaction { addNode(a); addNode(b); addEdge(TestEdge(fromId = a.id, toId = b.id, label = "x")) }
            val result = graphTest.transaction {
                modifyEdge(a.id, b.id, "test_edge") { old -> (old as TestEdge).copy(toId = Uuid.random()) }
            }
            assertIs<Either.Left<AbyssError>>(result)
            assertIs<AbyssError.IntegrityError>(result.value)
        }
    }

    @Test fun `modifyEdge does not re-check the old endpoint, only the new one`() {
        runBlocking {
            // A ghost edge whose own endpoints never existed — only writable at all because
            // checkIntegrity=false skipped the check on the way in.
            val a = TestNode(id = Uuid.random(), name = "a")
            val b = TestNode(id = Uuid.random(), name = "b")
            val ghost = TestEdge(fromId = Uuid.random(), toId = Uuid.random(), label = "ghost")
            graphTest.transaction(checkIntegrity = false) { addNode(a); addNode(b); addEdge(ghost) }

            // The transform discards the ghost's invalid endpoints entirely and rebuilds the edge
            // between two real nodes — this must succeed even with checkIntegrity=true, proving the
            // old (never valid) endpoints are never re-checked, only the transformed result is.
            val result = graphTest.transaction {
                modifyEdge(ghost.fromId, ghost.toId, "test_edge") { _ -> TestEdge(fromId = a.id, toId = b.id, label = "real") }
            }
            assertIs<Either.Right<Unit>>(result)
        }
    }

    // --- Referential integrity, ephemeral{} (separate builder, same integrityError plumbing) -------

    @Test fun `ephemeral addEdge returns IntegrityError when an endpoint is absent`() {
        runBlocking {
            val a = TestNode(id = Uuid.random(), name = "a")
            graphTest.transaction { addNode(a) }
            val result = graphTest.ephemeral(60.seconds) { addEdge(TestEdge(fromId = a.id, toId = Uuid.random(), label = "eph")) }
            assertIs<Either.Left<AbyssError>>(result)
            assertIs<AbyssError.IntegrityError>(result.value)
        }
    }

    @Test fun `ephemeral addEdge with checkIntegrity=false skips the existence check`() {
        runBlocking {
            val result = graphTest.ephemeral(60.seconds, checkIntegrity = false) {
                addEdge(TestEdge(fromId = Uuid.random(), toId = Uuid.random(), label = "eph-dangling"))
            }
            assertIs<Either.Right<Unit>>(result)
        }
    }
}
