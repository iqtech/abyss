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

package io.abyss

import akka.actor.{ActorRef, Address}
import akka.cluster.ClusterEvent.CurrentClusterState
import akka.cluster.Member
import io.abyss.client.QueryTraversable

import scala.collection.immutable


package object node {


	// TODO selected values should be configured externally





	/**
	 * Current replication factor
	 */
	final val ReplicationFactor = 1

	// role names

	final val FrontRoleName = "front"
	final val DataRoleName = "data"

	// currently not implemented

	final val IndexRoleName = "index"

	/**
	 * TODO Short number defining data center we live in.
	 * @return
	 */
	val dataCenterId: Short = 0


	/**
	 * Sorting by age function for cluster member.
	 */
	val membersAgeOrdering = Ordering.fromLessThan[ Member ] {
		(a, b) => a isOlderThan b
	}





	/**
	 * Returns role to members map, built upon current cluster state. Sets are ordered by members age (oldest first).
	 * @param cs Current cluster state
	 * @return Map role to set of members ordered by age
	 */
	def roleMembersMap(cs: CurrentClusterState) = {
		val roleMembersMap = collection.mutable.HashMap
			.empty[ String, immutable.SortedSet[ Member ] ]
			.withDefaultValue(immutable.SortedSet.empty[ Member ](membersAgeOrdering))

		cs.members foreach {
			member =>
				member.roles.foreach {
					r =>
						roleMembersMap(r) = roleMembersMap(r) + member
				}
		}

		roleMembersMap.toMap
	}



	object AbyssConsistencyLevel extends Enumeration {
		type AbyssConsistencyLevel = Value
		val Any, One, Two, Three, LocalQuorum, EachQuorum, Quorum, All = Value
	}



	case class AbyssClusterState(nodes: immutable.SortedSet[ Member ],
								 frontNodes: immutable.SortedSet[ Member ],
								 backNodes: immutable.SortedSet[ Member ],
								 newNodes: immutable.SortedSet[ Member ],
								 removedNodes: immutable.SortedSet[ Member ],
								 clusterState: CurrentClusterState)


	object AbyssClusterState {
		def apply(cs: CurrentClusterState, lastCs: Option[ CurrentClusterState ]): AbyssClusterState = {
			val rmm = roleMembersMap(cs)
			val (newNodes, removedNodes) = lastCs match {
				case Some(lcs) =>
					(cs.members -- lcs.members, lcs.members -- cs.members)
				case None =>
					(cs.members, immutable.SortedSet.empty[ Member ](membersAgeOrdering))
			}
			AbyssClusterState(
				cs.members,
				rmm.getOrElse(FrontRoleName, immutable.SortedSet.empty[ Member ](membersAgeOrdering)),
				rmm.getOrElse(DataRoleName, immutable.SortedSet.empty[ Member ](membersAgeOrdering)),
				newNodes,
				removedNodes,
				cs)
		}
	}


	case class ShardsOwned(address: Address, shards: Array[Short])


	/**
	 * Maps nodes in cluster (in role 'data') to shards owned by them. For each shard set of nodes are stored. Set
	 * size reflects consistency level value.
	 * @param arr
	 */
	case class ShardNodeMap(arr: Array[Set[Address]])


	/**
	 * Generic message returned by not configured actors
	 */
	case object NotConfigured


	/**
	 * Simple acknowledgement of being ready to process work
	 * @param ref
	 */
	case class WorkerReady(ref: ActorRef)


	/**
	 * Fires-up coordinated work
	 * @param coordinator
	 */
	case class StartCoordinatedWork(coordinator: ActorRef)


	/**
	 * May be sent to coordinator many times during processing
	 * @param data Any data, interpreted by coordinator
	 */
	case class CoordinatedWorkMilestone(data: Any)

	/**
	 * Tells coordinator that work is done
	 * @param data Any data, interpreted by coordinator
	 */
	case class CoordinatedWorkDone(data: Any)



	case class PrepareQueryTraversable(query: QueryTraversable, shardMap: Array[Address])


	/**
	 * Use it to send info about who is interested in results
	 * @param ref
	 */
	case class StartWorkFor(ref: ActorRef)

}
