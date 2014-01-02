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

package io.abyss.node.data.persistence

import akka.actor.{ActorLogging, Actor}
import java.util.{UUID, Date}
import me.prettyprint.cassandra.serializers.StringSerializer
import me.prettyprint.cassandra.service.ThriftKsDef
import me.prettyprint.cassandra.service.template.{ColumnFamilyUpdater, ThriftColumnFamilyTemplate}
import me.prettyprint.hector.api.Keyspace
import me.prettyprint.hector.api.ddl.{ComparatorType, ColumnFamilyDefinition}
import me.prettyprint.hector.api.factory.HFactory
import scala.collection.JavaConverters
import io.abyss._
import io.abyss.node._
import io.abyss.graph.model._
import io.abyss.node.persistence._
import io.abyss.node.persistence.CollectionConsistencyConfig
import io.abyss.node.persistence.CassandraPersistenceProviderConfig
import io.abyss.node.ShardsOwned
import scala.Some
import io.abyss.node.persistence.DirtyVertex
import io.abyss.node.persistence.AbyssPersistenceConfig
import io.abyss.client.{VertexState, EdgeState, GraphElementState}

/*
 * Created by cane, 8/16/13 2:07 PM
 * $Id: CassandraPersistenceProvider.scala,v 1.2 2013-12-31 21:09:28 cane Exp $
 */


/**
 * Persistence provider for Apache Cassandra, stores graph structure in created database (a key space and
 * column families, called collections from now). Configuration is stored externally and provided via constructor.
 * Also, persisted types consistency RW levels are provided externally. See <code>persistence-test.conf</code> file
 * for details.
 * <p/>
 * Collection V stores vertex metadata (from <code>VertexState</code>). Collection E stores edge metadata
 * (from <code>EdgeState</code>). Data is stored in collections named after model's class name, so if you
 * store your data in class like:
 * <code>
 * case class Foo(id: String, ts: Date)
 * </code>
 * then Foo collection will be created in configured key space.
 *
 * @param abyssPersistenceConfig Persistence configuration for Abyss
 * @param collectionConsistencyConfig Configuration of consistency level for each collection
 */
