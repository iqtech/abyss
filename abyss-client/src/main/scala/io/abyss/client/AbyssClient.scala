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

package io.abyss.client

import akka.actor._
import akka.routing.RoundRobinRouter
import scala.util.Random
import akka.cluster.Member

// Created by cane, 1/2/14 1:05 PM


// States and data for FSM

trait AbyssClientState
case object AbyssClientConnecting extends AbyssClientState
case object AbyssClientWorking extends AbyssClientState

trait AbyssClientData
case object NoData extends AbyssClientData
case class WorkingData(commandRouter: ActorRef, queryRouter: ActorRef) extends AbyssClientData

/**
 * Abyss client.
 * @param remotes Sequence of root actor paths for nodes to which client should try to connect
 */
class AbyssClient(remotes: Seq[ String ])
    extends Actor
    with FSM[ AbyssClientState, AbyssClientData ]
    with Stash {

    log info "Trying to connect to remotes: %s".format(remotes.mkString(","))

    context.actorSelection(remotes.head) ! ClientSpawned(self)

    startWith(AbyssClientConnecting, NoData)

    when(AbyssClientConnecting) {
        case Event(msg: AbyssFrontMembers, NoData) =>
            val routeesCommand = Vector(AbyssClient.randomMember(msg.members).address.toString + "/user/node/front/command")
            val routeesQuery = msg.members.map(m => m.address.toString + "/user/node/front/query").toVector

            val commandRouter = context.actorOf(Props.empty.withRouter(RoundRobinRouter(routees = routeesCommand)))
            val queryRouter = context.actorOf(Props.empty.withRouter(RoundRobinRouter(routees = routeesQuery)))

            log.info("Connected to members: {}", msg.members.mkString(","))

            goto(AbyssClientWorking) using WorkingData(commandRouter, queryRouter)

        case Event(msg: Command, NoData) =>
            stash()
            stay()

        case Event(msg: Query, NoData) =>
            stash()
            stay()
    }

    when(AbyssClientWorking) {
        case Event(msg: Command, sd: WorkingData) =>
            sd.commandRouter forward msg
            stay()

        case Event(msg: Query, sd: WorkingData) =>
            sd.queryRouter forward msg
            stay()
    }

    onTransition {
        case AbyssClientConnecting -> AbyssClientWorking =>
            unstashAll()
    }

}


object AbyssClient {

    /**
     * Returns random member from given set of members.
     * @param members set of members
     * @return
     */
    private def randomMember(members: Set[ Member ]): Member = {
        val arr = members.toArray
        arr(Random.nextInt(arr.size))
    }

    /**
     * Creates client actor and returns its reference
     * @param sys System in which client must be created
     * @param actorName Name of actor (will be started ass top-level actor in user space)
     * @param remotes Sequence of actor addresses of graph front actors
     * @return Refernce to created actor
     */
    def apply(sys: ActorSystem, actorName: String, remotes: String*): ActorRef = {
        sys.actorOf(Props(new AbyssClient(remotes)), actorName)
    }


}
