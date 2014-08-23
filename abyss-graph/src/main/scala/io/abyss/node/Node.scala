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

import java.util.concurrent.ConcurrentHashMap

import akka.actor.{ActorRef, FSM, Props, Stash}
import akka.cluster.ClusterEvent.CurrentClusterState
import io.abyss._
import io.abyss.client._
import io.abyss.graph.model.GraphElement
import io.abyss.node.data.DataManager
import io.abyss.node.front.FrontendManager


/**
 * Holder for role actor references
 * @param front Front role actor's reference
 * @param data Data role actor's reference
 */
case class RolesRef(front: ActorRef,
                    data: ActorRef)


// FSM - state and data

trait NodeState

case object Initializing extends NodeState

case object Working extends NodeState


trait NodeData

case object NoData extends NodeData

case class WorkingData(abyssClusterState: AbyssClusterState,
                       roles: RolesRef) extends NodeData


/**
 * Starts node roles actors and forwards some messages to them.
 *
 * Created by cane, 22.06.13 17:50
 */
class NodeManager
    extends AbyssActor
    with FSM[ NodeState, NodeData ]
    with Stash {

    startWith(Initializing, NoData)

    when(Initializing) {
        case Event(newClusterState: CurrentClusterState, NoData) =>
            val newData = WorkingData(
                AbyssClusterState(newClusterState, None),

                // When node is not member of role then use 'dead letters' actor reference
                RolesRef(
                    front =
                        if ( nodeRoles.contains(FrontRoleName) ) context.actorOf(Props[ FrontendManager ], FrontRoleName)
                        else context.system.deadLetters,
                    data =
                        if ( nodeRoles.contains(DataRoleName) ) context.actorOf(Props[ DataManager ], DataRoleName)
                        else context.system.deadLetters
                )
            )

            // propagate Abyss Cluster State to role-bound actors
            if ( nodeRoles.contains(FrontRoleName) ) newData.roles.front forward newData.abyssClusterState
            if ( nodeRoles.contains(DataRoleName) ) newData.roles.data forward newData.abyssClusterState

            goto(Working) using newData


        case Event(msg: ClientSpawned, NoData) =>
            stash()
            stay()
    }


    when(Working) {
        case Event(msg: ClientSpawned, sd: WorkingData) =>
            sender ! AbyssFrontMembers(sd.abyssClusterState.frontNodes)
            stay()

        case Event(newClusterState: CurrentClusterState, sd: WorkingData) =>
            val acs = AbyssClusterState(newClusterState, Some(sd.abyssClusterState.clusterState))
            // TODO reconfigure node due to cluster membership changes (may possibly result in some kind of maintenance state)
            stay() using sd.copy(abyssClusterState = acs)
    }


    onTransition {
        case Initializing -> Working =>
            unstashAll()
    }


}


/**
 * Object is visible to every actor which is run on physical node. Holds global stuff like memory for graph elements.
 *
 * Created by cane, 12/2/13 4:36 PM
 */
object Node {

    // TODO move to configuration
    val InitialGraphMemorySize = 1000000
    val LoadFactor = 0.75f
    val ConcurrencyLevel = 16


    // TODO change map to hold AbstractGraphElement
    val memory = new ConcurrentHashMap[ String, GraphElement ](InitialGraphMemorySize, LoadFactor, ConcurrencyLevel)

}
