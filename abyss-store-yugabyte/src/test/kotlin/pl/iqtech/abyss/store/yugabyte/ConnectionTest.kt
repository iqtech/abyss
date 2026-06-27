package pl.iqtech.abyss.store.yugabyte

import com.datastax.oss.driver.api.core.CqlSession
import java.net.InetSocketAddress
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertNotNull

class ConnectionTest {

    @Test
    fun `ysql - can select from abyss nodes`() {
        DriverManager.getConnection(
            "jdbc:postgresql://localhost:5433/abyss_test_graph",
            "abyss",
            "abyss"
        ).use { conn ->
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("SELECT * FROM abyss.nodes LIMIT 1")
                assertNotNull(rs)
            }
        }
    }

    @Test
    fun `ysql - can select from abyss edges`() {
        DriverManager.getConnection(
            "jdbc:postgresql://localhost:5433/abyss_test_graph",
            "abyss",
            "abyss"
        ).use { conn ->
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("SELECT * FROM abyss.edges LIMIT 1")
                assertNotNull(rs)
            }
        }
    }

    @Test
    fun `ycql - can select from ephemeral nodes`() {
        CqlSession.builder()
            .addContactPoint(InetSocketAddress("localhost", 9042))
            .withLocalDatacenter("datacenter1")
            .build()
            .use { session ->
                val rs = session.execute("SELECT * FROM abyss_test_graph.ephemeral_nodes LIMIT 1")
                assertNotNull(rs.columnDefinitions)
            }
    }

    @Test
    fun `ycql - can select from ephemeral edges`() {
        CqlSession.builder()
            .addContactPoint(InetSocketAddress("localhost", 9042))
            .withLocalDatacenter("datacenter1")
            .build()
            .use { session ->
                val rs = session.execute("SELECT * FROM abyss_test_graph.ephemeral_edges LIMIT 1")
                assertNotNull(rs.columnDefinitions)
            }
    }

    @Test
    fun `ycql - can select from ephemeral reverse edges`() {
        CqlSession.builder()
            .addContactPoint(InetSocketAddress("localhost", 9042))
            .withLocalDatacenter("datacenter1")
            .build()
            .use { session ->
                val rs = session.execute("SELECT * FROM abyss_test_graph.ephemeral_reverse_edges LIMIT 1")
                assertNotNull(rs.columnDefinitions)
            }
    }
}
