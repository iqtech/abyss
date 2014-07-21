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

package io.abyss.test.graph

import org.junit.Test
import akka.actor.ActorSystem
import io.abyss.node.persistence.CollectionConsistencyConfig

/**
 * Created by cane, 12/1/13 5:01 PM
 * $Id: TestPersistenceConfiguration.scala,v 1.2 2013-12-31 21:09:28 cane Exp $
 */
class TestPersistenceConfiguration extends PersistenceTestBase {

	@Test
	def testReading {
		val pc = readPersistenceConfiguration(system)
		pc.foreach(println)
	}


	def readPersistenceConfiguration(actorSystem: ActorSystem) = {
		val collectionsConf = actorSystem.settings.config.getConfigList("abyss.data.consistency.collections")
		println(collectionsConf.toString)

		var res = Array.empty [CollectionConsistencyConfig]
		0 to collectionsConf.size() - 1 foreach {
			i =>
				val colConf = collectionsConf.get(i)
				val collection = colConf.getString("name")

				// TODO cr and cw should be declared as strings, then mapped to enum
				//val cr = if(colConf.hasPath("cr")) colConf.getInt("cr") else 1
				//val cw = if(colConf.hasPath("cw")) colConf.getInt("cw") else 1

				val tpp = CollectionConsistencyConfig(collection)
				res = res :+ tpp
		}

		res
	}


}
