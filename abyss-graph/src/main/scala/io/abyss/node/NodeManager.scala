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

import akka.actor.{FSM, Props}
import akka.cluster.ClusterEvent.CurrentClusterState
import io.abyss.node.front.FrontendManager
import io.abyss.node.data.DataManager
import io.abyss._
import io.abyss.client._


trait NodeState

case object Initializing extends NodeState

case object Working extends NodeState


trait NodeData

case object NoData extends NodeData

case class WorkingData(lastClusterState: CurrentClusterState,
					   lastAbyssClusterState: AbyssClusterState) extends NodeData

/**
 * Starts node roles actors and forwards some messages to them.
 *
 * Created by cane, 22.06.13 17:50
 */
class NodeManager
	extends AbyssActor
	with FSM[NodeState, NodeData] {

	// TODO these actors should be replaced with some kind of proxy when not in role

	/**
	 * Frontend manager
	 */
	val frontRole = context.actorOf (Props[FrontendManager], FrontRoleName)

	/**
	 * Backend data manager
	 */
	val dataRole = context.actorOf (Props[DataManager], DataRoleName)


	startWith(Initializing, NoData)

	when(Initializing) {
		case Event(currentClusterState: CurrentClusterState, NoData) =>
			val acs = AbyssClusterState (currentClusterState, None)

			// propagate Abyss Cluster State to role-bound actors
			if (nodeRoles.contains (FrontRoleName)) frontRole forward acs
			if (nodeRoles.contains (DataRoleName)) dataRole forward acs

			goto(Working) using WorkingData(currentClusterState, acs)
	}

	when(Working) {
		case Event(msg: ClientSpawned, sd: WorkingData) =>
			sender ! AbyssFrontMembers(sd.lastAbyssClusterState.frontNodes)
			stay()

		case Event(currentClusterState: CurrentClusterState, sd: WorkingData) =>
			val acs = AbyssClusterState (currentClusterState, Some(sd.lastClusterState))

			// TODO reconfigure node due to cluster membership changes (may possibly result in some kind of maintenance state)
			stay() using WorkingData(currentClusterState, acs)
	}



}

