package pl.iqtech.abyss.graph

import com.hazelcast.config.ClasspathYamlConfig
import com.hazelcast.config.MaxSizePolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HazelcastConfigTest {

    private val cfg by lazy { ClasspathYamlConfig("hazelcast.yaml") }

    @Test fun `abyss-nodes has LRU eviction and max-idle`() {
        val map = cfg.getMapConfig("abyss-nodes")
        assertEquals(86400, map.maxIdleSeconds)
        assertEquals(MaxSizePolicy.FREE_HEAP_PERCENTAGE, map.evictionConfig.maxSizePolicy)
    }

    @Test fun `abyss-edges has HASH indexes on fromId and toId`() {
        val indexes = cfg.getMapConfig("abyss-edges").indexConfigs
        val attrs = indexes.flatMap { it.attributes }
        assertTrue("__key.fromIdHi" in attrs)
        assertTrue("__key.fromIdLo" in attrs)
        assertTrue("__key.toIdHi" in attrs)
        assertTrue("__key.toIdLo" in attrs)
        assertTrue(indexes.all { it.type.name == "HASH" })
    }
}
