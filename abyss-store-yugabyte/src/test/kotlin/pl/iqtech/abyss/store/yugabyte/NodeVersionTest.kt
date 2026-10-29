package pl.iqtech.abyss.store.yugabyte

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import pl.iqtech.abyss.store.api.AbyssStoreTransactionLike
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.NodeLike
import pl.iqtech.abyss.store.api.UuidKeyAdapter
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

// nodes-history idea.md item 2: every node upsert, plain included, increments nodes.version by exactly 1
// per save on both commit paths. Runs the real store against its own schema: the production tables
// (LIKE abyss.* INCLUDING ALL) plus the planned `version BIGINT NOT NULL DEFAULT 1` migration, so the shared
// abyss schema is untouched. Requires the live YugabyteDB container (localhost:5433).
class NodeVersionTest {

    private val url = "jdbc:yugabytedb://localhost:5433/abyss_test_graph"
    private val schema = "abyss_nodever"

    private fun ddl(vararg sql: String) = DriverManager.getConnection(url, "abyss", "abyss").use { c ->
        c.createStatement().use { s -> sql.forEach(s::execute) }
    }

    @BeforeTest fun createSchema() = ddl(
        "DROP SCHEMA IF EXISTS $schema CASCADE",
        "CREATE SCHEMA $schema",
        "CREATE TABLE $schema.nodes (LIKE abyss.nodes INCLUDING ALL)",
        "ALTER TABLE $schema.nodes ADD COLUMN version BIGINT NOT NULL DEFAULT 1",
        "CREATE TABLE $schema.edges (LIKE abyss.edges INCLUDING ALL)",
    )

    // Created after the schema: property initializers run before @BeforeTest, so the store is lazy.
    private val store by lazy {
        YugabytePersistentStore.create(
            ysqlUrl = url,
            ysqlUser = "abyss",
            ysqlPassword = "abyss",
            ysqlSchema = schema,
            module = SerializersModule {
                polymorphic(NodeLike::class) { subclass(YbTestNode::class) }
                polymorphic(EdgeLike::class) { subclass(YbTestEdge::class) }
            },
            ysqlMaxPoolSize = 2,
        )
    }

    @AfterTest fun dropSchema() {
        store.close()
        ddl("DROP SCHEMA IF EXISTS $schema CASCADE")
    }

    // Three saves over two transactions, the second holding two saves of the same id.
    @Test fun `commitYsql - plain node version counts every save`() = runBlocking {
        val a = Uuid.random()
        assertEquals(null, store.transaction { save(a, "t0") }.leftOrNull()?.toString())
        assertEquals(null, store.transaction { save(a, "t1"); save(a, "t2") }.leftOrNull()?.toString())
        assertEquals(3L, version(a), "3 saves -> version 3")
    }

    // Batch size 4 over 7 ops: chunks [a b a c] [a b a]. a repeats inside chunk 1 (the duplicate-key cut)
    // and crosses the chunk boundary; saves per id are 4/2/1, so a swapped or dropped count shows.
    @Test fun `commitYsqlBatched - plain node version counts every save across chunks`() = runBlocking {
        val (a, b, c) = List(3) { Uuid.random() }
        val order = listOf(a, b, a, c, a, b, a)
        val result = store.batchTransaction(4) { order.forEachIndexed { pos, id -> save(id, "b$pos") } }
        assertEquals(null, result.leftOrNull()?.toString(), "batchTransaction failed")
        assertEquals(listOf(4L, 2L, 1L), listOf(a, b, c).map(::version), "versions of a, b, c")
    }

    private suspend fun AbyssStoreTransactionLike.save(id: Uuid, name: String) =
        saveNode(UuidKeyAdapter.toNodeId(id), YbTestNode(id = id, name = name), emptySet())

    private fun version(id: Uuid): Long = DriverManager.getConnection(url, "abyss", "abyss").use { c ->
        c.prepareStatement("SELECT version FROM $schema.nodes WHERE id = ?").use { st ->
            st.setBytes(1, UuidKeyAdapter.toNodeId(id).bytes)
            st.executeQuery().use { rs -> check(rs.next()) { "node $id not stored" }; rs.getLong(1) }
        }
    }
}
