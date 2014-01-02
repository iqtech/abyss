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

package io.abyss.node

import akka.actor.Props
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._
import io.abyss.{Abyss, AbyssActor}
import pl.iqtech.abyss.node.ClusterProxy


/**
 * Created by cane, 12.06.13 00:44
 * $Id: ClusterManager.scala,v 1.3 2014-01-02 09:35:15 cane Exp $
 */
class ClusterManager extends AbyssActor with ClusterMembersCollector {

	/**
	 * Cluster proxy object, knows the cluster and can forward messages to other cluster nodes.
	 */
	val proxy = context.actorOf (Props[ ClusterProxy ], "proxy")


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
