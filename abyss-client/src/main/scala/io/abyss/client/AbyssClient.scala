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

object AbyssClient {

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



/**
 * Abyss client. May work in 'local' mode when run in 'abyss' actor system and surrounding logic is run as
 * one of the node's role.
 * @param remotes Sequence of root actor paths for nodes to which Abyss Client should try to connect
 */
class AbyssClient (remotes: Seq[ String ]) extends Actor with ActorLogging {
	implicit val timeout = Timeout (2 seconds)
	implicit val duration = 2.seconds

	// TODO try to connect next if fail, not necessary when in local mode

	val f = ( context.actorSelection (remotes.head) ? ClientConnected () ).mapTo[ AbyssFrontMembers ]

	val remoteMembers = Await.result (f, duration)

	val routeesCommand = Vector(randomMember(remoteMembers.members).address.toString + "/user/node/front/command")
	val routeesQuery = remoteMembers.members.map (m => m.address.toString + "/user/node/front/query").toVector


	// TODO command actor should be selected by cluster and identify itself in returned message

	/**
	 * Routes all messages to randomly selected front command endpoint
	 */
	val commandRouter = context.actorOf (
		Props.empty.withRouter (RoundRobinRouter (routees = routeesCommand)))


	/**
	 * Round robin router, will forward all messages to detected fronts
	 */
	val queryRouter = context.actorOf (
		Props.empty.withRouter (RoundRobinRouter (routees = routeesQuery)))

	log.info ("Connected to members: {}", remoteMembers.toString)


	/**
	 *
	 * @return
	 */
	def receive = {
		case msg: Command =>
			commandRouter.forward(msg)
		case msg: Query =>
			queryRouter.forward (msg)
	}


	/**
	 * Returns random member from given set of members.
	 * @param members set of members
	 * @return
	 */
	private def randomMember(members: Set[Member]): Member = {
		val arr = members.toArray
		arr(Random.nextInt(remoteMembers.members.size))
	}

}
