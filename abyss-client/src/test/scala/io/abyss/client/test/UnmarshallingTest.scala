package io.abyss.client.test

import org.junit.Test
import io.abyss.client._
import java.util.{Date, UUID}

// Created by cane, 1/19/14 1:17 PM


case class TestData(id: String,
                    ts: Date)

class UnmarshallingTest {


    @Test
    def testUnmarshal() {
        val duc = DataUnmarshallingConfiguration(
            Map(
                TestData -> {
                    m: Map[ String, Any ] => TestData(
                        id = m("id").asInstanceOf[ String ],
                        ts = m("ts").asInstanceOf[ Date ])
                },
                AnyRef -> UnmarshalMap
            )
        )

        assert(duc.callbacks.contains(TestData))

        val map = Map(
            "id" -> UUID.randomUUID().toString,
            "ts" -> new Date)

        val td = duc.callbacks(TestData)(map)

        println("%s" format td.toString)


    }

}
