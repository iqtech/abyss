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

package io.abyss.node.front

import akka.actor.{Props, Address, RootActorPath}
import akka.cluster.Member
import akka.util.Timeout
import akka.pattern.ask
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import io.abyss._
import io.abyss.node._
import io.abyss.graph.internal.GraphElementState


/**
 * Created by cane, 09.07.13 21:33
 * $Id: QueryEndpoint.scala,v 1.2 2013-12-31 21:09:28 cane Exp $
 */
class QueryEndpoint extends AbyssActor {

	implicit val defaultTimeout = Timeout (2 seconds)

	private var backNodes = collection.immutable.SortedSet.empty[ Member ]

	private var shardNodes: Array[ Set[ Address ] ] = null


	def receive = {

		case msg: QueryTraversable =>

			// nodes to talk to are chosen from shard map
			val coordinator = context.actorOf (Props (new TraversingCoordinator (shardMapForQuery)))
			coordinator forward msg


		case msg: ReadMany =>

			// client who wants data
			val askedBy = sender

			// group by node taken from shard id TODO don't use head only, use as many as defined by consistency level

			val grouped = msg.ids.groupBy {
				id => shardNodes (shardId (id)).head
			}

			log.debug ("ReadMany processing -> gathering results from nodes")

			// gather results, make union and return to client

			val futures = grouped map {
				t =>
					log.info ("Sending ReadMany to {}", t._1.toString)
					val dataQuery = context actorSelection (RootActorPath (t._1) / "user" / "node" / "data" / "query")
					( dataQuery ? msg.copy (ids = t._2) ).mapTo[ Array[ GraphElementState ] ]
			}

			val resFuture = Future.fold (futures)(Array.empty[ GraphElementState ])(_ ++ _)

			resFuture foreach {
				res =>
					log.info ("Returning results")
					askedBy ! res
			}


		// simple id query TODO should ask many data nodes, according to consistency level and shard mapping
		case msg: Query =>
			val queryWorker = context.actorSelection (RootActorPath (backNodes.head.address) / "user" / "node" / "data" / "query")
			val f = for {
				r <- ( queryWorker ? msg ).mapTo[ Any ]
			} yield r

			val askedBy = sender

			f foreach {
				res => askedBy ! res
			}


		// cluster state has been distributed
		case acs: AbyssClusterState =>
			backNodes = acs.backNodes

		// shards were calculated
		case msg: ShardNodeMap =>
			shardNodes = msg.arr

		// by default warn about messages not processed
		case msg =>
			log.warning ("Uncaught message: {}", msg)
	}


	/**
	 * Returns shards mapped to address for this query. Selects nodes which will be used. In current implementation
	 * all nodes are selected, as if consistency level was set to 1.
	 * TODO node may have some shards cached or consistency level may be greater than 1
	 * @return
	 */
	def shardMapForQuery: Array[ Address ] = {
		shardNodes.map (_.head)
	}


}
