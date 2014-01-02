/*
 * This software is licensed under the Apache 2 license, quoted below.
 *
 * Copyright 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package io.abyss.test.graph

import org.junit.Test
import me.prettyprint.hector.api.factory.HFactory
import me.prettyprint.hector.api.ddl.ComparatorType
import me.prettyprint.cassandra.service.ThriftKsDef
import java.util
import me.prettyprint.cassandra.service.template.ThriftColumnFamilyTemplate
import me.prettyprint.cassandra.serializers.{DateSerializer, LongSerializer, StringSerializer}
import java.util.{UUID, Date}
import akka.actor.Props
import scala.util.Random
import io.abyss._
import io.abyss.node.data.persistence.CassandraPersistenceProvider
import io.abyss.graph.internal.{EdgeState, VertexState}
import io.abyss.node.persistence.{CollectionConsistencyConfig, AbyssDefaultPersistenceConfig}

/*
 * Created by cane, 11/30/13 9:15 PM
 * $Id: PersistenceCassandraTest.scala,v 1.2 2013-12-31 21:09:28 cane Exp $
 */


case class TestModel(id: String, x: Int, y: Int)

case class TestModelAbc(id: String, x: Int, y: Int)

case class TestEdge(id: String, name: String, description: String)


class PersistenceCassandraTest extends PersistenceTestBase {

	val apc = AbyssDefaultPersistenceConfig()

	@Test
	def testPersistenceProvider() {

		val types = Array(
			CollectionConsistencyConfig(clazzName(TestModel.getClass)),
			CollectionConsistencyConfig(clazzName(TestModelAbc.getClass)),
			CollectionConsistencyConfig(clazzName(TestEdge.getClass))
		)

		val cpp = system.actorOf(Props(new CassandraPersistenceProvider(apc, types)))

		val id1 = UUID.randomUUID().toString
		val model1 = TestModel(id1, Random.nextInt(), Random.nextInt())
		cpp ! VertexState(model1.id, "g1", Some(model1))

		val id2 = UUID.randomUUID().toString
		val model2 = TestModelAbc(id2, Random.nextInt(), Random.nextInt())
		cpp ! VertexState(model2.id, "g3", Some(model2))

		val id3 = UUID.randomUUID().toString
		val edge1 = TestEdge(id3, "contains", "Contains other element")
		cpp ! EdgeState(id3, "g3", id1, id2, true, Some(edge1))

		awaitTermination(120)
		system.shutdown()
	}




	final val KeyspaceName = "quarkRepositoryTest"
	final val CollectionName = "gluon"


	@Test
	def testCreateKeyspace() {

		val cluster = HFactory.getOrCreateCluster("test-cluster", "localhost:9160")

		val keyspaceDefinition = cluster.describeKeyspace(KeyspaceName)
		if ( keyspaceDefinition == null ) {

			val cfDef = HFactory.createColumnFamilyDefinition(KeyspaceName,
				CollectionName,
				ComparatorType.BYTESTYPE)

			val replicationFactor = 1

			val newKeyspace = HFactory.createKeyspaceDefinition(KeyspaceName,
				ThriftKsDef.DEF_STRATEGY_CLASS,
				replicationFactor,
				util.Arrays.asList(cfDef))

			cluster.addKeyspace(newKeyspace, true)
		}

		val testKeyspace = HFactory.createKeyspace(KeyspaceName, cluster)

		println("Keyspace is: %s" format testKeyspace.getKeyspaceName)

		val gluonCollection = new ThriftColumnFamilyTemplate[ String, String ](
			testKeyspace,
			CollectionName,
			StringSerializer.get(),
			StringSerializer.get())


		val key = UUID.randomUUID().toString
		val updater = gluonCollection.createUpdater(key)
		updater.setString("domain", "www.iqtech.pl")
		updater.setLong("time", System.currentTimeMillis)
		updater.setDate("date", new Date())
		gluonCollection.update(updater)

		println("Record has been updated with current timestamp, reading..")

		val res = gluonCollection.queryColumns(key)
		val value = res.getString("domain")
		val ts = res.getLong("time")
		val date = res.getDate("date")

		println("I read (%s,%s,%s) from cassandra".format(value, ts, date))

		val scDomain = gluonCollection.querySingleColumn(key, "domain", StringSerializer.get())
		val scTime = gluonCollection.querySingleColumn(key, "time", LongSerializer.get())
		val scDate = gluonCollection.querySingleColumn(key, "date", DateSerializer.get())

		println("Single column read: (%s, %s, %s)".format(scDomain.getValue, scTime.getValue, scDate.getValue))

		cluster.getConnectionManager.shutdown()
	}


}
