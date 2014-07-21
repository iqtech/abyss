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

package io.abyss.node.data

import akka.actor.Props
import akka.cluster.Member
import akka.routing.RoundRobinRouter
import java.util.concurrent.ConcurrentHashMap
import scala.collection.immutable
import io.abyss._
import io.abyss.client._
import io.abyss.node.{NotConfigured, PrepareQueryTraversable}
import io.abyss.graph.model.GraphElement


/**
 * Created by cane, 18.07.13 16:33
 * $Id: QueryManager.scala,v 1.3 2014-01-02 09:35:15 cane Exp $
 */
class QueryManager (val memory: ConcurrentHashMap[ String, GraphElement ]) extends AbyssActor {

	val NrOfSimpleQueryWorkerInstances = config.getInt (NumberOfQueryWorkersConfigKey)

	/**
	 * Router for simple queries
	 */
	private val simple = context.actorOf (Props (new QueryWorker (memory))
		.withRouter (RoundRobinRouter (nrOfInstances = NrOfSimpleQueryWorkerInstances)), "simple")

	private var memberShardMap: Option[ AddressShardMap ] = None

	private var myShards = immutable.Set.empty[ Short ]
	private var backNodes = immutable.Set.empty[ Member ]


	def receive = {

		case msg: PrepareQueryTraversable =>
			memberShardMap match {
				case Some (msm) =>
					val traversableWorker = context.actorOf (Props (new QueryTraversableWorker (
						memory,
						msg.shardMap
					)), msg.query.id)

					traversableWorker forward msg.query

				case None =>
					sender ! NotConfigured
			}

		case msg: Query =>
			simple forward msg

		case msg: ReconfigureShards =>
			memberShardMap = Some (msg.addressShardMap)
			myShards = msg.addressShardMap.shardsByAddress (cluster.selfAddress)
			backNodes = msg.clusterState.backNodes

		case msg =>
			log.warning ("Uncaught message: {}", msg)
	}

}