class CassandraPersistenceProvider (abyssPersistenceConfig: AbyssPersistenceConfig,
									collectionConsistencyConfig: Array[ CollectionConsistencyConfig ])
	extends Actor with ActorLogging {

	val ReadBatchSize = 50

	val globalConfig = abyssPersistenceConfig.global

	val cassandraConfig = abyssPersistenceConfig.storage.asInstanceOf[ CassandraPersistenceProviderConfig ]

	val cassandraCluster = HFactory.getOrCreateCluster (cassandraConfig.name, cassandraConfig.nodes.mkString (","))

	var templateMap = Map.empty[ String, ThriftColumnFamilyTemplate[ String, String ] ]

	val abyssKeyspace = getOrCreateKeyspace (collectionConsistencyConfig)

	var myShards: Array[ Short ] = _

	log.info ("Cassandra Persistence Provider ready at path: {}", self.path)


	// TODO check shard id for every message which reads data from <code>Node.memory</code>
	// TODO provide a way to obtain MyShards

	def receive = {

		case acs: AbyssClusterState =>

		case msg: ShardsOwned =>
			myShards = msg.shards

		case msg: DirtyVertex =>
			save (Node.memory.get (msg.id).asInstanceOf[ Vertex ])

		case msg: DirtyEdge =>
			save (Node.memory.get (msg.id).asInstanceOf[ Edge ])

		case msg: Vertex =>
			save (msg)

		case msg: Edge =>
			save (msg)

		case msg: GraphElementState =>
			save (msg)

		case msg =>
			log.warning ("Unknown message received: {}", msg.toString)
	}


	def restore() {
		forEveryGraphElement("V") {
			id =>
				val v = readVertex(id)
				// TODO send it to shard to store, remote shards will forward the message to other node
		}
		forEveryGraphElement("E") {
			id =>
				val e = readEdge(id)
				// TODO send it to shard to store, remote shards will forward the message to other node
		}
	}


	private def readVertex (id: String): Vertex = {
		val kvMap = attributesMap ("V", id)
		Vertex (kvMap)
	}


	private def readEdge (id: String): Edge = {
		val kvMap = attributesMap ("E", id)
		Edge (kvMap)
	}


	def attributesMap (cfn: String, id: String): Map[ String, Any ] = {
		val c = templateMap (cfn)
		val rec = c.queryColumns (id)
		val cns = JavaConverters.collectionAsScalaIterableConverter (rec.getColumnNames).asScala

		val kvMap = ( Map.empty[ String, Any ] /: cns ) {
			(map, columnName) =>
				log.debug ("Reading column {} of {}", columnName, cfn)
				val column = rec.getColumn (columnName)
				map + ( columnName -> column.getValue )
		}
		kvMap
	}


	private def forEveryGraphElement (columnFamilyName: String) (vertexFunction: ( String ) => Unit) = {
		var continue = true
		var lastId = ""

		while ( continue ) {
			var ids = nextElementsId (columnFamilyName, lastId)
			continue = if ( ids.size > ReadBatchSize ) true else false
			lastId = ids.last
			ids = ids.dropRight (1)
			ids foreach vertexFunction
		}
	}


	/**
	 * Returns ReadBatchSize + 1 keys, use last one to start retrieving next batch. If number of returned keys is less than
	 * ReadBatchSize + 1 then it's last batch.
	 * @param lastId
	 * @return
	 */
	private def nextElementsId (columnFamilyName: String, lastId: String): Array[ String ] = {

		val rangeSlicesQuery = HFactory.createRangeSlicesQuery (abyssKeyspace, StringSerializer.get (), StringSerializer.get (), StringSerializer.get ())

		rangeSlicesQuery.setColumnFamily (columnFamilyName)
		rangeSlicesQuery.setKeys (lastId, "")
		rangeSlicesQuery.setReturnKeysOnly ()
		rangeSlicesQuery.setRowCount (ReadBatchSize + 1)

		//rangeSlicesQuery.setRange("", "", false, 0)  ---- looks like it tells how many columns to retrieve

		val result = rangeSlicesQuery.execute ()
		val it = JavaConverters.asScalaIteratorConverter (result.get ().iterator ()).asScala

		it.map (_.getKey).toArray
	}



	/**
	 * Persists Vertex - in fact should persist index of edges
	 * @param model
	 */
	def save (model: Vertex) {
		save (model.state)
		// TODO save indices
	}


	/**
	 * Persists Edge - in fact nothing to save now :-)
	 * @param model
	 */
	def save (model: Edge) {
		save (model.state)
		// TODO save node types on both sides
	}


	def save (element: GraphElementState) {
		val dataCollection = templateMap (element.data.getClass.getSimpleName)
		val dataUpdater = dataCollection.createUpdater (element.id)


		val (elementCollection, elementUpdater) = element match {
			case v: VertexState =>
				val c = templateMap ("V")
				val u = c.createUpdater (element.id)
				(c, u)
			case e: EdgeState =>
				val c = templateMap ("E")
				val u = c.createUpdater (element.id)
				(c, u)
		}

		// TODO if(stateUpdater.contains.....)

		val elementMap = ccToMap (element)
		setDataUpdater (elementUpdater, elementMap)
		elementUpdater.update ()
		elementCollection.update (elementUpdater)

		val dataMap = ccToMap (element.data)
		setDataUpdater (dataUpdater, dataMap)
		dataUpdater.update ()
		dataCollection.update (dataUpdater)

		//cassandraCluster.getConnectionManager.shutdown()
	}


	/**
	 * Sets values from map using overloaded method, in given column family updater
	 * @param du
	 * @param map
	 */
	private def setDataUpdater (du: ColumnFamilyUpdater[ String, String ], map: Map[ String, Any ]) {
		map foreach {
			kvTuple =>
				log.debug ("Setting updater: %s -> %s".format (kvTuple._1, kvTuple._2.toString))
				setDataUpdater (du, kvTuple._1, kvTuple._2)
		}
	}


	/**
	 * Sets value v for attribute defined in k in given column family updater
	 * @param du
	 * @param k
	 * @param v
	 */
	private def setDataUpdater (du: ColumnFamilyUpdater[ String, String ], k: String, v: Any) {
		v match {
			case v: Boolean => du.setBoolean (k, v)
			case v: Short => du.setInteger (k, v)
			case v: Int => du.setInteger (k, v)
			case v: Long => du.setLong (k, v)
			case v: Float => du.setDouble (k, v.toDouble)
			case v: Double => du.setDouble (k, v)
			case v: BigDecimal => du.setDouble (k, v.toDouble)
			case v: Date => du.setDate (k, v)
			case v: UUID => du.setUUID (k, v)
			case v: Array[ Byte ] => du.setByteArray (k, v)
			case v: String => du.setString (k, v)
			case _ =>
		}
	}


	/**
	 * Adds template for given column family to map
	 * @param cfName
	 */
	private def addTemplateToMap (cfName: String, ks: Keyspace) {
		log.debug ("Adding cf template: {}", cfName)
		templateMap +=
			cfName ->
				new ThriftColumnFamilyTemplate[ String, String ](
					ks,
					cfName,
					StringSerializer.get (),
					StringSerializer.get ())
	}


	/**
	 * Tries to load key space. If exists then tries to update schema (add column families).
	 * If no schema then one is created with schema passed in 'consistencyConfig' array.
	 * @param consistencyConfig
	 * @return
	 */
	private def getOrCreateKeyspace (consistencyConfig: Array[ CollectionConsistencyConfig ]): Keyspace = {
		var ks = cassandraCluster.describeKeyspace (cassandraConfig.keySpace)
		if ( ks == null ) {
			log.info ("Key space '%s' not present, initializing..".format (cassandraConfig.keySpace))

			var cfs = Array.empty[ ColumnFamilyDefinition ]
			consistencyConfig.foreach {
				t =>
					cfs = cfs :+ {
						log.info ("Added data column family: {}", t.collection)
						HFactory.createColumnFamilyDefinition (
							cassandraConfig.keySpace, t.collection, ComparatorType.BYTESTYPE)
					}
			}

			cfs = cfs :+ {
				HFactory.createColumnFamilyDefinition (
					cassandraConfig.keySpace, "V", ComparatorType.BYTESTYPE)
			} :+ {
				HFactory.createColumnFamilyDefinition (
					cassandraConfig.keySpace, "E", ComparatorType.BYTESTYPE)
			}

			log.info ("Added graph column family: V")
			log.info ("Added graph column family: E")

			val replicationFactor = cassandraConfig.replicationFactor

			ks = HFactory.createKeyspaceDefinition (cassandraConfig.keySpace,
				ThriftKsDef.DEF_STRATEGY_CLASS,
				replicationFactor,
				JavaConverters.seqAsJavaListConverter (cfs).asJava)
			cassandraCluster.addKeyspace (ks, true)
			log.info ("Key space '%s' initialization done.".format (cassandraConfig.keySpace))
		}
		else {
			log.info ("Key space '%s' already present, reconfiguring..".format (cassandraConfig.keySpace))

			val columnFamilies = JavaConverters.collectionAsScalaIterableConverter (ks.getCfDefs).asScala
			var reload = false

			// new types must be registered
			consistencyConfig.foreach {
				t =>
					columnFamilies.find (_.getName == t.collection) match {
						case Some (cfd) =>
						case None =>
							log.info ("Added data column family: {}", t.collection)
							val cfd = HFactory.createColumnFamilyDefinition (
								cassandraConfig.keySpace, t.collection, ComparatorType.BYTESTYPE)
							cassandraCluster.addColumnFamily (cfd, true)
							reload = true
					}
			}
			if ( reload ) ks = cassandraCluster.describeKeyspace (cassandraConfig.keySpace)
			log.info ("Key space '%s' reconfiguration done.".format (cassandraConfig.keySpace))
		}

		val kspace = HFactory.createKeyspace (cassandraConfig.keySpace, cassandraCluster);

		consistencyConfig.foreach {
			t =>
				addTemplateToMap (t.collection, kspace)
		}

		addTemplateToMap ("V", kspace)
		addTemplateToMap ("E", kspace)

		kspace
	}


}
