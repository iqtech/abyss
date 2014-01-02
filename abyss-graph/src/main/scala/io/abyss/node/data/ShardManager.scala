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

package io.abyss.node.data

import akka.actor.{Address, ActorRef, Props}
import akka.cluster.ClusterEvent.CurrentClusterState
import java.util.concurrent.ConcurrentHashMap
import scala.Some
import scala.collection.{immutable, mutable}
import io.abyss.AbyssActor
import io.abyss.node._
import io.abyss.graph.model.GraphElement


/**
 * Typed shards (remote, cache, write through and write behind cache, local), currently all local or remote.
 * Created by cane, 18.06.13 13:34
 * $Id: ShardManager.scala,v 1.3 2014-01-02 09:35:15 cane Exp $
 */


class ShardManager(val memory: ConcurrentHashMap[ String, GraphElement ]) extends AbyssActor {



	/**
	 * Array of actors bound to shards.
	 */
	private val shardActors = new Array[ ActorRef ](NumberOfShards)

	/**
	 * Current shards mapping
	 */
	var memberShardMap: Option[ AddressShardMap ] = None

	var myShards: immutable.SortedSet[ Short ] = _

	// TODO seems not to be used (what seems to be perfectly ok)
	@deprecated
	var notMyShards: immutable.SortedSet[ Short ] = _


	// Create shard workers, one for each shard (number of shards per node defines concurrency level)
	0 to NumberOfShards - 1 map {
		i =>
			val ref = context.actorOf(Props(new ShardWorker(i.toShort, memory)), i.toString)
			shardActors(i) = ref
	}



	/**
	 * Events processing
	 * @return
	 */
	def receive = {

		// Node manager decided to propagate cluster state so it's obviously different from one seen previously.
		// Check for new and removed nodes.

		case abyssClusterState: AbyssClusterState =>
			memberShardMap match {
				case Some(_) =>
					// use new and removed to calculate new shards mapping

					val newBack = abyssClusterState.newNodes.filter(_.hasRole(DataRoleName))
					val removedBack = abyssClusterState.removedNodes.filter(_.hasRole(DataRoleName))

					/*val newFront = acs.newNodes.filter(_.hasRole(FrontRoleName))
					val removedFront = acs.removedNodes.filter(_.hasRole(FrontRoleName))*/

					if ( !newBack.isEmpty || !removedBack.isEmpty ) {
						log.info("Reconfiguring shards")
						// TODO new shards doMap must be created here
					}
					else {
						log.info("No new or removed backends, no shards reconfiguration")
					}

				case None =>

                    log.info("Configuring shards for the first time")

					memberShardMap = Some(initialMemberShardMap(abyssClusterState.currentClusterState))

					log.info("Member to shard doMap contains {} entries", memberShardMap.get.arr.size)

					// first time initialization of shard ownership sets
					myShards = immutable.SortedSet.empty[ Short ]
					notMyShards = immutable.SortedSet.empty[ Short ]

					memberShardMap.get.map foreach {
						tuple =>
							if ( tuple._1 == cluster.selfAddress ) {
								log.info("Shards owned by me: {}", tuple._2.mkString(","))
								myShards ++= tuple._2
							}
							else {
								notMyShards ++= tuple._2
							}
					}

					val reconfigureReq = ReconfigureShards(clusterStart = true, /*myShards, notMyShards,*/ memberShardMap.get, abyssClusterState)

					context.actorSelection("*") ! reconfigureReq
                    context.actorSelection("../query") ! reconfigureReq

                    context.parent ! ShardsOwned(cluster.selfAddress, myShards.toArray)

					log.info("Shard workers reconfiguration request sent")
			}

		case msg =>
	}


	/**
	 * // TODO ask current data role leader for new doMap, block until response will come
	 * Creates initial shard table, which simply spreads ReplicaFactor times available members over
	 * shards. Returns array of members and doMap member-shard identifiers in single container.
	 * @param cs
	 * @return
	 */
	private def initialMemberShardMap(cs: CurrentClusterState) = {
		val backMembers = roleMembersMap(cs)(DataRoleName).toArray
		val arr = Array.ofDim[ Option[ Address ] ](NumberOfShards, ReplicationFactor)
		val map = mutable.HashMap.empty[ Address, immutable.SortedSet[ Short ] ]
			.withDefaultValue(immutable.SortedSet.empty[ Short ])

		if ( backMembers.size > 0 ) {

			log.info("{} data members, creating initial shards mapping..", backMembers.size)

			0 to ReplicationFactor - 1 foreach {
				r =>
					0 to NumberOfShards - 1 foreach {
						i =>
							val member = backMembers(( i + r ) % backMembers.size)
							arr(i)(r) = Some(member.address)
							map(member.address) = map(member.address) + i.toShort
					}
			}
		}

		AddressShardMap(arr, map.toMap)
	}

}
