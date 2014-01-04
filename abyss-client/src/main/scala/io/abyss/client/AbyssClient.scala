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
import akka.util.Timeout
import akka.routing.RoundRobinRouter
import akka.pattern.ask
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Random
import akka.cluster.Member

// Created by cane, 1/2/14 1:05 PM


trait AbyssClientState
case object Connecting extends AbyssClientState
case object Working extends AbyssClientState

trait AbyssClientData
case object NoData extends AbyssClientData
case class WorkingData(commandRouter: ActorRef, queryRouter: ActorRef) extends AbyssClientData

/**
 * Abyss client.
 * @param remotes Sequence of root actor paths for nodes to which client should try to connect
 */
class AbyssClient (remotes: Seq[ String ])
    extends Actor
    with FSM[AbyssClientState, AbyssClientData]
    with Stash {


    context.actorSelection (remotes.head) ! ClientSpawned(self)

    startWith(Connecting, NoData)

    when(Connecting) {
        case Event(msg: AbyssFrontMembers, NoData) =>
            val routeesCommand = Vector(AbyssClient.randomMember(msg.members).address.toString + "/user/node/front/command")
            val routeesQuery = msg.members.map (m => m.address.toString + "/user/node/front/query").toVector

            val commandRouter = context.actorOf (
                Props.empty.withRouter (RoundRobinRouter (routees = routeesCommand)))
            val queryRouter = context.actorOf (
                Props.empty.withRouter (RoundRobinRouter (routees = routeesQuery)))

            log.info ("Connected to members: {}", msg.members.mkString(","))

            goto (Working) using WorkingData(commandRouter, queryRouter)

        case Event(msg: Command, NoData) =>
            stash()
            stay()

        case Event(msg: Query, NoData) =>
            stash()
            stay()
    }

    when(Working) {
        case Event(msg: Command, sd: WorkingData) =>
            sd.commandRouter forward msg
            stay()

        case Event(msg: Query, sd: WorkingData) =>
            sd.queryRouter forward msg
            stay()
    }

    onTransition {
        case Connecting -> Working =>
            unstashAll()
    }

}



object AbyssClient {

    /**
     * Returns random member from given set of members.
     * @param members set of members
     * @return
     */
    private def randomMember(members: Set[Member]): Member = {
        val arr = members.toArray
        arr(Random.nextInt(arr.size))
    }

    /**
     * Creates
     * @param sys
     * @param actorName
     * @param remotes
     * @return
     */
    def apply (sys: ActorSystem, actorName: String, remotes: String*): ActorRef = {
        sys.actorOf (Props (new AbyssClient (remotes)), actorName)
    }


}
