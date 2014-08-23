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

package io.abyss.node

// Created by cane, 1/6/14 12:25 PM

import akka.actor.Address
import akka.cluster.ClusterEvent._
import akka.cluster.{Cluster, Member, MemberStatus}
import io.abyss.{Abyss, AbyssActor}

class ClusterManager extends AbyssActor with ClusterMembersCollector {



	/**
	 * Set to true when OnMemberUp happened, otherwise is false. If node was previously Up then send current
	 * cluster state to NodeManager each time member is moved to Up or Removed state.
	 */
	var memberWasUp = false


	// -------------------------------------------------------------------------------------------------------


	Cluster (context.system) registerOnMemberUp {
		// when node comes up it sends current cluster state to node manager, which takes actions
		log.info ("I am up, starting roles: {}", nodeRoles.mkString(","))
		cluster sendCurrentClusterState Abyss.nodeManager
		memberWasUp = true
	}


	// -------------------------------------------------------------------------------------------------------


	/**
	 * Starting procedure:
	 * - subscribe to ClusterDomainEvent messages
	 */
	override def preStart (): Unit = {
		Cluster (context.system).subscribe (self, classOf[ ClusterDomainEvent ])
	}


	/**
	 * Stopping procedure
	 * - unsubscribe from events
	 */
	override def postStop (): Unit = {
		Cluster (context.system).unsubscribe (self)
	}


	def receive = {

		case MemberUp (member) =>
			log.debug ("Member is Up: {}", member.address)
			memberUp (member)
			if(memberWasUp) cluster sendCurrentClusterState Abyss.nodeManager

		case MemberRemoved (member, previousStatus) =>
			log.debug ("Member is Removed: {} after {}", member.address, previousStatus)
			memberRemoved (member, previousStatus)
			if(memberWasUp) cluster sendCurrentClusterState Abyss.nodeManager


		// TODO below processed events has no Abyss meaning now

		case UnreachableMember (member) =>
			// prepare for changes in cluster (for example degrade shards to write-through cache)
			log.debug ("Member detected as unreachable: {}", member)


		case LeaderChanged (newLeader) =>
			log.debug ("Leader changed: {}", newLeader.toString)
			leaderChanged (newLeader)


		case RoleLeaderChanged (role, leader) =>
			log.debug ("Role {} leader changed to {}", role, leader.toString)
			roleLeaderChanged(role, leader)


		case msg: CurrentClusterState =>
			log.info("Cluster state: {}", msg.toString)

		case msg: ClusterMetricsChanged =>

		// print out rest of ClusterDomainEvent messages
		case msg: ClusterDomainEvent =>
			log.debug ("Domain event not processed: %s" format msg.toString)
	}

}





trait ClusterMembersCollector  {


	/**
	 * Minimum cluster size before nodes goes to started state
	 * TODO Should be taken from Akka configuration file
	 */
	val MinClusterSize = 2


	var leader: Option[ Address ] = None

	var roleLeaders = Map.empty[ String, Option[ Address ] ]


	/**
	 * All cluster members sorted by age
	 */
	var membersByAge: collection.immutable.SortedSet[ Member ] =
		collection.immutable.SortedSet.empty(membersAgeOrdering)

	/**
	 * Front role members sorted by age
	 */
	var frontMembersByAge: collection.immutable.SortedSet[ Member ] =
		collection.immutable.SortedSet.empty(membersAgeOrdering)

	/**
	 * Back role members sorted by age
	 */
	var backMembersByAge: collection.immutable.SortedSet[ Member ] =
		collection.immutable.SortedSet.empty(membersAgeOrdering)


	/**
	 * Member has been moved to Up state by cluster leader
	 * @param member
	 */
	protected[node] def memberUp(member: Member) = {
		membersByAge += member

		if ( member.hasRole(FrontRoleName) ) {
			frontMembersByAge += member
		}

		if(membersByAge.size == MinClusterSize) {
			// TODO start node? what about convergence here? nodes works in separation!
		}
	}


	/**
	 * Member has been moved to Removed state by cluster leader
	 * @param member
	 * @param memberStatus
	 */
	protected[node] def memberRemoved(member: Member, memberStatus: MemberStatus) = {
		membersByAge -= member

		if ( member.hasRole(FrontRoleName) ) {
			frontMembersByAge -= member
		}

		if ( membersByAge.size < MinClusterSize ) {
			// TODO cluster degradation detected
		}
	}


	protected[node] def leaderChanged(newLeader: Option[Address]) = {
		leader = newLeader
	}


	protected[node] def roleLeaderChanged(role: String, newLeader: Option[Address]) = {
		newLeader match {
			case Some(a) => roleLeaders += (role -> newLeader)
			case None => roleLeaders -= role
		}

	}


	/**
	 * Builds singleton address from path. Singleton is always started on oldest node.
	 * TODO
	 * @param path
	 */
	def singletonAddress(path: String) = {

	}
}
