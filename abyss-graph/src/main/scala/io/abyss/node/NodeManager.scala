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
import akka.cluster.ClusterEvent.CurrentClusterState
import io.abyss.node.front.FrontendManager
import io.abyss.node.data.DataManager
import io.abyss._
import io.abyss.client._

/**
 * Starts node roles actors and forwards some messages to them.
 *
 * Created by cane, 22.06.13 17:50
 * $Id: NodeManager.scala,v 1.2 2013-12-31 21:09:28 cane Exp $
 */
class NodeManager extends AbyssActor {

	// TODO these actors should be replaced with some kind of proxy when not in role

	/**
	 * Frontend manager
	 */
	val frontend = context.actorOf(Props[ FrontendManager ], FrontRoleName)

	/**
	 * Backend manager
	 * TODO a subject to change - role 'data' currently, change to 'backend'
	 */
	val backend = context.actorOf(Props[ DataManager ], DataRoleName)



	/**
	 * Last cluster state seen by NodeManager, None at the beginning.
	 */
	var lastClusterState: Option[ CurrentClusterState ] = None
	var lastAbyssClusterState: Option[ AbyssClusterState ] = None


	def receive = {

		// Received when ClusterManager goes up or cluster membership changes.
		// This message is sent by cluster itself, on demand or alone.

		case currentClusterState: CurrentClusterState =>
			val acs = AbyssClusterState(currentClusterState, lastClusterState)

			if ( !lastClusterState.equals(currentClusterState) ) {
				if ( nodeRoles.contains(FrontRoleName) ) frontend forward acs
				if ( nodeRoles.contains(DataRoleName) ) backend forward acs

				lastClusterState = Some(currentClusterState)
				lastAbyssClusterState = Some(acs)
			}

		case _: ClientConnected =>
			sender ! AbyssFrontMembers(lastAbyssClusterState.get.frontNodes)


		case msg =>
			log.warning("Unknown message {} received from {}", msg.toString, sender.path)
	}


}

