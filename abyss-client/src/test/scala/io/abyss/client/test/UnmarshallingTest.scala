/*
 * Copyright 2013-2014 IQ TECH <http://www.iqtech.pl>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
